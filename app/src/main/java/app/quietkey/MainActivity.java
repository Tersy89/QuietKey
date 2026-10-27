package app.quietkey;

import android.app.AlertDialog;
import android.app.ActivityManager;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.fragment.app.FragmentActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.io.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends FragmentActivity {
    private static final int BG=0xff080f1d, CARD=0xff111e30, LINE=0xff20344b, WHITE=0xffedf5ff, MUTED=0xff93a9bf, GREEN=0xff4be3c1;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private Vault vault;
    private PasswordLock passwordLock;
    private volatile int session;
    private volatile boolean sessionLocked=true;
    private boolean handoff;private long handoffUntil;
    private android.app.Dialog transientDialog;
    private NearbyJoinDialog nearbyJoin;
    private Protocol.Group nearbyCandidate;
    private android.app.Dialog nearbyConsent;
    private final Set<CompletableFuture<?>> nearbyApprovals=new HashSet<>();
    private BiometricPrompt biometricPrompt;
    private int biometricAttempt;
    private String restoredGroup;
    private final BroadcastReceiver screenLock=new BroadcastReceiver(){public void onReceive(Context c,Intent i){lockNow();}};
    private volatile boolean broken;
    private LinearLayout root,body,nav;
    private ScrollView scroll;
    private TextView progress,output;
    private EditText message;
    private Protocol.Group selected;
    private String page="home",ownId,recipientId,pendingName,capturePurpose,scanAction,scanGroupId,fileGroupId,fileRecipientId,fileAction;
    private boolean busy,foreground;
    private long ttlMillis,fileTtlMillis,preparedExpiresAt;
    private final ArrayList<View> recipientActions=new ArrayList<>();
    private byte[] waitingDigest;
    private String waitingQr;
    private File encryptedFile;
    private FileProtocol.Verified verifiedFile;
    private ActivityResultLauncher<Intent> capture,saveFile,joinScanner,connectionSettings;
    private ActivityResultLauncher<String[]> openFile;
    private interface Job<T>{T run()throws Exception;}
    private interface Success<T>{void run(T result)throws Exception;}

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);passwordLock=new PasswordLock(this);registerReceiver(screenLock,new IntentFilter(Intent.ACTION_SCREEN_OFF));getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(saved!=null) {
            pendingName=saved.getString("pendingName");capturePurpose=saved.getString("capturePurpose");
            scanAction=saved.getString("scanAction");scanGroupId=saved.getString("scanGroupId");
            fileAction=saved.getString("fileAction");fileGroupId=saved.getString("fileGroupId");fileRecipientId=saved.getString("fileRecipientId");
            page=saved.getString("page","home");restoredGroup=saved.getString("selectedGroupId");
            ttlMillis=saved.getLong("ttlMillis",0);fileTtlMillis=saved.getLong("fileTtlMillis",0);
            recipientId=saved.getString("recipientId");
        }
        capture=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            returnedFromHandoff();
            if(result.getResultCode()!=RESULT_OK || result.getData()==null)return;
            byte[] digest=result.getData().getByteArrayExtra("digest");
            returnedFromHandoff();if(vault==null){if(digest!=null)Arrays.fill(digest,(byte)0);error("Unlock and start camera capture again.");return;}captured(digest);
        });
        joinScanner=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{returnedFromHandoff();if(result.getResultCode()==RESULT_OK && result.getData()!=null){if(result.getData().getBooleanExtra("paste",false)){if(vault!=null)pasteTransfer(scanAction,group(scanGroupId));}else{String packet=result.getData().getStringExtra("packet");if(vault==null)waitingQr=packet;else scanned(packet);}}else if(vault!=null&&"join".equals(scanAction))startJoining(false);});
        openFile=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{
            returnedFromHandoff();if(uri!=null){if(vault==null)error("Please choose the file again after initialization.");else pickedFile(uri);}
        });
        saveFile=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            returnedFromHandoff();if(result.getResultCode()==RESULT_OK && result.getData()!=null && result.getData().getData()!=null){if(vault==null){try{DocumentsContract.deleteDocument(getContentResolver(),result.getData().getData());}catch(Exception ignored){}error("Unlock and choose the encrypted file again.");}else exportFile(result.getData().getData());}
        });
        connectionSettings=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{returnedFromHandoff();if(vault!=null)render();});
        shell();lockScreen();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);out.putString("pendingName",pendingName);out.putString("capturePurpose",capturePurpose);
        out.putString("scanAction",scanAction);out.putString("scanGroupId",scanGroupId);out.putString("page",page);
        out.putString("fileAction",fileAction);out.putString("fileGroupId",fileGroupId);out.putString("fileRecipientId",fileRecipientId);
        out.putLong("ttlMillis",ttlMillis);out.putLong("fileTtlMillis",fileTtlMillis);out.putString("recipientId",recipientId);if(selected!=null)out.putString("selectedGroupId",selected.id);
    }
    private void shell() {
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(12),dp(20),dp(12));root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(20),insets.getSystemWindowInsetTop()+dp(12),dp(20),insets.getSystemWindowInsetBottom()+dp(12));return insets;});
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.ic_enigma);icon.setContentDescription("Enigma shield");header.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout brand=column();brand.addView(label("ENIGMA",22,WHITE));header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(label("● LOCAL",10,GREEN));root.addView(header);
        progress=label("",11,MUTED);root.addView(progress);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        body=column();body.setPadding(0,dp(12),0,dp(18));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        nav=new LinearLayout(this);nav.setPadding(0,dp(8),0,0);root.addView(nav);setContentView(root);
    }
    private void render() {
        if(isDestroyed())return;if(vault==null){lockScreen();return;}
        body.removeAllViews();message=null;output=null;nav.removeAllViews();recipientActions.clear();
        progress.setText("Working…");progress.setVisibility(busy?View.VISIBLE:View.GONE);
        if(!vault.setupCompleted){setup();return;}
        String[] tabs={"home","messages","groups","identity"},names={"Home","Messages","Groups","Device"};
        for(int i=0;i<tabs.length;i++){
            String tab=tabs[i];Button b=button(names[i],()->{page=tab;render();});b.setTextSize(10);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);
            boolean active=tab.equals(page) || (tab.equals("messages") && (page.equals("files") || page.equals("compose"))) || (tab.equals("groups") && (page.equals("members") || page.equals("group-details"))) || (tab.equals("identity") && page.equals("security"));
            b.setTextColor(active?BG:MUTED);b.setBackground(shape(active?GREEN:CARD,12,0));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.setMargins(dp(2),0,dp(2),0);nav.addView(b,p);
        }
        switch(page){case "messages":messages();break;case "compose":compose();break;case "files":files();break;case "groups":groups();break;case "members":memberList();break;case "group-details":groupDetails();break;case "security":securitySettings();break;case "identity":identity();break;default:home();}
        scroll.post(()->scroll.scrollTo(0,0));
    }
    private void setup() {
        title("Set up your device");note("Name your device, then move its camera for five seconds. No video is saved.");
        EditText name=editor("Device name, e.g. Chris's phone",false);name.setText(vault.deviceName.equals("This device")?"":vault.deviceName);limit(name,40);body.addView(name);
        body.addView(primary("Set up with live camera",()->{
            String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("Name this device");return;}
            pendingName=value;capturePurpose="setup";beginHandoff();capture.launch(new Intent(this,CameraActivity.class).putExtra("purpose","setup"));
        }));
        if(!vault.groups.isEmpty()){
            note("Your existing identity and "+vault.groups.size()+" group(s) will be kept.");
            body.addView(button("Continue with existing groups",()->{
                String value=name.getText().toString().trim();work(()->{vault.deviceName=value.isEmpty()?"This device":value;vault.setupCompleted=true;persist();return true;},ok->{page="home";render();});
            }));
        }

    }
    private void captured(byte[] digest) {
        String name=pendingName;boolean setup="setup".equals(capturePurpose);
        if(name==null || digest==null){error("Capture interrupted. Start the setup or group capture again.");return;}
        work(()->{
            try {
                if(vault.groups.size()>=30)throw new IllegalArgumentException("Maximum 30 groups. Remove an unused group before creating another.");
                Protocol.Group group=Protocol.create(setup?"Starter group":name,digest,vault.publicKey());
                group.memberNames.put(ownId,setup?name:vault.deviceName);PairingProtocol.signRoster(group,vault.publicKey(),vault.privateKey());vault.groups.add(group);
                if(setup){vault.deviceName=name;vault.personalGroupId=group.id;vault.setupCompleted=true;vault.setupCapturedAt=System.currentTimeMillis();}
                persist();return group;
            }finally{Arrays.fill(digest,(byte)0);}
        },group->{selected=group;chooseDefaultRecipient();page=setup?"home":"groups";capturePurpose=null;render();});
    }
    private void home() {
        title("Send privately");
        if(selected==null){note("Create or join a group to start.");body.addView(primary("Set up a group",()->{page="groups";render();}));return;}
        selectors();
        body.addView(recipientButton("Write a message",()->{page="compose";render();}));
        body.addView(recipientButton("Send a file or image",()->{page="files";render();}));
    }
    private void identity() {
        title("About me & my device");note(vault.deviceName+" · Enigma "+BuildConfig.VERSION_NAME);
        note(vault.setupCapturedAt>0?"Device setup complete":"Device setup pending");
        body.addView(primary("How to use Enigma",this::guide));
        body.addView(button("Security & unlock",()->{page="security";render();}));
        body.addView(button("My fingerprint",()->{try{qrDialog("My device fingerprint",Protocol.identity(vault.publicKey()),"For joining, use Groups → Join group.\n\n"+pretty(ownId));}catch(Exception e){error(e.getMessage());}}));
        body.addView(button("Rename this device",()->editName("Device name",vault.deviceName,40,value->work(()->{vault.deviceName=value;for(Protocol.Group g:vault.groups)g.memberNames.put(ownId,value);persist();return true;},ok->render()))));
        if(vault.personalGroupId==null)body.addView(button("Create starter group with camera",()->{pendingName=vault.deviceName;capturePurpose="setup";beginHandoff();capture.launch(new Intent(this,CameraActivity.class).putExtra("purpose","setup"));}));
        body.addView(button("Reset app & delete local data",()->reauth("Confirm reset",this::confirmReset)));
    }
    private void securitySettings(){
        title("Security & unlock");body.addView(button("Back to device",()->{page="identity";render();}));
        body.addView(primary("Lock Enigma now",this::lockNow));body.addView(button("Change app passphrase",this::changePassword));
        body.addView(button(passwordLock.biometricConfigured()?"Disable biometric unlock":"Enable biometric unlock",()->reauth("Confirm security change",()->{if(passwordLock.biometricConfigured())work(()->{passwordLock.disableBiometric();return true;},ok->render());else biometric(true);})));
        note("Biometrics unlock the app. Your passphrase is still required to change security settings, reset or export readable files.");
    }
    private void groups() {
        title("Groups");LinearLayout actions=new LinearLayout(this);Button create=button("Create a group",this::newGroup),join=button("Join group",this::startJoining);actions.addView(create,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams jp=new LinearLayout.LayoutParams(0,-2,1);jp.setMargins(dp(8),0,0,0);actions.addView(join,jp);body.addView(actions);
        if(vault.groups.isEmpty())note("No groups yet.");
        for(Protocol.Group g:vault.groups){body.addView(button(g.name,()->{selected=g;chooseDefaultRecipient();page="group-details";render();}));note(g.members.size()+" members · "+(isOwner(g)?"You own this group":"Member"));}
    }
    private void groupDetails(){
        Protocol.Group g=selected;if(g==null){page="groups";groups();return;}
        title(g.name);body.addView(button("Back to groups",()->{page="groups";render();}));
        if(isOwner(g)){
            body.addView(primary("Invite new recipient",()->inviteInstructions(g)));
        }
        body.addView(button("Manage members ("+g.members.size()+")",()->{page="members";render();}));
        body.addView(button("Send a message",()->{page="home";render();}));
        body.addView(button("Rename this group",()->editName("Group name",g.name,60,value->work(()->{g.name=value;persist();return true;},ok->render()))));
        body.addView(button("Remove group from this device",()->deleteGroup(g)));
    }
    private void newGroup() {editName("Create a group","",60,value->{pendingName=value;capturePurpose="group";beginHandoff();capture.launch(new Intent(this,CameraActivity.class).putExtra("purpose","group"));});}
    private void deleteGroup(Protocol.Group g) {
        transientDialog=new AlertDialog.Builder(this).setTitle("Remove "+g.name+"?").setMessage("This removes its secret, trusted members and replay history from this device. Other members keep their keys. Rejoining resets replay protection.")
            .setNegativeButton("Keep group",null).setPositiveButton("Remove",(d,w)->work(()->{JoiningFlow.cancel(g.id,null,vault.pendingInvites,vault.invitationCache);vault.groups.remove(g);vault.history.removeIf(r->r.group.equals(g.id));if(g.id.equals(vault.personalGroupId))vault.personalGroupId=null;persist();cleanupHistory(vault);Arrays.fill(g.seed,(byte)0);return true;},ok->{if(selected==g)selected=vault.groups.isEmpty()?null:vault.groups.get(0);closeVerified();encryptedFile=null;chooseDefaultRecipient();render();})).show();
    }
    private boolean isOwner(Protocol.Group g){return ownId!=null && g.owner.equals(ownId);}
    private Protocol.Group group(String id){if(vault!=null)for(Protocol.Group g:vault.groups)if(g.id.equals(id))return g;return null;}
    private void chooseDefaultRecipient(){recipientId=null;}
    private boolean hasRecipient(){return selected!=null && recipientId!=null && !recipientId.equals(ownId) && selected.members.containsKey(recipientId);}
    private void requireRecipient()throws Exception{if(!hasRecipient())throw new SecurityException("Choose another verified member as recipient first");DirectProtocol.recipient(selected,recipientId);}
    private Button recipientButton(String text,Runnable action){Button b=primary(text,()->{try{requireRecipient();action.run();}catch(Exception e){error(e.getMessage());}});recipientActions.add(b);b.setEnabled(hasRecipient() && !busy);b.setAlpha(b.isEnabled()?1f:.4f);return b;}
    private void refreshSendActions(){for(View b:recipientActions){b.setEnabled(hasRecipient() && !busy);b.setAlpha(b.isEnabled()?1f:.4f);}}
    private String memberName(Protocol.Group g,String id){if(id.equals(ownId))return "My device";String name=g.memberNames.get(id);return name==null?"Member "+id.substring(0,Math.min(8,id.length())):name;}
    private void scan(String action,Protocol.Group g) {
        if("invite".equals(action) && (g==null || !isOwner(g))){error("Only the group creator can invite members.");return;}
        scanAction=action;scanGroupId=g==null?null:g.id;
        beginHandoff(PairingProtocol.WINDOW);joinScanner.launch(new Intent(this,JoinScanActivity.class).putExtra("expected",action.equals("invite")?"QKJ1:":action.equals("receipt")?"QKA1:":"QKG2:"));
    }
    private void inviteInstructions(Protocol.Group g){joiningMethods(g);}
    private void startJoining(){joiningMethods(null);}
    private void joiningMethods(Protocol.Group g){
        if(vault==null)return;if(g!=null&&!isOwner(g)){error("Only the group owner can invite");return;}
        ArrayList<String> names=new ArrayList<>(Arrays.asList("USB cable (preferred)","Personal hotspot","QR fallback"));ArrayList<Runnable> actions=new ArrayList<>();
        actions.add(()->startNearby(g,true));actions.add(()->startNearby(g,false));actions.add(()->{if(g==null)startJoining(false);else scan("invite",g);});
        if(g!=null){for(Map.Entry<String,JoiningFlow.Invitation> entry:vault.invitationCache.entrySet()){JoiningFlow.Invitation invitation=entry.getValue();
            if(invitation.group.equals(g.id)&&invitation.expires>System.currentTimeMillis()&&"Invited".equals(g.memberStatus.get(invitation.member))&&g.members.containsKey(invitation.member)&&invitation.member.equals(vault.pendingInvites.get(entry.getKey()))){names.add("Continue QR invitation: "+memberName(g,invitation.member));actions.add(()->{scanAction="invite";scanGroupId=g.id;scanned(invitation.request);});}}
            names.add("Scan QR confirmation");actions.add(()->scan("receipt",g));}
        transientDialog=new AlertDialog.Builder(this).setTitle(g==null?"Join a group":"Invite to "+g.name).setItems(names.toArray(new String[0]),(d,w)->actions.get(w).run()).setNegativeButton("Cancel",null).show();
    }
    private void startNearby(Protocol.Group g,boolean usb){
        if(vault==null||busy)return;stopNearby();
        if(g!=null){openNearby(g,usb,null);return;}
        work(()->{vault.joiningRequest=JoiningFlow.request(vault.publicKey(),vault.privateKey(),vault.deviceName,vault.joiningRequest,vault.pairingUsed,true);persist();return vault.joiningRequest;},request->openNearby(null,usb,request));
    }
    private void openNearby(Protocol.Group g,boolean usb,String request){
        final NearbyJoinDialog[] holder=new NearbyJoinDialog[1];
        NearbyJoinDialog.Callbacks callbacks=new NearbyJoinDialog.Callbacks(){
            private <T> CompletableFuture<T> pending(){CompletableFuture<T> future=new CompletableFuture<>();ui.post(()->{if(!active())future.completeExceptionally(new SecurityException("Joining closed"));else nearbyApprovals.add(future);});return future;}
            private boolean active(){return nearbyJoin==holder[0]&&!holder[0].closed()&&vault!=null&&!sessionLocked&&foreground;}
            public void status(String text){holder[0].status(text);}
            public CompletableFuture<String> invite(String packet){CompletableFuture<String> result=pending();ui.post(()->{if(!active()){result.completeExceptionally(new SecurityException("Unlock and retry"));return;}
                work(()->{try{if(g==null||!isOwner(g))throw new SecurityException("Only the owner can invite");return PairingProtocol.parseRequest(packet);}catch(Exception e){result.completeExceptionally(e);throw e;}},r->{
                    if(!active()||result.isDone())return;
                    nearbyConsent=transientDialog=new AlertDialog.Builder(MainActivity.this).setTitle("Invite "+r.name+"?").setMessage("Add this device to "+g.name+". Confirm this is the person connected to you.\n\nDevice fingerprint\n"+pretty(Protocol.fingerprint(r.key)))
                    .setNegativeButton("Decline",(d,w)->{result.completeExceptionally(new SecurityException("Owner declined"));holder[0].failure("Invitation declined. Cancel to return to the group.");})
                    .setOnCancelListener(d->result.completeExceptionally(new SecurityException("Owner cancelled")))
                    .setPositiveButton("Invite",(d,w)->{if(!active()){result.completeExceptionally(new SecurityException("Joining closed"));return;}work(()->{try{String grant=JoiningFlow.invite(g,packet,vault.publicKey(),vault.privateKey(),vault.pairingUsed,vault.pendingInvites,vault.invitationCache);persist();return grant;}catch(Exception e){result.completeExceptionally(e);throw e;}},grant->{selected=g;result.complete(grant);});}).show();
                });});return result;
            }
            public CompletableFuture<String> accept(String invitation){CompletableFuture<String> result=pending();ui.post(()->{if(!active()){result.completeExceptionally(new SecurityException("Unlock and retry"));return;}
                work(()->{try{if(vault.joiningRequest.isEmpty()||!vault.joiningRequest.equals(request))throw new SecurityException("Joining session changed");PairingProtocol.Request r=PairingProtocol.parseRequest(request);if(vault.pairingUsed.contains(r.id)||vault.pairingUsed.size()>=10000)throw new SecurityException("Joining request unavailable");
                    Protocol.Group candidate=PairingProtocol.accept(invitation,request,vault.publicKey(),vault.privateKey());if(group(candidate.id)!=null||vault.groups.size()>=30){Arrays.fill(candidate.seed,(byte)0);throw new SecurityException("Group already exists or group limit reached; history was preserved");}return candidate;
                }catch(Exception e){result.completeExceptionally(e);throw e;}},candidate->{if(!active()||result.isDone()){Arrays.fill(candidate.seed,(byte)0);return;}nearbyCandidate=candidate;
                    nearbyConsent=transientDialog=new AlertDialog.Builder(MainActivity.this).setTitle("Join "+candidate.name+"?").setMessage("Confirm the owner is the person connected to you. Group details and "+candidate.members.size()+" member public keys will be added together.\n\nOwner fingerprint\n"+pretty(candidate.owner))
                    .setNegativeButton("Decline",(d,w)->{Arrays.fill(candidate.seed,(byte)0);nearbyCandidate=null;result.completeExceptionally(new SecurityException("Recipient declined"));holder[0].failure("Joining declined. No group was saved on this phone.");})
                    .setOnCancelListener(d->{Arrays.fill(candidate.seed,(byte)0);nearbyCandidate=null;result.completeExceptionally(new SecurityException("Recipient cancelled"));})
                    .setPositiveButton("Join group",(d,w)->{if(!active()){result.completeExceptionally(new SecurityException("Joining closed"));return;}work(()->{try{
                        PairingProtocol.Request r=PairingProtocol.parseRequest(request);if(!request.equals(vault.joiningRequest)||vault.pairingUsed.contains(r.id))throw new SecurityException("Joining session changed");
                        if(group(candidate.id)!=null||vault.groups.size()>=30)throw new SecurityException("Group cannot be added");String receipt=PairingProtocol.receipt(candidate,r.id,vault.publicKey(),vault.privateKey());vault.groups.add(candidate);vault.pairingUsed.add(r.id);vault.joiningRequest="";persist();return receipt;
                    }catch(Exception e){result.completeExceptionally(e);throw e;}},receipt->{nearbyCandidate=null;selected=candidate;chooseDefaultRecipient();page="groups";holder[0].markCommitted();result.complete(receipt);});}).show();
                });});return result;
            }
            public CompletableFuture<Void> receipt(String packet){CompletableFuture<Void> result=pending();ui.post(()->{if(!active()){result.completeExceptionally(new SecurityException("Unlock and retry"));return;}work(()->{try{if(g==null)throw new SecurityException("No owner group");PairingProtocol.receiptMember(g,packet,vault.pendingInvites);vault.invitationCache.entrySet().removeIf(e->!vault.pendingInvites.containsKey(e.getKey()));persist();return true;}catch(Exception e){result.completeExceptionally(e);throw e;}},ok->result.complete(null));});return result;}
            public void finished(){if(vault!=null){page=g==null?"groups":"group-details";render();}}
            public void stopped(){if(nearbyJoin==holder[0])stopNearby();}
            public void transportStopped(){if(nearbyJoin==holder[0])cancelNearbyApprovals();}
            public void permissionPrompt(){beginHandoff(PairingProtocol.WINDOW);}
            public void permissionReturned(){returnedFromHandoff();}
            public void settings(){beginHandoff();try{connectionSettings.launch(new Intent(usb?android.provider.Settings.ACTION_SETTINGS:android.provider.Settings.ACTION_WIRELESS_SETTINGS));}catch(Exception e){returnedFromHandoff();error("Open Android Settings to configure your hotspot or Wi-Fi, then return to Enigma.");}}
        };
        holder[0]=new NearbyJoinDialog(this,g!=null,usb,request,callbacks);nearbyJoin=holder[0];nearbyJoin.show();
    }
    private void cancelNearbyApprovals(){for(CompletableFuture<?> approval:nearbyApprovals)approval.completeExceptionally(new SecurityException("Joining closed"));nearbyApprovals.clear();if(nearbyConsent!=null){nearbyConsent.dismiss();nearbyConsent=null;}Protocol.Group candidate=nearbyCandidate;Vault current=vault;nearbyCandidate=null;if(candidate!=null&&!worker.isShutdown())worker.execute(()->{if(current==null||!current.groups.contains(candidate))Arrays.fill(candidate.seed,(byte)0);});}
    private void stopNearby(){NearbyJoinDialog previous=nearbyJoin;nearbyJoin=null;if(previous!=null){previous.close();if(previous.isShowing())previous.dismiss();}cancelNearbyApprovals();}
    private void startJoining(boolean fresh){
        work(()->{vault.joiningRequest=JoiningFlow.request(vault.publicKey(),vault.privateKey(),vault.deviceName,vault.joiningRequest,vault.pairingUsed,fresh);persist();return vault.joiningRequest;},request->showTransfer(request,()->scan("join",null)));
    }
    private void pasteTransfer(String action,Protocol.Group g){
        EditText packet=editor(action.equals("invite")?"Paste their copied joining request":"Paste the owner's copied invitation",true);limit(packet,32000);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(action.equals("invite")?"Step 1 · Paste joining request":"Step 2 · Paste owner's invitation").setView(packet).setNegativeButton("Cancel",(d,w)->{if(action.equals("join"))startJoining();}).setPositiveButton("Continue",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String wire=packet.getText().toString().replaceAll("\\s","");String prefix=action.equals("invite")?"QKJ1:":"QKG2:";if(!wire.startsWith(prefix)){packet.setError("Copy the complete "+(action.equals("invite")?"joining request":"invitation")+" from the other phone");return;}dialog.dismiss();scanAction=action;scanGroupId=g==null?null:g.id;scanned(wire);}));transientDialog=dialog;dialog.show();
    }

    private void scanned(String qr){
        Protocol.Group g=group(scanGroupId);
        if("join".equals(scanAction))join(qr);
        else if(g!=null && "receipt".equals(scanAction))work(()->{String member=PairingProtocol.receiptMember(g,qr,vault.pendingInvites);vault.invitationCache.entrySet().removeIf(entry->!vault.pendingInvites.containsKey(entry.getKey()));persist();return member;},id->{render();Toast.makeText(this,memberName(g,id)+" joined",Toast.LENGTH_LONG).show();});
        else if(g!=null && "invite".equals(scanAction))work(()->PairingProtocol.parseRequest(qr),request->{
            transientDialog=new AlertDialog.Builder(this).setTitle("Invite "+request.name+"?").setMessage("Invite this device to "+g.name+". Scan directly from their Join group screen. Their public fingerprint is available below for verification.\n\n"+pretty(Protocol.fingerprint(request.key))).setNegativeButton("Cancel",null).setPositiveButton("Invite",(d,w)->work(()->{
                String packet=JoiningFlow.invite(g,qr,vault.publicKey(),vault.privateKey(),vault.pairingUsed,vault.pendingInvites,vault.invitationCache);persist();return packet;
            },packet->{selected=g;chooseDefaultRecipient();page="group-details";render();qrDialog("Owner · step 2 of 2",packet,"On the OTHER phone: tap Scan owner's invitation and scan every frame here. When this group appears on their phone, joining is complete. No second invite in the other direction is needed. If the camera fails, Copy transfer and use Paste owner's invitation there. You can reopen this invitation from group details until it expires.");})).show();
        });else error("Joining interrupted. Start again from Groups.");
    }
    private void join(String qr){
        work(()->{
            if(vault.joiningRequest.isEmpty())throw new SecurityException("Tap Join group first to create your request");PairingProtocol.Request request=PairingProtocol.parseRequest(vault.joiningRequest);
            if(vault.pairingUsed.contains(request.id))throw new SecurityException("Joining invitation already consumed");if(vault.pairingUsed.size()>=10000)throw new SecurityException("Joining history full");Protocol.Group joined=PairingProtocol.accept(qr,vault.joiningRequest,vault.publicKey(),vault.privateKey());
            if(group(joined.id)!=null){Arrays.fill(joined.seed,(byte)0);throw new SecurityException("This group is already on your device; history was preserved");}if(vault.groups.size()>=30)throw new IllegalArgumentException("Maximum 30 groups");
            String receipt=PairingProtocol.receipt(joined,request.id,vault.publicKey(),vault.privateKey());vault.groups.add(joined);vault.pairingUsed.add(request.id);vault.joiningRequest="";persist();return new Object[]{joined,receipt};
        },result->{selected=(Protocol.Group)result[0];chooseDefaultRecipient();page="groups";render();transientDialog=new AlertDialog.Builder(this).setTitle("Joined "+selected.name).setMessage("Joining is complete. Group details and member keys are ready.\n\nHome → choose this group → choose a recipient to send text or a file. You do not need to invite the owner back. The owner’s Invited status changes to Joined after your first verified message or an optional confirmation.").setPositiveButton("Done",null).setNeutralButton("Show confirmation QR",(d,w)->{try{qrDialog("Optional joining confirmation",(String)result[1],"The owner can scan this from group details. Your first verified message also confirms joining.");}catch(Exception e){error(e.getMessage());}}).show();});
    }
    private void memberList() {
        Protocol.Group g=selected;if(g==null){page="groups";groups();return;}
        title(g.name+" members");body.addView(button("Back to group",()->{page="group-details";render();}));
        for(String id:new ArrayList<>(g.members.keySet())){
            LinearLayout card=card();card.addView(label(memberName(g,id)+(id.equals(g.owner)?" · owner":" · "+g.memberStatus.getOrDefault(id,"Member")),18,WHITE));card.addView(label(pretty(id),12,MUTED));
            if(!id.equals(ownId)){
                card.addView(button("Name this member",()->editName("Member name",g.memberNames.getOrDefault(id,""),40,value->work(()->{g.memberNames.put(id,value);persist();return true;},ok->render()))));
                if(!id.equals(g.owner))card.addView(button("Remove local trust",()->transientDialog=new AlertDialog.Builder(this).setTitle("Remove local trust?").setMessage("This device will stop accepting this member's signatures and offering them as a recipient. Their existing group key is not revoked. Create a new group to replace a shared group secret.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->work(()->{JoiningFlow.cancel(g.id,id,vault.pendingInvites,vault.invitationCache);g.members.remove(id);g.memberNames.remove(id);g.memberStatus.remove(id);g.blocked.add(id);if(isOwner(g))PairingProtocol.signRoster(g,vault.publicKey(),vault.privateKey());persist();return true;},ok->{chooseDefaultRecipient();render();})).show()));
            }body.addView(card);
        }
    }
    private void selectors() {
        if(selected==null)return;
        body.addView(label("GROUP",10,GREEN));
        Spinner groups=new Spinner(this);List<String> names=new ArrayList<>();for(Protocol.Group g:vault.groups)names.add(g.name);
        groups.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));groups.setSelection(vault.groups.indexOf(selected));
        groups.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(selected!=vault.groups.get(pos)){selected=vault.groups.get(pos);chooseDefaultRecipient();render();}}});body.addView(groups);
        body.addView(label("PRIVATE RECIPIENT",10,GREEN));
        List<String> ids=new ArrayList<>(),labels=new ArrayList<>();ids.add(null);labels.add("Choose verified recipient");for(String id:selected.members.keySet())if(!id.equals(ownId)){ids.add(id);labels.add(memberName(selected,id)+" · "+id.substring(0,8));}
        if(!ids.contains(recipientId))chooseDefaultRecipient();Spinner recipients=new Spinner(this);
        recipients.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));recipients.setSelection(Math.max(0,ids.indexOf(recipientId)));
        recipients.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){recipientId=ids.get(pos);refreshSendActions();}});body.addView(recipients);
        if(ids.size()==1)note("No other verified member yet. Invite or pair with someone in Groups before sending.");

    }
    private void messages() {
        title("Messages");note("All your text and files, together. Stored encrypted on this device.");
        if(selected==null)body.addView(primary("Set up a group",()->{page="groups";render();}));
        else{
            body.addView(primary("New text message",()->{page="compose";render();}));
            body.addView(button("Attach a file or image",()->{page="files";render();}));
            body.addView(button("Open received text",()->{page="compose";render();}));
            body.addView(button("Receive an encrypted file",()->{page="files";render();pickFile("decrypt");}));
        }
        showHistory();
    }
    private void compose() {
        title("Write or receive text");body.addView(button("Back to messages",()->{page="messages";render();}));if(selected==null){body.addView(primary("Manage groups",()->{page="groups";render();}));return;}
        selectors();Protocol.Group g=selected;
        ttlSelector();
        message=editor("Write privately, or paste encrypted text…",true);body.addView(message,new LinearLayout.LayoutParams(-1,dp(145)));
        body.addView(recipientButton("Encrypt for selected member",()->{String clear=message.getText().toString(),to=recipientId;long ttl=ttlMillis;work(()->{
            String wire=DirectProtocol.encrypt(g,to,clear,vault.publicKey(),vault.privateKey(),ttl);DirectProtocol.Opened opened=DirectProtocol.decrypt(g,wire,vault.publicKey(),vault.privateKey());
            remember(new MessageRecord(opened.messageId,g.id,ownId,to,"text",wire,"Private text",opened.createdAt,opened.expiresAt,true));return wire;
        },wire->{render();result(wire,true,"For "+memberName(g,to)+" • "+g.name+"\n"+pretty(to));});}));
        body.addView(recipientButton("Attach a file or image",()->{page="files";render();}));
        body.addView(button("Decrypt pasted message",()->{
            String wire=DirectProtocol.clean(message.getText().toString());work(()->{
                Protocol.Group target=DirectProtocol.isPrivate(wire)?packetGroup(DirectProtocol.groupId(wire)):g;
                OpenedText opened=openText(target,wire);target.seen.add(opened.record.id);remember(opened.record);return opened;
            },opened->{selectPacketGroup(opened.record.group);render();showOpenedText(opened);});
        }));body.addView(button("Receive an encrypted file",()->{page="files";render();pickFile("decrypt");}));
    }
    private void ttlSelector(){
        body.addView(label("MESSAGE EXPIRY (TTL)",10,GREEN));long[] values={0,300000,3600000,86400000,604800000,2592000000L};
        String[] labels={"No expiry","5 minutes","1 hour","24 hours","7 days","30 days"};Spinner ttl=new Spinner(this);ttl.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
        int current=0;for(int i=0;i<values.length;i++)if(values[i]==ttlMillis)current=i;ttl.setSelection(current);
        ttl.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){ttlMillis=values[pos];}});body.addView(ttl);
        note("Expiry starts when encrypted. It blocks later opening in Enigma; it cannot erase a copy someone already saved.");
    }
    private static final class OpenedText {
        final String text;final MessageRecord record;
        OpenedText(String text,MessageRecord record){this.text=text;this.record=record;}
    }
    /** Packets name their group, so receiving never depends on which group is selected on screen. */
    private Protocol.Group packetGroup(String id){Protocol.Group g=group(id);if(g==null)throw new SecurityException("This was encrypted for a group that is not on this device. Join that group first, then open it again.");return g;}
    private void selectPacketGroup(String id){Protocol.Group g=group(id);if(g!=null&&g!=selected){selected=g;chooseDefaultRecipient();}}
    private OpenedText openText(Protocol.Group g,String wire)throws Exception{
        wire=DirectProtocol.clean(wire);
        if(DirectProtocol.isPrivate(wire)){
            DirectProtocol.Opened o=DirectProtocol.decrypt(g,wire,vault.publicKey(),vault.privateKey());if(isOwner(g)&&!o.sender.equals(ownId))g.memberStatus.put(o.sender,"Joined");return new OpenedText(o.text,new MessageRecord(o.messageId,g.id,o.sender,o.recipient,"text",wire,"Private text",o.createdAt,o.expiresAt,false));
        }
        Protocol.Opened o=Protocol.decrypt(g,wire);return new OpenedText("Legacy shared-group text\n\n"+o.text,new MessageRecord(o.messageId,g.id,o.sender,ownId,"text",wire.trim(),"Legacy text",System.currentTimeMillis(),0,false));
    }
    private void showOpenedText(OpenedText opened){
        result("Verified sender: "+memberName(group(opened.record.group),opened.record.sender)+"\n"+pretty(opened.record.sender)+"\n"+expiryLabel(opened.record.expiresAt)+"\n\n"+opened.text,false);
        if(opened.record.expiresAt>0){TextView shown=output;ui.postDelayed(()->{if(output==shown){clearResult();if(foreground)Toast.makeText(this,"Message expired",Toast.LENGTH_SHORT).show();}},Math.max(0,opened.record.expiresAt-System.currentTimeMillis()));}
    }
    private String expiryLabel(long expires){return expires==0?"No message expiry":System.currentTimeMillis()>=expires?"Expired":"Expires "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(expires));}
    private File historyDirectory()throws IOException{File directory=new File(getFilesDir(),"packages");if(!directory.exists() && !directory.mkdirs())throw new IOException("Could not prepare encrypted history");return directory;}
    private void cleanupHistory(Vault v)throws Exception{
        HashSet<String> retained=new HashSet<>();for(MessageRecord r:v.history)if(r.kind.equals("file"))retained.add(r.payload);
        File[] files=historyDirectory().listFiles();if(files!=null)for(File f:files)if(!retained.contains(f.getName()))f.delete();
    }
    private void remember(MessageRecord record)throws Exception{
        for(MessageRecord r:vault.history)if(r.id.equals(record.id) && r.group.equals(record.group) && r.kind.equals(record.kind)){persist();return;}
        vault.history.add(record);
        while(vault.history.size()>200)vault.history.remove(0);
        long size=0;for(MessageRecord r:vault.history)if(r.kind.equals("file"))size+=new File(historyDirectory(),r.payload).length();
        while(size>150L*1024*1024 && vault.history.size()>1){MessageRecord r=vault.history.remove(0);if(r.kind.equals("file"))size-=new File(historyDirectory(),r.payload).length();}
        persist();cleanupHistory(vault);
    }
    private MessageRecord rememberFile(Protocol.Group g,File file,FileProtocol.Verified verified,boolean outgoing)throws Exception{
        for(MessageRecord r:vault.history)if(r.id.equals(verified.messageId) && r.group.equals(g.id) && r.kind.equals("file"))return r;
        String name=UUID.randomUUID()+".enigma";File stored=new File(historyDirectory(),name);boolean committed=false;
        try{
            try(InputStream in=new FileInputStream(file);FileOutputStream out=new FileOutputStream(stored)){copy(in,out,FileProtocol.MAX_FILE_BYTES+100000);out.getFD().sync();}
            MessageRecord record=new MessageRecord(verified.messageId,g.id,verified.sender,verified.recipient,"file",name,verified.name,verified.createdAt==0?System.currentTimeMillis():verified.createdAt,verified.expiresAt,outgoing);remember(record);committed=true;return record;
        }finally{if(!committed)stored.delete();}
    }
    private void showHistory(){
        body.addView(label("ALL MESSAGES · TEXT & FILES",16,WHITE));
        if(vault.history.isEmpty())note("Your prepared and received encrypted messages will appear here. Older versions did not keep a message history.");
        List<MessageRecord> records=new ArrayList<>(vault.history);Collections.reverse(records);
        for(MessageRecord r:records){Protocol.Group g=group(r.group);if(g==null)continue;LinearLayout card=card();
            card.addView(label((r.outgoing?"PREPARED • ":"RECEIVED • ")+g.name,10,GREEN));card.addView(label(r.kind.equals("file")?r.name:"Private text",17,WHITE));
            card.addView(label((r.outgoing?"To "+memberName(g,r.recipient):"From "+memberName(g,r.sender))+" · "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(r.createdAt))+"\n"+expiryLabel(r.expiresAt),12,MUTED));
            boolean expired=System.currentTimeMillis()>=g.expires || (r.expiresAt>0 && System.currentTimeMillis()>=r.expiresAt);
            if(!expired)card.addView(button(r.kind.equals("file")?"Open encrypted file":"Open message",()->openHistory(r)));
            if(r.outgoing && !expired)card.addView(button("Share encrypted packet",()->shareHistory(r)));
            body.addView(card);
        }
        note("Only encrypted content is retained. Latest 200 items / 150 MB of file packages. Prepared means encrypted here; Enigma cannot confirm delivery by another messenger.");
    }
    private void openHistory(MessageRecord r){Protocol.Group g=group(r.group);if(g==null)return;
        if(r.kind.equals("file"))work(()->FileProtocol.inspect(g,new File(historyDirectory(),r.payload),vault.publicKey(),vault.privateKey()),v->{closeVerified();verifiedFile=v;encryptedFile=new File(historyDirectory(),r.payload);fileGroupId=g.id;fileRecipientId=v.recipient;page="files";render();});
        else work(()->{Protocol.Group view=new Protocol.Group(g.id,g.name,g.seed,g.expires,g.owner);view.members.putAll(g.members);view.memberNames.putAll(g.memberNames);view.roster=g.roster;view.rosterRevision=g.rosterRevision;view.blocked.addAll(g.blocked);view.seen.addAll(g.seen);view.seen.remove(r.id);OpenedText opened=openText(view,r.payload);if(!opened.record.id.equals(r.id))throw new SecurityException("History packet mismatch");return opened;},this::showOpenedText);
    }
    private void shareHistory(MessageRecord r){Protocol.Group g=group(r.group);try{
        checkHistoryPacket(r);
        if(r.kind.equals("text")){Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,r.payload);startActivity(Intent.createChooser(share,"Send encrypted text to "+memberName(g,r.recipient)));}
        else work(()->{checkHistoryPacket(r);File cached=cacheFile();boolean done=false;try{try(InputStream in=new FileInputStream(new File(historyDirectory(),r.payload));OutputStream out=new FileOutputStream(cached)){copy(in,out,FileProtocol.MAX_FILE_BYTES+100000);}checkHistoryPacket(r);done=true;return cached;}finally{if(!done)cached.delete();}},cached->{checkHistoryPacket(r);shareEncryptedFile(cached,g,r.recipient);});
    }catch(Exception e){error(e.getMessage());}}
    private void checkHistoryPacket(MessageRecord r)throws Exception{Protocol.Group g=group(r.group);if(g==null || System.currentTimeMillis()>=g.expires || !g.members.containsKey(r.recipient) || !g.members.containsKey(r.sender) || (r.expiresAt>0 && System.currentTimeMillis()>=r.expiresAt))throw new SecurityException("This packet is expired or its member is no longer trusted");}
    private void result(String value,boolean encrypted) {
        result(value,encrypted,null);
    }
    private void result(String value,boolean encrypted,String recipient) {
        if(!foreground)return;
        clearResult();LinearLayout card=card();card.addView(label(encrypted?"ENCRYPTED • READY TO SHARE":"DECRYPTED • SIGNATURE VERIFIED",10,GREEN));
        if(recipient!=null)card.addView(label(recipient,13,WHITE));
        output=label(encrypted?"Your encrypted message is ready. Use Share or Copy to deliver the complete packet.":value,15,WHITE);card.addView(output);
        if(encrypted){MessageRecord record=null;for(MessageRecord r:vault.history)if(r.kind.equals("text") && r.payload.equals(value))record=r;MessageRecord packet=record;
            card.addView(primary("Share encrypted message",()->{if(packet!=null)shareHistory(packet);}));card.addView(button("Copy encrypted message",()->{try{if(packet==null)throw new SecurityException("Prepare the message again");checkHistoryPacket(packet);ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("Enigma encrypted message",value));Toast.makeText(this,"Encrypted message copied",Toast.LENGTH_SHORT).show();}catch(Exception e){error(e.getMessage());}}));}body.addView(card);
    }
    private void files() {
        title("Message attachment");body.addView(button("Back to messages",()->{page="messages";render();}));note("Encrypt images, PDFs, video and other files up to 50 MB. Text and files are saved together in Messages.");
        if(selected==null){body.addView(primary("Create or join a group",()->{page="groups";render();}));return;}
        selectors();ttlSelector();LinearLayout card=card();card.addView(label("ANY FILE. ONE PRIVATE PACKAGE.",10,GREEN));card.addView(label("Images / documents / video / archives",17,WHITE));card.addView(label("Choose a recipient → encrypt a file → share its .enigma package. Only encrypted packages are shared.",14,MUTED));body.addView(card);
        body.addView(recipientButton("Choose file to encrypt",()->pickFile("encrypt")));body.addView(button("Open .enigma file to decrypt",()->pickFile("decrypt")));
        note("Decryption verifies the sender, every encrypted chunk and the full package before offering Save restored file. Saving creates a readable file in the location you choose.");
        if(encryptedFile!=null && encryptedFile.exists() && verifiedFile==null)fileResult(false);
        else if(verifiedFile!=null)fileResult(true);
    }
    private void pickFile(String action){try{if("encrypt".equals(action))requireRecipient();if(selected==null)throw new SecurityException("Choose a group first");closeVerified();encryptedFile=null;clearResult();fileGroupId=selected.id;fileRecipientId=recipientId;fileTtlMillis=ttlMillis;fileAction=action;beginHandoff();openFile.launch(new String[]{"*/*"});}catch(Exception e){error(e.getMessage());}}
    private File cacheFile()throws IOException {File directory=new File(getCacheDir(),"encrypted");if(!directory.exists() && !directory.mkdirs())throw new IOException("Could not prepare file storage");return File.createTempFile("enigma-",".enigma",directory);}
    private void cleanupCache(){File directory=new File(getCacheDir(),"encrypted");File[] files=directory.listFiles();if(files!=null)for(File file:files)if(file.lastModified()<System.currentTimeMillis()-86400000L)file.delete();}
    private void pickedFile(Uri uri) {
        Protocol.Group g=group(fileGroupId);if(g==null){error("Choose a group and file again.");return;}String action=fileAction,to=fileRecipientId;
        if("encrypt".equals(action))work(()->{
            if(to==null || to.equals(ownId))throw new SecurityException("Choose another verified group recipient first");DirectProtocol.recipient(g,to);
            String name="file",mime=getContentResolver().getType(uri);
            try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                if(cursor!=null && cursor.moveToFirst()){int n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=cursor.getColumnIndex(OpenableColumns.SIZE);if(n>=0)name=cursor.getString(n);if(s>=0 && !cursor.isNull(s) && cursor.getLong(s)>FileProtocol.MAX_FILE_BYTES)throw new IOException("Files must be 50 MB or smaller");}
            }
            File file=cacheFile();boolean done=false;
            try{try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){
                if(in==null)throw new IOException("Could not open that file");FileProtocol.encrypt(g,to,in,out,name,mime,vault.publicKey(),vault.privateKey(),fileTtlMillis);
            }
            try(FileProtocol.Verified verified=FileProtocol.inspect(g,file,vault.publicKey(),vault.privateKey())){rememberFile(g,file,verified,true);preparedExpiresAt=verified.expiresAt;done=true;return file;}
            }finally{if(!done)file.delete();}
        },file->{closeVerified();encryptedFile=file;render();});
        else if("decrypt".equals(action))work(()->{
            File file=cacheFile();boolean done=false;
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){
                if(in==null)throw new IOException("Could not open that file");copy(in,out,FileProtocol.MAX_FILE_BYTES+100000);
            }catch(Exception e){file.delete();throw e;}
            FileProtocol.Verified verified=null;
            try{Protocol.Group target=packetGroup(FileProtocol.groupId(file));verified=FileProtocol.inspect(target,file,vault.publicKey(),vault.privateKey());rememberFile(target,file,verified,false);done=true;return new Object[]{file,verified,target.id};}finally{if(!done){if(verified!=null)verified.close();file.delete();}}
        },pair->{closeVerified();encryptedFile=(File)pair[0];verifiedFile=(FileProtocol.Verified)pair[1];fileGroupId=(String)pair[2];selectPacketGroup(fileGroupId);render();});
    }
    private void fileResult(boolean decrypted) {
        if(!foreground)return;clearResult();LinearLayout card=card();card.addView(label(decrypted?(verifiedFile.previouslyOpened?"FILE VERIFIED • PREVIOUSLY OPENED":"FILE VERIFIED • READY TO RESTORE"):"FILE ENCRYPTED • READY TO SHARE",10,GREEN));
        Protocol.Group preparedGroup=group(fileGroupId);
        if(preparedGroup==null){closeVerified();encryptedFile=null;return;}
        output=label(decrypted?verifiedFile.name+"\n"+formatSize(verifiedFile.size)+" • from "+memberName(preparedGroup,verifiedFile.sender)+"\n"+expiryLabel(verifiedFile.expiresAt):"Private .enigma package\nFor "+memberName(preparedGroup,fileRecipientId)+" • "+preparedGroup.name+"\n"+pretty(fileRecipientId)+"\n"+expiryLabel(preparedExpiresAt),16,WHITE);card.addView(output);
        card.addView(primary(decrypted?"Save restored file":"Save encrypted file",()->{
            Runnable save=()->{fileAction=decrypted?"restore":"save-encrypted";Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(decrypted?verifiedFile.mime:"application/octet-stream").putExtra(Intent.EXTRA_TITLE,decrypted?verifiedFile.name:encryptedFile.getName());beginHandoff();saveFile.launch(intent);};if(decrypted)reauth("Unlock to export readable file",save);else save.run();
        }));
        if(!decrypted)card.addView(button("Share encrypted file",()->{try{checkPreparedFile();shareEncryptedFile(encryptedFile,preparedGroup,fileRecipientId);}catch(Exception e){error(e.getMessage());}}));body.addView(card);
    }
    private void shareEncryptedFile(File file,Protocol.Group g,String recipient){Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);Intent share=new Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newRawUri("Enigma encrypted file",uri));startActivity(Intent.createChooser(share,"Send encrypted file to "+memberName(g,recipient)));}
    private void checkPreparedFile()throws Exception{Protocol.Group g=group(fileGroupId);if(g==null || System.currentTimeMillis()>=g.expires || fileRecipientId==null || fileRecipientId.equals(ownId) || !g.members.containsKey(fileRecipientId) || (preparedExpiresAt>0 && System.currentTimeMillis()>=preparedExpiresAt))throw new SecurityException("This file is expired or its recipient is unavailable");DirectProtocol.recipient(g,fileRecipientId);}
    private void guide(){
        ScrollView scroll=new ScrollView(this);LinearLayout steps=column();steps.setPadding(dp(20),dp(10),dp(20),dp(10));scroll.addView(steps);
        String[][] sections={
            {"1. Protect and set up","Set an app passphrase (12+ characters). Tap Show passphrase to check it. Name your device, then complete five seconds of camera setup."},
            {"2. Create or join a group","Owner: Groups → Create a group → open it → Invite new recipient. Recipient: Groups → Join group. Choose the same joining method on both phones. USB cable is preferred; Personal hotspot is the alternative. QR is a fallback."},
            {"3. USB joining","Open USB joining on both phones and connect a USB data cable. Let one phone control USB and allow Android's access prompts on both. The owner taps Invite after checking the person's device. The recipient checks the owner and taps Join group. Details and all public keys transfer together; confirmation returns automatically. No USB debugging is required. If USB accessory mode is unavailable, use Personal hotspot."},
            {"4. Hotspot joining","First enable the owner's personal hotspot in Android Settings. Connect the recipient phone to it using the hotspot password, even if Android says No Internet. Return to Enigma and choose Personal hotspot on both. Owner: show the address and temporary connection code and tap Wait for recipient. Recipient: enter that address and code and tap Connect to owner. Owner approves, recipient confirms. The temporary connection code is separate from your hotspot password and app passphrase. Keep both apps open; joining expires after five minutes."},
            {"5. QR fallback","QR joining remains available under QR fallback. Keep each code still until Code scanned appears. Tap Next code on the showing phone, then Scan next code on the scanning phone. After the request, tap Swap phones, scan the owner's invitation and confirm joining. More contains Copy/Paste and Start again."},
            {"6. Send text or a file","Home → choose a group → choose a recipient → Write a message or Send a file or image. Select a TTL or leave No expiry. Share the encrypted result with that person in your messenger."},
            {"7. Read received messages","Messages → Open received text to paste a packet, or Receive an encrypted file. Local text and file history appears here. Saving a readable file requires your app passphrase; that saved copy is outside Enigma."},
            {"8. Unlock and reset","Device → Security & unlock enables fingerprint unlock or changes the passphrase. Backgrounding locks Enigma. Device → Reset app & delete local data requires the passphrase and typed RESET; it deletes local keys/groups/history. There is no recovery."}
        };
        for(String[] section:sections){steps.addView(label(section[0],17,GREEN));steps.addView(label(section[1],14,WHITE));}
        transientDialog=new AlertDialog.Builder(this).setTitle("How to use Enigma").setView(scroll).setPositiveButton("Got it",null).show();
    }
    private void confirmReset(){
        if(busy)return;
        EditText confirm=editor("Type RESET to delete local app data",false);limit(confirm,5);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Reset Enigma permanently?")
            .setMessage("Delete this device's private keys, groups, encrypted message/file history, setup and cache. Old encrypted content may become unreadable. Files exported or shared outside Enigma remain elsewhere. You will need to set up and pair again.\n\nType RESET to continue.")
            .setView(confirm).setNegativeButton("Keep my data",null).setPositiveButton("Delete local app data",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!confirm.getText().toString().equals("RESET")){confirm.setError("Type RESET exactly");return;}
            dialog.dismiss();if(message!=null)message.setText("");clearResult();
            try{ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);if(manager==null || !manager.clearApplicationUserData())error("Android could not reset the app. Use Settings → Apps → Enigma → Storage → Clear data.");}catch(Exception e){error(e.getMessage());}
        }));transientDialog=dialog;dialog.show();
    }
    private void exportFile(Uri destination) {
        if(encryptedFile==null || ("restore".equals(fileAction) && verifiedFile==null)){error("File workflow interrupted. Choose the encrypted file again.");return;}
        boolean restore="restore".equals(fileAction);File source=encryptedFile;FileProtocol.Verified verified=verifiedFile;Protocol.Group g=group(fileGroupId);
        work(()->{
            final int exportSession=session;try(OutputStream raw=getContentResolver().openOutputStream(destination,"wt")){
                OutputStream out=raw==null?null:new FilterOutputStream(raw){private void check(){if(sessionLocked||session!=exportSession)throw new SecurityException("Export cancelled because Enigma locked");}@Override public void write(byte[] b,int off,int len)throws IOException{check();out.write(b,off,len);}@Override public void write(int value)throws IOException{check();out.write(value);}};
                if(out==null)throw new IOException("Could not save to that location");
                if(restore){if(g==null || System.currentTimeMillis()>=g.expires || !g.members.containsKey(verified.sender) || !g.members.containsKey(verified.recipient))throw new SecurityException("Group or trusted member unavailable");g.seen.add(verified.messageId);persist();FileProtocol.restore(verified,out);}
                else{checkPreparedFile();try(InputStream in=new FileInputStream(source)){copy(in,out,FileProtocol.MAX_FILE_BYTES+100000);}}return true;
            }catch(Exception e){try{DocumentsContract.deleteDocument(getContentResolver(),destination);}catch(Exception ignored){}if(restore)ui.post(()->{verifiedFile=null;encryptedFile=null;if(!isDestroyed())render();});throw e;}
            finally{if(restore)verified.close();}
        },ok->{if(restore){verifiedFile=null;encryptedFile=null;if(source.getParentFile().equals(new File(getCacheDir(),"encrypted")))source.delete();}render();Toast.makeText(this,restore?"Readable file saved to your chosen location":"Encrypted file saved",Toast.LENGTH_LONG).show();});
    }
    private static void copy(InputStream in,OutputStream out,long max)throws IOException {byte[] buffer=new byte[65536];long total=0;int count;while((count=in.read(buffer))!=-1){if(count==0)throw new IOException("File provider stopped responding");total+=count;if(total>max)throw new IOException("File exceeds the size limit");out.write(buffer,0,count);}out.flush();}
    private void closeVerified(){if(verifiedFile!=null){verifiedFile.close();verifiedFile=null;}}
    private String formatSize(long bytes){return bytes>=1048576?String.format(Locale.ROOT,"%.1f MB",bytes/1048576.0):String.format(Locale.ROOT,"%.1f KB",bytes/1024.0);}
    private void clearResult(){if(output!=null && output.getParent() instanceof View)body.removeView((View)output.getParent());output=null;}
    private void qrDialog(String title,String packet,String caption)throws Exception {QrTransferDialog dialog=transferDialog(packet,null);if(!packet.startsWith("QKG2:")&&!packet.startsWith("QKA1:"))dialog.information(title,caption);transientDialog=dialog;dialog.show();dialog.getWindow().setLayout(-1,-1);}
    private QrTransferDialog transferDialog(String packet,Runnable action)throws Exception {return new QrTransferDialog(this,packet,action,()->pasteTransfer("join",null),()->startJoining(true));}
    private void showTransfer(String packet,Runnable action)throws Exception {
        QrTransferDialog dialog=transferDialog(packet,action);
        transientDialog=dialog;dialog.show();dialog.getWindow().setLayout(-1,-1);
    }
    static Bitmap qrBitmap(String packet)throws Exception {
        return qrBitmap(packet,960);
    }
    static Bitmap qrBitmap(String packet,int side)throws Exception {
        // Encode at the measured display size so modules remain whole pixels.
        BitMatrix matrix=QrImage.encode(packet,side);int width=matrix.getWidth();int[] pixels=new int[width*width];for(int y=0;y<width;y++)for(int x=0;x<width;x++)pixels[y*width+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;return Bitmap.createBitmap(pixels,width,width,Bitmap.Config.ARGB_8888);
    }
    private EditText passwordEditor(String hint){EditText edit=editor(hint,false);edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);edit.setTypeface(android.graphics.Typeface.DEFAULT);limit(edit,128);return edit;}
    private View passwordField(EditText edit){
        LinearLayout field=column();field.addView(edit);CheckBox reveal=new CheckBox(this);reveal.setText("Show "+edit.getHint().toString().toLowerCase(Locale.ROOT));reveal.setTextColor(MUTED);reveal.setOnCheckedChangeListener((v,shown)->{int cursor=edit.getSelectionStart();edit.setTransformationMethod(shown?null:android.text.method.PasswordTransformationMethod.getInstance());edit.setSelection(Math.max(0,Math.min(cursor,edit.length())));});field.addView(reveal);return field;
    }
    private void lockScreen(){
        if(isDestroyed())return;body.removeAllViews();nav.removeAllViews();recipientActions.clear();output=null;message=null;progress.setVisibility(View.GONE);
        boolean create=!passwordLock.configured();title(create?"Protect Enigma":"Unlock Enigma");note(create?"Use at least 12 characters. There is no password recovery.":"Enter your app passphrase to unlock groups and history.");
        EditText password=passwordEditor(create?"New passphrase":"App passphrase");body.addView(passwordField(password));EditText confirm=create?passwordEditor("Repeat passphrase"):null;if(confirm!=null)body.addView(passwordField(confirm));
        body.addView(primary(create?"Protect my app":"Unlock",()->{
            char[] chars=password.getText().toString().toCharArray();if(create && (chars.length<12 || !password.getText().toString().equals(confirm.getText().toString()))){Arrays.fill(chars,'\0');password.setError("Use 12–128 characters and matching passphrases");return;}
            long wait=getPreferences(MODE_PRIVATE).getLong("retryAfter",0)-System.currentTimeMillis();if(wait>0){Arrays.fill(chars,'\0');error("Try again in "+((wait+999)/1000)+" seconds");return;}password.setText("");if(confirm!=null)confirm.setText("");
            unlockWork(()->{byte[] key=null;try{key=create?passwordLock.create(chars):passwordLock.unlock(chars);Vault opened=new Vault(this,key);cleanupCache();cleanupHistory(opened);opened.save();getPreferences(MODE_PRIVATE).edit().remove("failures").remove("retryAfter").apply();return opened;}catch(Exception e){if(!create){int n=Math.min(10,getPreferences(MODE_PRIVATE).getInt("failures",0)+1);getPreferences(MODE_PRIVATE).edit().putInt("failures",n).putLong("retryAfter",System.currentTimeMillis()+Math.min(60000,1000L*(1L<<n))).apply();}throw new SecurityException(create?"Could not protect the vault. Your existing data was retained.":"Could not unlock. Check your passphrase; damaged data or missing device keys also prevent unlocking.",e);}finally{Arrays.fill(chars,'\0');if(key!=null)Arrays.fill(key,(byte)0);}},this::openedVault);
        }));
        if(!create && passwordLock.biometricConfigured())body.addView(button("Unlock with biometrics",()->biometric(false)));
        note("Forgotten passphrase? There is no bypass. Android Settings can clear this app's data to start over; that permanently loses local keys and data.");
    }
    private void openedVault(Vault opened)throws Exception{
        if(!foreground){opened.close();lockScreen();return;}vault=opened;sessionLocked=false;ownId=Protocol.fingerprint(opened.publicKey());broken=false;
        if(!opened.groups.isEmpty())selected=opened.groups.get(0);if(group(restoredGroup)!=null)selected=group(restoredGroup);chooseDefaultRecipient();render();
        if(waitingQr!=null){String qr=waitingQr;waitingQr=null;scanned(qr);}
    }
    private void lockNow(){
        stopNearby();
        session++;sessionLocked=true;handoff=false;biometricAttempt++;if(biometricPrompt!=null){biometricPrompt.cancelAuthentication();biometricPrompt=null;}if(vault==null){if(transientDialog!=null)transientDialog.dismiss();setEnabled(root,true);lockScreen();return;}Vault previous=vault;restoredGroup=selected==null?null:selected.id;vault=null;selected=null;recipientId=null;
        if(transientDialog!=null)transientDialog.dismiss();transientDialog=null;if(message!=null)message.setText("");clearResult();FileProtocol.Verified old=verifiedFile;verifiedFile=null;encryptedFile=null;
        worker.execute(()->{if(old!=null)old.close();previous.close();});if(!isDestroyed()){setEnabled(root,true);lockScreen();}
    }
    private void beginHandoff(){beginHandoff(120000);}
    private void beginHandoff(long duration){handoff=true;handoffUntil=SystemClock.elapsedRealtime()+duration;int generation=session;ui.postDelayed(()->{if(handoff && generation==session && SystemClock.elapsedRealtime()>=handoffUntil)lockNow();},duration+100);}
    private void returnedFromHandoff(){if(handoff && SystemClock.elapsedRealtime()>handoffUntil)lockNow();handoff=false;}
    private void reauth(String title,Runnable after){
        if(vault==null)return;long delay=getPreferences(MODE_PRIVATE).getLong("retryAfter",0)-System.currentTimeMillis();if(delay>0){error("Try again in "+((delay+999)/1000)+" seconds");return;}EditText pass=passwordEditor("App passphrase");int generation=session;
        transientDialog=new AlertDialog.Builder(this).setTitle(title).setView(passwordField(pass)).setNegativeButton("Cancel",null).setPositiveButton("Unlock",(d,w)->{char[] chars=pass.getText().toString().toCharArray();pass.setText("");work(()->{byte[] key=null;try{key=passwordLock.unlock(chars);getPreferences(MODE_PRIVATE).edit().remove("failures").remove("retryAfter").apply();return true;}catch(Exception e){int n=Math.min(10,getPreferences(MODE_PRIVATE).getInt("failures",0)+1);getPreferences(MODE_PRIVATE).edit().putInt("failures",n).putLong("retryAfter",System.currentTimeMillis()+Math.min(60000,1000L*(1L<<n))).apply();throw new SecurityException("Incorrect passphrase or unavailable device key");}finally{Arrays.fill(chars,'\0');if(key!=null)Arrays.fill(key,(byte)0);}},ok->{if(generation==session && !sessionLocked)after.run();});}).show();
    }
    private void changePassword(){reauth("Confirm current passphrase",()->{
        LinearLayout view=column();EditText next=passwordEditor("New passphrase"),again=passwordEditor("Repeat new passphrase");view.addView(passwordField(next));view.addView(passwordField(again));
        transientDialog=new AlertDialog.Builder(this).setTitle("Change app passphrase").setView(view).setNegativeButton("Cancel",null).setPositiveButton("Change",(d,w)->{char[] chars=next.getText().toString().toCharArray();boolean same=next.getText().toString().equals(again.getText().toString());next.setText("");again.setText("");if(chars.length<12 || !same){Arrays.fill(chars,'\0');error("Use matching passphrases of 12–128 characters");return;}work(()->{byte[] key=vault.copyMaster();try{passwordLock.replace(chars,key);return true;}finally{Arrays.fill(chars,'\0');Arrays.fill(key,(byte)0);}},ok->{render();Toast.makeText(this,"Passphrase changed; biometric unlock disabled",Toast.LENGTH_LONG).show();});}).show();
    });}
    private void biometric(boolean enable){
        if(busy)return;
        int available=BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        if(available!=BiometricManager.BIOMETRIC_SUCCESS){error(available==BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED?"Add a fingerprint in Android Settings first, then try again.":"Strong biometrics are unavailable right now. Use your app passphrase.");return;}
        final int generation=session,attempt=++biometricAttempt;
        try{
            javax.crypto.Cipher cipher=enable?passwordLock.enableBiometricCipher():passwordLock.biometricUnlockCipher();
            biometricPrompt=new BiometricPrompt(this,ContextCompat.getMainExecutor(this),new BiometricPrompt.AuthenticationCallback(){
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){
                    if(generation!=session||attempt!=biometricAttempt||result.getCryptoObject()==null)return;biometricPrompt=null;
                    runWork(()->{byte[] key=enable?vault.copyMaster():passwordLock.finishBiometricUnlock(result.getCryptoObject().getCipher());try{if(enable){passwordLock.finishBiometricEnable(result.getCryptoObject().getCipher(),key);return null;}Vault opened=new Vault(MainActivity.this,key);cleanupCache();cleanupHistory(opened);opened.save();return opened;}finally{Arrays.fill(key,(byte)0);}},v->{if(enable){render();Toast.makeText(MainActivity.this,"Biometric unlock enabled",Toast.LENGTH_LONG).show();}else openedVault(v);},!enable);
                }
                @Override public void onAuthenticationError(int code,CharSequence reason){if(attempt!=biometricAttempt)return;biometricPrompt=null;if(code!=BiometricPrompt.ERROR_USER_CANCELED&&code!=BiometricPrompt.ERROR_CANCELED&&code!=BiometricPrompt.ERROR_NEGATIVE_BUTTON)error(reason.toString());}
            });
            BiometricPrompt.PromptInfo info=new BiometricPrompt.PromptInfo.Builder().setTitle(enable?"Enable biometric unlock":"Unlock Enigma").setSubtitle("Use your enrolled fingerprint").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("Use passphrase").build();
            biometricPrompt.authenticate(info,new BiometricPrompt.CryptoObject(cipher));
        }catch(Exception e){error("Biometric key unavailable. Unlock with your passphrase, then disable and enable biometric unlock again.");}
    }
    private void persist()throws Exception {if(sessionLocked||vault==null)throw new SecurityException("Enigma locked; action cancelled");try{vault.save();}catch(Exception e){broken=true;throw new SecurityException("Could not save securely. Close and reopen the app before continuing.",e);}}
    private <T> void work(Job<T> job,Success<T> success){runWork(job,success,false);}
    private <T> void unlockWork(Job<T> job,Success<T> success){runWork(job,success,true);}
    private <T> void runWork(Job<T> job,Success<T> success,boolean unlocking) {
        if(sessionLocked && !unlocking){error("Unlock Enigma first");return;}
        if(busy || broken){if(broken)error("Close and reopen the app to reload its secure vault.");return;}
        final int generation=session;busy=true;setEnabled(root,false);progress.setText("Working…");progress.setVisibility(View.VISIBLE);
        worker.execute(()->{try{T result=job.run();ui.post(()->{busy=false;if(isDestroyed()||generation!=session){discardResult(result);return;}setEnabled(root,true);refreshSendActions();progress.setVisibility(View.GONE);try{success.run(result);}catch(Exception e){error(e.getMessage());}});}catch(Exception e){ui.post(()->{busy=false;if(isDestroyed()||generation!=session)return;setEnabled(root,true);refreshSendActions();progress.setText("Action needs attention");error(e instanceof EOFException?"Incomplete encrypted message or file":e.getMessage());});}});
    }
    private void discardResult(Object result){if(result instanceof Protocol.Group)Arrays.fill(((Protocol.Group)result).seed,(byte)0);else if(result instanceof Object[])for(Object item:(Object[])result)discardResult(item);else if(result instanceof AutoCloseable)try{((AutoCloseable)result).close();}catch(Exception ignored){}else if(result instanceof File)((File)result).delete();}
    private void setEnabled(View view,boolean enabled){view.setEnabled(enabled);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)setEnabled(group.getChildAt(i),enabled);}}
    private void error(String text){if(!isDestroyed())transientDialog=new AlertDialog.Builder(this).setTitle("Action could not be completed").setMessage(text==null?"Invalid or unsupported encrypted data":text).setPositiveButton("OK",null).show();}
    private interface NameAction{void run(String value);}
    private void editName(String title,String initial,int max,NameAction action){EditText name=editor(title,false);name.setText(initial);limit(name,max);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(name).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("Enter a name");return;}dialog.dismiss();action.run(value);}));transientDialog=dialog;dialog.show();}
    private void limit(EditText field,int max){field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView label(String text,int size,int color){TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);view.setPadding(0,dp(5),0,dp(5));return view;}
    private void title(String text){TextView view=label(text,27,WHITE);view.setTypeface(null,Typeface.BOLD);body.addView(view);}
    private void note(String text){body.addView(label(text,14,MUTED));}
    private void stat(LinearLayout parent,String name,String value){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.addView(label(name,13,MUTED),new LinearLayout.LayoutParams(0,-2,1));TextView right=label(value,13,WHITE);right.setGravity(Gravity.END);row.addView(right,new LinearLayout.LayoutParams(0,-2,1));parent.addView(row);}
    private GradientDrawable shape(int color,int radius,int border){GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(radius));if(border!=0)drawable.setStroke(dp(1),border);return drawable;}
    private LinearLayout card(){LinearLayout view=column();view.setPadding(dp(16),dp(14),dp(16),dp(14));view.setBackground(shape(CARD,18,LINE));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(12),0,dp(8));view.setLayoutParams(p);return view;}
    private Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setTextColor(GREEN);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(dp(50));b.setMinimumHeight(dp(50));b.setBackground(shape(CARD,12,LINE));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(4));b.setLayoutParams(p);b.setOnClickListener(v->action.run());return b;}
    private Button primary(String text,Runnable action){Button b=button(text,action);b.setTextColor(BG);b.setTypeface(null,Typeface.BOLD);b.setBackground(shape(GREEN,12,0));return b;}
    private EditText editor(String hint,boolean multiline){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(MUTED);e.setTextColor(WHITE);e.setTextSize(16);e.setPadding(dp(14),dp(14),dp(14),dp(14));e.setBackground(shape(CARD,12,LINE));e.setGravity(Gravity.TOP);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));e.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);return e;}
    private String pretty(String value){StringBuilder b=new StringBuilder();for(int i=0;i<value.length();i+=8){if(i>0)b.append(' ');b.append(value,i,Math.min(i+8,value.length()));}return b.toString();}
    private void badge(String text,boolean ready){ShieldView shield=new ShieldView(this,ready);body.addView(shield,new LinearLayout.LayoutParams(-1,dp(170)));TextView status=label(text,11,GREEN);status.setGravity(Gravity.CENTER);body.addView(status);}
    private final class ShieldView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final boolean ready;
        ShieldView(Context context,boolean ready){super(context);this.ready=ready;setContentDescription(ready?"Encryption ready shield":"Device setup shield");}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float x=getWidth()/2f,y=getHeight()/2f,r=dp(69);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(LINE);canvas.drawCircle(x,y,r,paint);canvas.drawCircle(x,y,r-dp(9),paint);paint.setStrokeWidth(dp(3));paint.setColor(ready?GREEN:MUTED);canvas.drawArc(x-r,y-r,x+r,y+r,-88,ready?340:210,false,paint);Path path=new Path();path.moveTo(x,y-dp(43));path.lineTo(x+dp(34),y-dp(29));path.lineTo(x+dp(30),y+dp(9));path.quadTo(x+dp(24),y+dp(33),x,y+dp(47));path.quadTo(x-dp(24),y+dp(33),x-dp(30),y+dp(9));path.lineTo(x-dp(34),y-dp(29));path.close();paint.setStyle(Paint.Style.FILL);paint.setColor(0xff12372f);canvas.drawPath(path,paint);paint.setStyle(Paint.Style.STROKE);paint.setColor(GREEN);paint.setStrokeWidth(dp(2));canvas.drawPath(path,paint);canvas.drawArc(x-dp(10),y-dp(20),x+dp(10),y+dp(2),180,180,false,paint);paint.setStyle(Paint.Style.FILL);canvas.drawRoundRect(x-dp(15),y-dp(5),x+dp(15),y+dp(19),dp(4),dp(4),paint);paint.setColor(BG);canvas.drawCircle(x,y+dp(5),dp(3),paint);canvas.drawRect(x-dp(1),y+dp(5),x+dp(1),y+dp(12),paint);}
    }
    @Override protected void onResume(){super.onResume();foreground=true;if(handoff && (SystemClock.elapsedRealtime()>handoffUntil || ((android.app.KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()))lockNow();if(vault==null)lockScreen();}
    @Override protected void onPause(){foreground=false;super.onPause();if(message!=null)message.setText("");clearResult();if(!handoff)lockNow();}
    @Override protected void onDestroy(){stopNearby();unregisterReceiver(screenLock);Vault previous=vault;vault=null;if(previous!=null)worker.execute(previous::close);FileProtocol.Verified pending=verifiedFile;verifiedFile=null;if(pending!=null)worker.execute(pending::close);worker.shutdown();super.onDestroy();}
}
