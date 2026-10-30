package app.quietkey;

import android.app.AlertDialog;
import android.app.ActivityManager;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.*;
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
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
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
    private PinLock pinLock;
    /** First-factor secret (from passphrase or biometrics): awaiting the PIN, and kept while unlocked for security changes. */
    private byte[] pendingSecret,unlockSecret;
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
    private String chatGroup,chatMember,sendDraft="",sendName,viewTitle,viewText,viewMime,incomingText;
    private Uri sendUri,incomingUri;
    private Bitmap viewImage;
    private MessageRecord viewRecord;
    private String page="messages",ownId,pendingName,capturePurpose,manualAction,manualGroupId,pendingJoinExport,fileGroupId,fileRecipientId,fileAction;
    private boolean busy,foreground;
    private long ttlMillis,fileTtlMillis,preparedExpiresAt;
    private final ArrayList<View> recipientActions=new ArrayList<>();
    private byte[] waitingDigest;
    private Runnable afterPermission;
    private File encryptedFile;
    /** Readable content of the message on screen; memory only, wiped when the viewer closes. */
    private byte[] viewBytes;
    private boolean viewOnce;
    private final LinkedHashSet<String> recipients=new LinkedHashSet<>();
    /** Key-generation randomness: OS + camera digests + motion sensors while unlocked (decision 10). */
    private final Entropy entropy=new Entropy();
    private SensorManager sensors;
    private SensorEventListener motion;
    private ActivityResultLauncher<Intent> capture,saveFile,exportJoin;
    private ActivityResultLauncher<String[]> openFile,importJoin,permissions;
    private ActivityResultLauncher<ScanOptions> qrScanner;
    private interface Job<T>{T run()throws Exception;}
    private interface Success<T>{void run(T result)throws Exception;}

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);passwordLock=new PasswordLock(this);pinLock=new PinLock(this);registerReceiver(screenLock,new IntentFilter(Intent.ACTION_SCREEN_OFF));getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(saved!=null) {
            pendingName=saved.getString("pendingName");capturePurpose=saved.getString("capturePurpose");
            manualAction=saved.getString("manualAction");manualGroupId=saved.getString("manualGroupId");
            fileAction=saved.getString("fileAction");fileGroupId=saved.getString("fileGroupId");fileRecipientId=saved.getString("fileRecipientId");
            page=saved.getString("page","messages");chatGroup=saved.getString("chatGroup");chatMember=saved.getString("chatMember");restoredGroup=saved.getString("selectedGroupId");
            ttlMillis=saved.getLong("ttlMillis",0);fileTtlMillis=saved.getLong("fileTtlMillis",0);
        }
        capture=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            returnedFromHandoff();
            if(result.getResultCode()!=RESULT_OK || result.getData()==null)return;
            byte[] digest=result.getData().getByteArrayExtra("digest");
            returnedFromHandoff();if(vault==null){if(digest!=null)Arrays.fill(digest,(byte)0);error("Unlock and start camera capture again.");return;}captured(digest);
        });
        qrScanner=registerForActivityResult(new ScanContract(),result->{returnedFromHandoff();String code=result.getContents();if(code!=null&&vault!=null&&nearbyJoin!=null&&!nearbyJoin.closed()&&nearbyJoin.recipient())nearbyJoin.bootstrap(code);});
        permissions=registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),granted->{returnedFromHandoff();Runnable next=afterPermission;afterPermission=null;if(vault==null||next==null)return;
            if(!granted.isEmpty()&&!granted.containsValue(false))next.run();else error(Build.VERSION.SDK_INT>=33?"Enigma needs Nearby devices permission to run its joining hotspot. Use Same Wi-Fi or Manual transfer instead.":"Android requires Location permission (and Location turned on) for the joining hotspot. Use Same Wi-Fi or Manual transfer instead.");});
        importJoin=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{returnedFromHandoff();if(uri!=null&&vault!=null)importedJoinFile(uri);});
        exportJoin=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{returnedFromHandoff();if(result.getResultCode()==RESULT_OK&&result.getData()!=null&&result.getData().getData()!=null&&vault!=null)writeJoinExport(result.getData().getData());else pendingJoinExport=null;});
        openFile=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{
            returnedFromHandoff();if(uri!=null){if(vault==null)error("Please choose the file again after initialization.");else pickedFile(uri);}
        });
        saveFile=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            returnedFromHandoff();if(result.getResultCode()==RESULT_OK && result.getData()!=null && result.getData().getData()!=null){if(vault==null){try{DocumentsContract.deleteDocument(getContentResolver(),result.getData().getData());}catch(Exception ignored){}error("Unlock and choose the encrypted file again.");}else exportFile(result.getData().getData());}
        });
        shell();lockScreen();if(saved==null)takeIntent(getIntent());
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);out.putString("pendingName",pendingName);out.putString("capturePurpose",capturePurpose);
        out.putString("manualAction",manualAction);out.putString("manualGroupId",manualGroupId);out.putString("page",page);
        out.putString("fileAction",fileAction);out.putString("fileGroupId",fileGroupId);out.putString("fileRecipientId",fileRecipientId);
        out.putLong("ttlMillis",ttlMillis);out.putLong("fileTtlMillis",fileTtlMillis);out.putString("chatGroup",chatGroup);out.putString("chatMember",chatMember);if(selected!=null)out.putString("selectedGroupId",selected.id);
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
        body.removeAllViews();message=null;output=null;nav.removeAllViews();recipientActions.clear();if(!"viewer".equals(page))closeViewer();
        progress.setText("Working…");progress.setVisibility(busy?View.VISIBLE:View.GONE);
        if(!pinLock.configured()){pinSetup();return;}
        if(vault.legacy){upgrade();return;}
        if(!vault.setupCompleted||vault.identity==null){setup();return;}
        String[] tabs={"messages","groups","identity"},names={"Messages","Groups","Device"};
        for(int i=0;i<tabs.length;i++){
            String tab=tabs[i];Button b=button(names[i],()->{page=tab;render();});b.setTextSize(12);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);
            boolean active=tab.equals(page) || (tab.equals("messages") && (page.equals("chat") || page.equals("send") || page.equals("receive") || page.equals("viewer"))) || (tab.equals("groups") && (page.equals("members") || page.equals("group-details"))) || (tab.equals("identity") && page.equals("security"));
            b.setTextColor(active?BG:MUTED);b.setBackground(shape(active?GREEN:CARD,12,0));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.setMargins(dp(2),0,dp(2),0);nav.addView(b,p);
        }
        switch(page){case "chat":chat();break;case "send":send();break;case "receive":receive();break;case "viewer":viewer();break;case "groups":groups();break;case "members":memberList();break;case "group-details":groupDetails();break;case "security":securitySettings();break;case "identity":identity();break;default:page="messages";messages();}
        scroll.post(()->scroll.scrollTo(0,0));
    }
    private void setup() {
        title("Set up your device");note("Name your device, then move its camera for five seconds. No video is saved.");
        EditText name=editor("Device name, e.g. Chris's phone",false);name.setText(vault.deviceName.equals("This device")?"":vault.deviceName);limit(name,40);body.addView(name);
        body.addView(primary("Set up with live camera",()->{
            String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("Name this device");return;}
            pendingName=value;capturePurpose="setup";beginHandoff();capture.launch(new Intent(this,CameraActivity.class).putExtra("purpose","setup"));
        }));
        if(!vault.groups.isEmpty()&&vault.identity!=null){
            note("Your existing identity and "+vault.groups.size()+" group(s) will be kept.");
            body.addView(button("Continue with existing groups",()->{
                String value=name.getText().toString().trim();work(()->{vault.deviceName=value.isEmpty()?"This device":value;vault.setupCompleted=true;persist();return true;},ok->{page="messages";render();});
            }));
        }

    }
    private void captured(byte[] digest) {
        String name=pendingName;boolean setup="setup".equals(capturePurpose);
        if(name==null || digest==null){error("Capture interrupted. Start the setup or group capture again.");return;}
        work(()->{
            try {
                entropy.addCamera(digest);
                if(vault.groups.size()>=30)throw new IllegalArgumentException("Maximum 30 groups. Remove an unused group before creating another.");
                if(vault.identity==null)vault.identity=Identity.generate(entropy);
                Protocol.Group group=JoinProtocol.create(setup?"Starter group":name,setup?name:vault.deviceName,vault.identity,vault.pools,entropy);vault.groups.add(group);
                if(setup){vault.deviceName=name;vault.personalGroupId=group.id;vault.setupCompleted=true;vault.setupCapturedAt=System.currentTimeMillis();}
                persist();return group;
            }finally{Arrays.fill(digest,(byte)0);}
        },group->{ownId=vault.identity.fingerprint();selected=group;chooseDefaultRecipient();page=setup?"messages":"groups";capturePurpose=null;render();});
    }
    /** Older vaults (0.6.0 RSA, early 0.7.0 without ML-KEM/ML-DSA) hold keys this version cannot use. Nothing is deleted until the owner confirms. */
    private void upgrade(){
        title("New keys needed");
        note("Enigma "+BuildConfig.VERSION_NAME+" uses new encryption keys with forward secrecy. Groups and messages from earlier versions can't be opened by it. They are still stored, unchanged, on this phone.");
        note("Start fresh to create new keys. Everyone then joins your groups again in person.");
        body.addView(primary("Start fresh",()->reauth("Confirm start fresh",()->transientDialog=new AlertDialog.Builder(this).setTitle("Delete old groups and messages?")
            .setMessage("Your old groups, member keys and message history are deleted from this phone. Your passphrase stays the same. This cannot be undone.")
            .setNegativeButton("Keep for now",null).setPositiveButton("Start fresh",(d,w)->work(()->{vault.startFresh();persist();cleanupHistory(vault);return true;},ok->{ownId=null;selected=null;chooseDefaultRecipient();render();})).show())));
        note("Keep for now: reinstall the earlier Enigma version to read old messages.");
    }
    private void identity() {
        title("About me & my device");note(vault.deviceName+" · Enigma "+BuildConfig.VERSION_NAME);
        note(vault.setupCapturedAt>0?"Device setup complete":"Device setup pending");
        body.addView(primary("How to use Enigma",this::guide));
        body.addView(button("Security & unlock",()->{page="security";render();}));
        body.addView(button("My fingerprint",()->transientDialog=new AlertDialog.Builder(this).setTitle("My device fingerprint").setMessage(pretty(ownId)+"\n\nJoining shows check codes; compare this only if someone asks for it.").setPositiveButton("OK",null).show()));
        body.addView(button("Rename this device",()->editName("Device name",vault.deviceName,40,value->work(()->{vault.deviceName=value;for(Protocol.Group g:vault.groups)g.memberNames.put(ownId,value);persist();return true;},ok->render()))));
        if(vault.personalGroupId==null)body.addView(button("Create starter group with camera",()->{pendingName=vault.deviceName;capturePurpose="setup";beginHandoff();capture.launch(new Intent(this,CameraActivity.class).putExtra("purpose","setup"));}));
        body.addView(button("Reset app & delete local data",()->reauth("Confirm reset",this::confirmReset)));
    }
    private void securitySettings(){
        title("Security & unlock");body.addView(button("Back to device",()->{page="identity";render();}));
        body.addView(primary("Lock Enigma now",this::lockNow));body.addView(button("Change app passphrase",this::changePassword));body.addView(button("Change PIN or panic PIN",this::changePin));
        body.addView(button(passwordLock.biometricConfigured()?"Disable biometric unlock":"Enable biometric unlock",()->reauth("Confirm security change",()->{if(passwordLock.biometricConfigured())work(()->{passwordLock.disableBiometric();return true;},ok->render());else biometric(true);})));
        note("Unlocking takes two steps: your passphrase or fingerprint, then your PIN. After "+PinBox.MAX_ATTEMPTS+" wrong PINs, or on the panic PIN, Enigma erases its keys and data. Your passphrase is still required to change security settings, reset or export readable files.");
        try{if(PinBox.DEFAULT_PANIC.equals(pinLock.state().panic))note("Your panic PIN is still "+PinBox.DEFAULT_PANIC+". Anyone who knows the default can erase Enigma; change it.");}catch(Exception ignored){}
    }
    private void groups() {
        title("Groups");body.addView(row(button("Create a group",this::newGroup),button("Join group",this::startJoining)));
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
        body.addView(button("Send a message",()->openSend(g,null)));
        body.addView(button("Rename this group",()->editName("Group name",g.name,60,value->work(()->{g.name=value;persist();return true;},ok->render()))));
        body.addView(button("Remove group from this device",()->deleteGroup(g)));
    }
    private void newGroup() {editName("Create a group","",60,value->{pendingName=value;capturePurpose="group";beginHandoff();capture.launch(new Intent(this,CameraActivity.class).putExtra("purpose","group"));});}
    private void deleteGroup(Protocol.Group g) {
        transientDialog=new AlertDialog.Builder(this).setTitle("Remove "+g.name+"?").setMessage("This removes its secret, trusted members and replay history from this device. Other members keep their keys. Rejoining resets replay protection.")
            .setNegativeButton("Keep group",null).setPositiveButton("Remove",(d,w)->work(()->{JoiningFlow.cancel(g.id,null,vault.pendingInvites,vault.invitationCache);vault.groups.remove(g);vault.history.removeIf(r->r.group.equals(g.id));if(g.id.equals(vault.personalGroupId))vault.personalGroupId=null;pruneKeys();persist();cleanupHistory(vault);Arrays.fill(g.seed,(byte)0);return true;},ok->{if(selected==g)selected=vault.groups.isEmpty()?null:vault.groups.get(0);closeVerified();encryptedFile=null;chooseDefaultRecipient();render();})).show();
    }
    private boolean isOwner(Protocol.Group g){return ownId!=null && g.owner.equals(ownId);}
    private Protocol.Group group(String id){if(vault!=null)for(Protocol.Group g:vault.groups)if(g.id.equals(id))return g;return null;}
    private void chooseDefaultRecipient(){recipients.clear();}
    private boolean hasRecipient(){if(selected==null||recipients.isEmpty())return false;for(String id:recipients)if(id.equals(ownId)||SealedProtocol.member(selected,id)==null)return false;return true;}
    private void requireRecipient()throws Exception{if(!hasRecipient())throw new SecurityException("Choose at least one other member first");}
    private Button recipientButton(String text,Runnable action){return recipientButton(text,action,true);}
    private Button recipientButton(String text,Runnable action,boolean main){Runnable checked=()->{try{requireRecipient();action.run();}catch(Exception e){error(e.getMessage());}};Button b=main?primary(text,checked):button(text,checked);recipientActions.add(b);b.setEnabled(hasRecipient() && !busy);b.setAlpha(b.isEnabled()?1f:.4f);return b;}
    private void refreshSendActions(){for(View b:recipientActions){b.setEnabled(hasRecipient() && !busy);b.setAlpha(b.isEnabled()?1f:.4f);}}
    private String memberName(Protocol.Group g,String id){if(id.equals(ownId))return "My device";String name=g.memberNames.get(id);return name==null?"Member "+id.substring(0,Math.min(8,id.length())):name;}
    /** Joining: nearby (single QR or NFC tap → Wi-Fi) or manual files. Message encryption is untouched by any of it. */
    private void inviteInstructions(Protocol.Group g){joiningMethods(g);}
    private void startJoining(){joiningMethods(null);}
    private void joiningMethods(Protocol.Group g){
        if(vault==null)return;if(g!=null&&!isOwner(g)){error("Only the group owner can invite");return;}
        ArrayList<String[]> options=new ArrayList<>();ArrayList<Runnable> actions=new ArrayList<>();
        if(g!=null){
            options.add(new String[]{"Nearby: scan or tap","They scan the QR code this phone shows, or hold their phone to the back of this one. Uses a private hotspot."});actions.add(()->withJoinPermission(()->startNearby(g,false)));
            options.add(new String[]{"Nearby on the same Wi-Fi","Use when both phones are already on the same private Wi-Fi."});actions.add(()->startNearby(g,true));
            options.add(new String[]{"By file: open their request","Manual, e.g. over USB. They save a request file and copy it to this phone."});actions.add(()->manualImport("invite",g));
            options.add(new String[]{"By file: open their confirmation","Last manual step, after they have joined."});actions.add(()->manualImport("receipt",g));
        }else{
            options.add(new String[]{"Nearby: scan or tap","The owner chooses Invite new recipient → Nearby: scan or tap and shows a QR code. Scan it, or hold the phones back to back."});actions.add(()->startNearby(null,false));
            options.add(new String[]{"By file: save my request","Manual, e.g. over USB. Copy the file to the owner's phone."});actions.add(this::manualRequest);
            options.add(new String[]{"By file: open the owner's invitation","The file the owner sent back."});actions.add(()->manualImport("join",null));
        }
        choices(g==null?"Join a group":"Invite to "+g.name,options,actions);
    }
    /** App-styled buttons instead of a plain list, so every option looks tappable. */
    private void choices(String heading,List<String[]> options,List<Runnable> actions){
        ScrollView scroll=new ScrollView(this);LinearLayout list=column();list.setPadding(dp(20),dp(4),dp(20),dp(4));scroll.addView(list);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(heading).setView(scroll).setNegativeButton("Cancel",null).create();
        for(int i=0;i<options.size();i++){Runnable action=actions.get(i);Runnable chosen=()->{dialog.dismiss();action.run();};
            list.addView(i==0?primary(options.get(i)[0],chosen):button(options.get(i)[0],chosen));list.addView(label(options.get(i)[1],12,MUTED));}
        transientDialog=dialog;dialog.show();
    }
    private String[] joinPermissions(){return Build.VERSION.SDK_INT>=33?new String[]{"android.permission.NEARBY_WIFI_DEVICES"}:new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION};}
    /** Android gates the local-only hotspot (and on some versions Wi-Fi network requests) on this permission. */
    private void withJoinPermission(Runnable action){
        boolean granted=true;for(String p:joinPermissions())if(ContextCompat.checkSelfPermission(this,p)!=android.content.pm.PackageManager.PERMISSION_GRANTED)granted=false;
        if(granted){action.run();return;}afterPermission=action;beginHandoff();permissions.launch(joinPermissions());
    }
    String ownFingerprint(){return ownId;}
    private void startNearby(Protocol.Group g,boolean sameWifi){
        if(vault==null||busy)return;stopNearby();
        if(g!=null){openNearby(g,sameWifi,null);return;}
        work(()->{vault.joiningRequest=JoiningFlow.request(vault.identity,vault.pools,entropy,vault.deviceName,vault.joiningRequest,vault.pairingUsed,true);persist();return vault.joiningRequest;},request->openNearby(null,false,request));
    }
    private void openNearby(Protocol.Group g,boolean sameWifi,String request){
        final NearbyJoinDialog[] holder=new NearbyJoinDialog[1];
        NearbyJoinDialog.Callbacks callbacks=new NearbyJoinDialog.Callbacks(){
            private <T> CompletableFuture<T> pending(){CompletableFuture<T> future=new CompletableFuture<>();ui.post(()->{if(!active())future.completeExceptionally(new SecurityException("Joining closed"));else nearbyApprovals.add(future);});return future;}
            private boolean active(){return nearbyJoin==holder[0]&&!holder[0].closed()&&vault!=null&&!sessionLocked&&foreground;}
            public CompletableFuture<String> invite(String packet,String check){CompletableFuture<String> result=pending();ui.post(()->{if(!active()){result.completeExceptionally(new SecurityException("Unlock and retry"));return;}
                work(()->{try{if(g==null||!isOwner(g))throw new SecurityException("Only the owner can invite");return JoinProtocol.parseRequest(packet);}catch(Exception e){result.completeExceptionally(e);throw e;}},r->{
                    if(!active()||result.isDone())return;
                    nearbyConsent=transientDialog=new AlertDialog.Builder(MainActivity.this).setTitle("Invite "+r.name+"?").setMessage("Check code\n"+check+"\n\nThe other phone must show exactly this code. If it differs, tap Decline: someone else connected.\n\nAdd this device to "+g.name+".\n\nDevice fingerprint\n"+pretty(r.key.fingerprint()))
                    .setNegativeButton("Decline",(d,w)->{result.completeExceptionally(new SecurityException("Owner declined"));holder[0].failure("Invitation declined.");})
                    .setOnCancelListener(d->result.completeExceptionally(new SecurityException("Owner cancelled")))
                    .setPositiveButton("Codes match · Invite",(d,w)->{if(!active()){result.completeExceptionally(new SecurityException("Joining closed"));return;}work(()->{try{String grant=JoiningFlow.invite(g,packet,vault.identity,vault.pools,entropy,vault.pairingUsed,vault.pendingInvites,vault.invitationCache);persist();return grant;}catch(Exception e){result.completeExceptionally(e);throw e;}},grant->{selected=g;result.complete(grant);});}).show();
                });});return result;
            }
            public CompletableFuture<String> accept(String invitation,String check,JoinBootstrap pinned){CompletableFuture<String> result=pending();ui.post(()->{if(!active()){result.completeExceptionally(new SecurityException("Unlock and retry"));return;}
                work(()->{try{if(vault.joiningRequest.isEmpty()||!vault.joiningRequest.equals(request))throw new SecurityException("Joining session changed");JoinProtocol.Request r=JoinProtocol.parseRequest(request);if(vault.pairingUsed.contains(r.id)||vault.pairingUsed.size()>=10000)throw new SecurityException("Joining request unavailable");
                    Protocol.Group candidate=JoinProtocol.accept(invitation,request,vault.identity,vault.pools);
                    try{pinned.requireOwner(candidate.owner);}catch(SecurityException e){Arrays.fill(candidate.seed,(byte)0);throw e;}
                    if(group(candidate.id)!=null||vault.groups.size()>=30){Arrays.fill(candidate.seed,(byte)0);throw new SecurityException("Group already exists or group limit reached; history was preserved");}return candidate;
                }catch(Exception e){result.completeExceptionally(e);throw e;}},candidate->{if(!active()||result.isDone()){Arrays.fill(candidate.seed,(byte)0);return;}nearbyCandidate=candidate;
                    nearbyConsent=transientDialog=new AlertDialog.Builder(MainActivity.this).setTitle("Join "+candidate.name+"?").setMessage("Check code\n"+check+"\n\nThe owner's phone must show this same code. The invitation is signed by the phone whose code you scanned.\n\n"+candidate.members.size()+" member public keys will be added together.\n\nOwner fingerprint\n"+pretty(candidate.owner))
                    .setNegativeButton("Decline",(d,w)->{Arrays.fill(candidate.seed,(byte)0);nearbyCandidate=null;result.completeExceptionally(new SecurityException("Recipient declined"));holder[0].failure("Joining declined. No group was saved on this phone.");})
                    .setOnCancelListener(d->{Arrays.fill(candidate.seed,(byte)0);nearbyCandidate=null;result.completeExceptionally(new SecurityException("Recipient cancelled"));})
                    .setPositiveButton("Join group",(d,w)->{if(!active()){result.completeExceptionally(new SecurityException("Joining closed"));return;}work(()->{try{return commitJoin(candidate,request);}catch(Exception e){result.completeExceptionally(e);throw e;}},receipt->{nearbyCandidate=null;selected=candidate;chooseDefaultRecipient();page="groups";holder[0].markCommitted();result.complete(receipt);});}).show();
                });});return result;
            }
            public CompletableFuture<Void> receipt(String packet){CompletableFuture<Void> result=pending();ui.post(()->{if(!active()){result.completeExceptionally(new SecurityException("Unlock and retry"));return;}work(()->{try{if(g==null)throw new SecurityException("No owner group");JoinProtocol.receiptMember(g,packet,vault.pendingInvites,vault.pools);vault.invitationCache.entrySet().removeIf(e->!vault.pendingInvites.containsKey(e.getKey()));persist();return true;}catch(Exception e){result.completeExceptionally(e);throw e;}},ok->result.complete(null));});return result;}
            public void scanQr(){beginHandoff(JoinProtocol.WINDOW);qrScanner.launch(new ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the owner's joining code").setBeepEnabled(false).setOrientationLocked(true).setCaptureActivity(SecureCaptureActivity.class));}
            public void needPermission(Runnable retry){withJoinPermission(retry);}
            public void handoff(boolean start){if(start)beginHandoff(90000);else if(handoff)returnedFromHandoff();}
            public void finished(){if(vault!=null){page=g==null?"groups":"group-details";render();}}
            public void stopped(){if(nearbyJoin==holder[0])stopNearby();}
            public void transportStopped(){if(nearbyJoin==holder[0])cancelNearbyApprovals();}
        };
        holder[0]=new NearbyJoinDialog(this,g!=null,sameWifi,request,callbacks);nearbyJoin=holder[0];nearbyJoin.show();
    }
    /** Commits a verified, confirmed invitation and returns the signed receipt. Runs on the worker. */
    private String commitJoin(Protocol.Group candidate,String request)throws Exception{
        JoinProtocol.Request r=JoinProtocol.parseRequest(request);if(!request.equals(vault.joiningRequest)||vault.pairingUsed.contains(r.id))throw new SecurityException("Joining session changed. Start again.");
        if(group(candidate.id)!=null||vault.groups.size()>=30)throw new SecurityException("Group cannot be added");String receipt=JoinProtocol.receipt(candidate,r.id,vault.identity,vault.pools,entropy);vault.groups.add(candidate);vault.pairingUsed.add(r.id);vault.joiningRequest="";persist();return receipt;
    }
    private void cancelNearbyApprovals(){for(CompletableFuture<?> approval:nearbyApprovals)approval.completeExceptionally(new SecurityException("Joining closed"));nearbyApprovals.clear();if(nearbyConsent!=null){nearbyConsent.dismiss();nearbyConsent=null;}Protocol.Group candidate=nearbyCandidate;Vault current=vault;nearbyCandidate=null;if(candidate!=null&&!worker.isShutdown())worker.execute(()->{if(current==null||!current.groups.contains(candidate))Arrays.fill(candidate.seed,(byte)0);});}
    private void stopNearby(){NearbyJoinDialog previous=nearbyJoin;nearbyJoin=null;if(previous!=null){previous.close();if(previous.isShowing())previous.dismiss();}cancelNearbyApprovals();}

    // Manual transfer: the same signed request, recipient-encrypted invitation and signed receipt, carried as small text files (USB cable, SD card, any file transfer).
    private void manualRequest(){
        work(()->{vault.joiningRequest=JoiningFlow.request(vault.identity,vault.pools,entropy,vault.deviceName,vault.joiningRequest,vault.pairingUsed,false);persist();return vault.joiningRequest;},
            request->exportJoin("enigma-join-request.txt",request,"Request saved","Copy this file to the owner's phone, for example over a USB cable through a computer. It contains only your public keys and device name.\n\nThe owner opens it with Invite new recipient → By file: open their request, and sends back an invitation file. Then open that here with Join group → By file: open the owner's invitation.\n\nThe request expires five minutes after it was made. Save it again for a fresh one if needed."));
    }
    private void manualImport(String action,Protocol.Group g){manualAction=action;manualGroupId=g==null?null:g.id;beginHandoff();importJoin.launch(new String[]{"text/plain","application/octet-stream","*/*"});}
    private void importedJoinFile(Uri uri){
        String action=manualAction;Protocol.Group g=group(manualGroupId);
        work(()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Could not open that file");ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy(in,bytes,80000);return Packets.clean(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.US_ASCII));}},
            packet->{if("join".equals(action))manualJoin(packet);else if(g!=null&&"invite".equals(action))manualInvite(g,packet);else if(g!=null&&"receipt".equals(action))manualReceipt(g,packet);else error("Joining interrupted. Start again from Groups.");});
    }
    private void manualInvite(Protocol.Group g,String packet){
        if(!packet.startsWith(JoinProtocol.REQUEST)){error("That is not an Enigma joining request file.");return;}
        work(()->new Object[]{JoinProtocol.parseRequest(packet),JoinBootstrap.manualCheckCode(packet,vault.identity.publicKey)},value->{JoinProtocol.Request request=(JoinProtocol.Request)value[0];String check=(String)value[1];
            transientDialog=new AlertDialog.Builder(this).setTitle("Invite "+request.name+"?").setMessage("Check code\n"+check+"\n\nTheir phone shows this code when they open your invitation. Compare it with them in person or on a call before they join.\n\nDevice fingerprint\n"+pretty(request.key.fingerprint())).setNegativeButton("Cancel",null)
                .setPositiveButton("Invite",(d,w)->work(()->{String grant=JoiningFlow.invite(g,packet,vault.identity,vault.pools,entropy,vault.pairingUsed,vault.pendingInvites,vault.invitationCache);persist();return grant;},
                    grant->{selected=g;chooseDefaultRecipient();page="group-details";render();exportJoin("enigma-invitation.txt",grant,"Invitation ready","Copy this file to "+request.name+"'s phone. It is encrypted so only that phone can open it, and signed by this phone.\n\nCheck code: "+check+"\n\nThey stay Invited until their first verified message or their confirmation file.");})).show();});
    }
    private void manualJoin(String packet){
        if(!packet.startsWith(JoinProtocol.INVITATION)){error("That is not an Enigma invitation file.");return;}
        work(()->{if(vault.joiningRequest.isEmpty())throw new SecurityException("Save your request file first: Join group → By file: save my request");String request=vault.joiningRequest;JoinProtocol.Request r=JoinProtocol.parseRequest(request);
            if(vault.pairingUsed.contains(r.id))throw new SecurityException("This request was already used. Save a fresh request file.");Protocol.Group candidate=JoinProtocol.accept(packet,request,vault.identity,vault.pools);
            if(group(candidate.id)!=null||vault.groups.size()>=30){Arrays.fill(candidate.seed,(byte)0);throw new SecurityException("Group already exists or group limit reached; history was preserved");}
            return new Object[]{candidate,request,JoinBootstrap.manualCheckCode(request,Identity.Public.decode(Protocol.un64(candidate.members.get(candidate.owner))))};
        },value->{Protocol.Group candidate=(Protocol.Group)value[0];String request=(String)value[1],check=(String)value[2];
            transientDialog=new AlertDialog.Builder(this).setTitle("Join "+candidate.name+"?").setMessage("Check code\n"+check+"\n\nThe owner saw this code when inviting you. Join only if it matches what they read to you.\n\n"+candidate.members.size()+" member public keys will be added together.\n\nOwner fingerprint\n"+pretty(candidate.owner))
                .setNegativeButton("Decline",(d,w)->Arrays.fill(candidate.seed,(byte)0)).setOnCancelListener(d->Arrays.fill(candidate.seed,(byte)0))
                .setPositiveButton("Codes match · Join",(d,w)->work(()->commitJoin(candidate,request),receipt->{selected=candidate;chooseDefaultRecipient();page="groups";render();
                    transientDialog=new AlertDialog.Builder(this).setTitle("Joined "+candidate.name).setMessage("Group details and member keys are ready. Your first verified message confirms joining to the owner, or save a confirmation file for them now.").setPositiveButton("Done",null).setNeutralButton("Save confirmation file",(x,y)->exportJoin("enigma-joined.txt",receipt,"Confirmation saved","Copy this file to the owner. They open it with Invite new recipient → By file: open their confirmation.")).show();})).show();});
    }
    private void manualReceipt(Protocol.Group g,String packet){
        if(!packet.startsWith(JoinProtocol.RECEIPT)){error("That is not an Enigma confirmation file.");return;}
        work(()->{String member=JoinProtocol.receiptMember(g,packet,vault.pendingInvites,vault.pools);vault.invitationCache.entrySet().removeIf(entry->!vault.pendingInvites.containsKey(entry.getKey()));persist();return member;},id->{render();Toast.makeText(this,memberName(g,id)+" joined",Toast.LENGTH_LONG).show();});
    }
    private void exportJoin(String name,String packet,String title,String explanation){
        transientDialog=new AlertDialog.Builder(this).setTitle(title).setMessage(explanation).setPositiveButton("Save file",(d,w)->{pendingJoinExport=packet;beginHandoff();exportJoin.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,name));})
            .setNeutralButton("Copy text",(d,w)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Enigma joining",packet));Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show();}).setNegativeButton("Close",null).show();
    }
    private void writeJoinExport(Uri uri){
        String packet=pendingJoinExport;pendingJoinExport=null;if(packet==null)return;
        work(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Could not write that file");out.write(packet.getBytes(java.nio.charset.StandardCharsets.US_ASCII));}return true;},ok->Toast.makeText(this,"Saved. Copy it to the other phone.",Toast.LENGTH_LONG).show());
    }
    private void memberList() {
        Protocol.Group g=selected;if(g==null){page="groups";groups();return;}
        title(g.name+" members");body.addView(button("Back to group",()->{page="group-details";render();}));
        for(String id:new ArrayList<>(g.members.keySet())){
            LinearLayout card=card();card.addView(label(memberName(g,id)+(id.equals(g.owner)?" · owner":" · "+g.memberStatus.getOrDefault(id,"Member")),18,WHITE));card.addView(label(pretty(id),12,MUTED));
            if(!id.equals(ownId)){
                card.addView(button("Name this member",()->editName("Member name",g.memberNames.getOrDefault(id,""),40,value->work(()->{g.memberNames.put(id,value);persist();return true;},ok->render()))));
                if(!id.equals(g.owner))card.addView(button("Remove local trust",()->transientDialog=new AlertDialog.Builder(this).setTitle("Remove local trust?").setMessage("This device will stop accepting this member's signatures and offering them as a recipient. Their existing group key is not revoked. Create a new group to replace a shared group secret.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->work(()->{JoiningFlow.cancel(g.id,id,vault.pendingInvites,vault.invitationCache);g.members.remove(id);g.memberNames.remove(id);g.memberStatus.remove(id);g.blocked.add(id);if(isOwner(g))JoinProtocol.signRoster(g,vault.identity,vault.pools,entropy,Collections.emptyMap());pruneKeys();persist();return true;},ok->{chooseDefaultRecipient();render();})).show()));
            }body.addView(card);
        }
    }
    private void selectors() {
        if(selected==null)return;
        body.addView(label("2 · GROUP",10,GREEN));
        Spinner groups=new Spinner(this);List<String> names=new ArrayList<>();for(Protocol.Group g:vault.groups)names.add(g.name);
        groups.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));groups.setSelection(vault.groups.indexOf(selected));
        groups.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(selected!=vault.groups.get(pos)){selected=vault.groups.get(pos);chooseDefaultRecipient();render();}}});body.addView(groups);
        body.addView(label("3 · TO (ONE OR MORE)",10,GREEN));
        recipients.removeIf(id->id.equals(ownId)||!selected.members.containsKey(id));int others=0;
        for(String id:selected.members.keySet()){if(id.equals(ownId))continue;others++;
            CheckBox box=new CheckBox(this);box.setText(memberName(selected,id)+" · "+id.substring(0,8));box.setTextColor(WHITE);box.setTextSize(16);box.setChecked(recipients.contains(id));
            box.setOnCheckedChangeListener((v,on)->{if(on)recipients.add(id);else recipients.remove(id);refreshSendActions();});body.addView(box);}
        if(others==0)note("No one else is in this group yet. Invite someone from Groups first.");
    }
    // ---- Messages: chats like a messenger. Send = message or file → group → person; Receive = open the file (or pasted text) and Enigma finds the group and sender. ----
    private void messages() {
        title("Messages");
        if(vault.groups.isEmpty()){note("Create or join a group to start.");body.addView(primary("Set up a group",()->{page="groups";render();}));return;}
        body.addView(row(primary("Send",()->openSend(null,null)),button("Receive",()->{page="receive";render();})));
        int shown=0;
        for(Inbox.Chat c:Inbox.chats(vault.history)){Protocol.Group g=group(c.group);if(g==null)continue;shown++;MessageRecord last=c.last();
            LinearLayout card=card();card.addView(label(memberName(g,c.member),18,WHITE));card.addView(label((last.outgoing?"You: ":"")+Inbox.label(last),14,MUTED));card.addView(label(g.name+" · "+when(last.createdAt),11,MUTED));
            card.setClickable(true);card.setOnClickListener(v->{chatGroup=c.group;chatMember=c.member;page="chat";render();});body.addView(card);}
        if(shown==0)note("No messages yet. Tap Send to encrypt a message or file for someone in one of your groups. Tap Receive to open one you were sent.");
        else note("Messages are kept encrypted inside Enigma: the latest 200 items / 150 MB.");
    }
    private void chat(){
        Protocol.Group g=group(chatGroup);if(g==null||chatMember==null){page="messages";messages();return;}String who=chatMember;
        body.addView(button("Back to messages",()->{page="messages";render();}));title(memberName(g,who));note(g.name);
        boolean member=g.members.containsKey(who)&&!who.equals(ownId);
        body.addView(row(primary("Send",()->openSend(g,member?who:null)),button("Receive",()->{page="receive";render();})));
        List<MessageRecord> items=Inbox.thread(vault.history,g.id,who);for(MessageRecord r:items)body.addView(bubble(r));
        if(items.isEmpty())note("Nothing here yet.");else note("Tap a message to open it. Sent means encrypted here; Enigma cannot confirm another app delivered it.");
    }
    private View bubble(MessageRecord r){
        LinearLayout b=column();b.setPadding(dp(14),dp(10),dp(14),dp(10));b.setBackground(shape(r.outgoing?0xff12372f:CARD,16,LINE));
        b.addView(label(Inbox.label(r),16,WHITE));b.addView(label((r.outgoing?"Sent ":"Received ")+when(r.createdAt)+(r.expiresAt>0?"\n"+expiryLabel(r.expiresAt):""),11,MUTED));
        if(expired(r))b.setAlpha(.5f);else{b.setClickable(true);b.setOnClickListener(v->openHistory(r));}
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.gravity=r.outgoing?Gravity.END:Gravity.START;p.setMargins(r.outgoing?dp(48):0,dp(6),r.outgoing?0:dp(48),dp(6));b.setLayoutParams(p);b.setMinimumWidth(dp(150));return b;
    }
    private boolean expired(MessageRecord r){Protocol.Group g=group(r.group);long now=System.currentTimeMillis();return g==null||now>=g.expires||(r.expiresAt>0&&now>=r.expiresAt);}
    private String when(long time){return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(time));}
    private void openSend(Protocol.Group g,String to){if(g!=null)selected=g;if(selected==null&&!vault.groups.isEmpty())selected=vault.groups.get(0);recipients.clear();if(to!=null)recipients.add(to);viewOnce=false;sendUri=null;sendName=null;sendDraft="";page="send";render();}
    private void send(){
        body.addView(button("Back to messages",()->{page="messages";render();}));title("Send");
        if(selected==null){body.addView(primary("Set up a group",()->{page="groups";render();}));return;}
        body.addView(label("1 · MESSAGE OR FILE",10,GREEN));
        if(sendUri==null){
            message=editor("Type your message…",true);message.setText(sendDraft);
            message.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence t,int a,int b,int c){}public void onTextChanged(CharSequence t,int a,int b,int c){}public void afterTextChanged(android.text.Editable e){sendDraft=e.toString();}});
            body.addView(message,new LinearLayout.LayoutParams(-1,dp(140)));body.addView(button("Or choose a file or photo",()->pickFile("attach")));
        }else{LinearLayout card=card();card.addView(label("FILE",10,GREEN));card.addView(label(sendName,16,WHITE));card.addView(button("Remove file",()->{sendUri=null;sendName=null;render();}));body.addView(card);}
        selectors();ttlSelector();
        CheckBox once=new CheckBox(this);once.setText("View once: not kept after it is read");once.setTextColor(WHITE);once.setChecked(viewOnce);once.setOnCheckedChangeListener((v,on)->viewOnce=on);body.addView(once);
        body.addView(recipientButton("Encrypt & share",this::encryptAndShare));
        if(sendUri==null)body.addView(recipientButton("Send as text instead",this::sendAsText,false));
        note("Encrypt & share makes a .enigma file only the people you chose can open. Send it with any app (WhatsApp, email) or copy it over USB. Send as text instead gives a block of text to paste into a chat.");
    }
    private void attach(Uri uri){
        work(()->{String name="file";try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                if(cursor!=null && cursor.moveToFirst()){int n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=cursor.getColumnIndex(OpenableColumns.SIZE);if(n>=0&&cursor.getString(n)!=null)name=cursor.getString(n);if(s>=0 && !cursor.isNull(s) && cursor.getLong(s)>SealedProtocol.MAX_CONTENT)throw new IOException("Files must be 50 MB or smaller");}}
            return name;},name->{sendUri=uri;sendName=name;sendDraft="";page="send";render();});
    }
    private void encryptAndShare(){sealAndSend(false);}
    private void sendAsText(){sealAndSend(true);}
    /**
     * One package for every chosen recipient: each gets the content key wrapped under one of their
     * one-time keys (or their backup key when none are left: "reduced protection"). The readable
     * copy is kept encrypted in the vault unless View once is chosen.
     */
    private void sealAndSend(boolean asText){
        Protocol.Group g=selected;List<String> to=new ArrayList<>(recipients);String name=sendName,typed=sendDraft;long ttl=ttlMillis;boolean once=viewOnce;Uri uri=sendUri;
        if(uri==null&&typed.trim().isEmpty()){error("Type a message or choose a file first.");return;}
        work(()->{
            byte[] content;String fileName=Inbox.TEXT_NAME,mime=Inbox.TEXT_MIME;
            if(uri==null)content=typed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            else{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Could not open that file");ByteArrayOutputStream b=new ByteArrayOutputStream();copy(in,b,SealedProtocol.MAX_CONTENT);content=b.toByteArray();}
                fileName=name;mime=getContentResolver().getType(uri);if(mime==null)mime="application/octet-stream";}
            try{
                long now=System.currentTimeMillis();List<SealedProtocol.Recipient> list=new ArrayList<>();List<String> reduced=new ArrayList<>();
                for(String id:to){
                    Identity.Public key=SealedProtocol.member(g,id);if(key==null||id.equals(ownId))throw new SecurityException("Choose members of this group");
                    PreKey backup=vault.pools.theirBackup(id);if(backup==null)throw new SecurityException(memberName(g,id)+"'s keys are missing on this phone. Ask the group owner to send a group update.");
                    PreKey oneTime=vault.pools.take(id);if(oneTime==null)reduced.add(memberName(g,id));
                    list.add(new SealedProtocol.Recipient(key,backup,oneTime,vault.pools.issue(vault.identity,entropy,id,KeyPools.REFILL,now)));
                }
                byte[] packet=SealedProtocol.seal(g,vault.identity,vault.pools.backup(vault.identity,entropy,now),list,new SealedProtocol.Content(fileName,mime,content,ttl,once),JoinProtocol.carrier(g),entropy);
                String id=SealedProtocol.idOf(packet),packetName=id+".enigma";
                try(FileOutputStream out=new FileOutputStream(new File(historyDirectory(),packetName))){out.write(packet);out.getFD().sync();}
                String plain=once?"":storePlain(content);long expires=ttl<=0?0:now+ttl;
                for(String r:to)remember(new MessageRecord(id+"/"+r,g.id,ownId,r,once?"once":"file",plain,fileName,mime,packetName,now,expires,true,reduced.contains(memberName(g,r))));
                Object share;if(asText)share=SealedProtocol.toText(packet);else{File cached=cacheFile();try(FileOutputStream out=new FileOutputStream(cached)){out.write(packet);}share=cached;}
                return new Object[]{share,reduced};
            }finally{Arrays.fill(content,(byte)0);}
        },value->{
            sendUri=null;sendName=null;sendDraft="";viewOnce=false;if(message!=null)message.setText("");chatGroup=g.id;chatMember=to.get(0);page="chat";render();
            @SuppressWarnings("unchecked") List<String> reduced=(List<String>)value[1];
            if(!reduced.isEmpty())Toast.makeText(this,"Reduced protection for "+String.join(", ",reduced)+": no one-time keys left. They refill when that person messages you.",Toast.LENGTH_LONG).show();
            String who=to.size()==1?memberName(g,to.get(0)):to.size()+" people";
            if(value[0] instanceof String)startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,(String)value[0]),"Send encrypted text to "+who));
            else shareEncryptedFile((File)value[0],who);
        });
    }
    private void receive(){
        body.addView(button("Back to messages",()->{page="messages";render();}));title("Receive");
        note("Open the .enigma file you were sent. Enigma finds the group, checks who sent it and opens it here.");
        body.addView(primary("Open .enigma file",()->pickFile("decrypt")));
        note("Quicker: tap the .enigma file in WhatsApp, email or Files and choose Enigma.");
        body.addView(label("GOT A BLOCK OF TEXT INSTEAD?",10,GREEN));
        message=editor("Paste the encrypted text (ENG5:…) here",true);body.addView(message,new LinearLayout.LayoutParams(-1,dp(110)));
        EditText pasted=message;body.addView(button("Open pasted text",()->openPasted(pasted.getText().toString())));
    }
    private void openPasted(String text){
        if(Packets.clean(text).isEmpty()){error("Paste the encrypted text first.");return;}
        work(()->openPacket(SealedProtocol.fromText(text)),value->{if(message!=null)message.setText("");showOpened(value);});
    }
    private void receiveFile(Uri uri){
        work(()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Could not open that file");ByteArrayOutputStream b=new ByteArrayOutputStream();copy(in,b,SealedProtocol.MAX_CONTENT+1024*1024);return openPacket(b.toByteArray());}},this::showOpened);
    }
    /**
     * Opens a received package on the worker. Order matters: verify and decrypt without changing
     * anything; store the readable copy (unless view once) and the record; then delete the used
     * one-time key, store the sender's refill keys and adopt a newer carried roster; persist once.
     */
    private Object[] openPacket(byte[] packet)throws Exception{
        Protocol.Group g=packetGroup(SealedProtocol.groupOf(packet));String id=SealedProtocol.idOf(packet);
        for(MessageRecord r:vault.history)if(r.group.equals(g.id)&&r.id.equals(id)&&!r.outgoing){if(r.kind.equals("once"))throw new SecurityException("This view-once message was already opened.");return openStored(r);}
        if(g.seen.contains(id))throw new SecurityException("This message was already opened on this phone.");
        SealedProtocol.Opened o=SealedProtocol.open(g,vault.identity,vault.pools,packet);
        String plain=o.viewOnce?"":storePlain(o.body);
        MessageRecord record=new MessageRecord(o.messageId,g.id,o.sender,ownId,o.viewOnce?"once":"file",plain,o.name,o.mime,"",o.createdAt,o.expiresAt,false,o.reducedProtection());
        SealedProtocol.accept(g,vault.pools,o);
        JoinProtocol.Roster roster=JoinProtocol.openCarrier(g,o.carrier);if(roster!=null&&JoinProtocol.adopt(g,roster,vault.identity,vault.pools))pruneKeys();
        g.seen.add(o.messageId);if(isOwner(g))g.memberStatus.put(o.sender,"Joined");remember(record);
        return new Object[]{record,o.body,preview(o.mime,o.name,o.body)};
    }
    /** Deletes keys of people who are no longer trusted members of any group here (removed, blocked or group deleted). */
    private void pruneKeys(){vault.pools.forgetAllExcept(KeyPools.activeContacts(vault.groups,ownId));}
    private Object[] openStored(MessageRecord r)throws Exception{byte[] content=readPlain(r.payload);return new Object[]{r,content,preview(r.mime,r.name,content)};}
    /** Readable content for the in-app viewer stays in memory (FLAG_SECURE window). */
    private static Object preview(String mime,String name,byte[] content){
        int kind=Inbox.preview(mime,name,content.length);if(kind==Inbox.TEXT)return new String(content,java.nio.charset.StandardCharsets.UTF_8);
        if(kind!=Inbox.IMAGE)return null;BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(content,0,content.length,bounds);if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
        int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/sample>2048)sample*=2;BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=sample;return BitmapFactory.decodeByteArray(content,0,content.length,o);
    }

    private void showOpened(Object[] value){
        MessageRecord r=(MessageRecord)value[0];closeViewer();viewRecord=r;viewBytes=(byte[])value[1];viewMime=r.mime;viewTitle=Inbox.label(r);
        if(value[2] instanceof Bitmap)viewImage=(Bitmap)value[2];else if(value[2] instanceof String)viewText=(String)value[2];
        openViewer(r);
    }

    private void openViewer(MessageRecord r){
        chatGroup=r.group;chatMember=Inbox.member(r);selectPacketGroup(r.group);page="viewer";render();
        if(r.expiresAt>0)ui.postDelayed(()->{if(viewRecord==r){page="chat";closeViewer();render();if(foreground)Toast.makeText(this,"Message expired",Toast.LENGTH_SHORT).show();}},Math.max(0,r.expiresAt-System.currentTimeMillis()));
    }
    private void closeViewer(){
        viewRecord=null;viewTitle=null;viewText=null;if(viewImage!=null){viewImage.recycle();viewImage=null;}
        closeVerified();encryptedFile=null;
    }
    private void viewer(){
        MessageRecord r=viewRecord;Protocol.Group g=r==null?null:group(r.group);if(g==null){page="messages";messages();return;}String who=Inbox.member(r);
        body.addView(button("Back to chat",()->{chatGroup=g.id;chatMember=who;page="chat";render();}));
        title(viewTitle);note((r.outgoing?"To ":"From ")+memberName(g,who)+" · "+g.name+"\n"+when(r.createdAt)+" · "+expiryLabel(r.expiresAt)+(r.outgoing?"":"\nSender verified"));
        if(r.reduced)note("Reduced protection: "+(r.outgoing?"sent":"received")+" with a backup key because no one-time keys were left.");
        if(r.kind.equals("once"))note("View once: this is not kept. Leaving this screen removes it.");
        if(viewImage!=null){ImageView image=new ImageView(this);image.setImageBitmap(viewImage);image.setAdjustViewBounds(true);image.setContentDescription(viewTitle);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(8));body.addView(image,p);}
        else if(viewText!=null){LinearLayout card=card();card.addView(label(viewText,17,WHITE));body.addView(card);}
        else if(viewBytes!=null){LinearLayout card=card();card.addView(label(r.name,17,WHITE));card.addView(label(formatSize(viewBytes.length)+"\nEnigma shows photos and text here. Save this file to open it with another app.",13,MUTED));body.addView(card);}
        if(viewBytes!=null)body.addView(viewImage==null&&viewText==null?primary("Save to device",this::saveRestored):button("Save to device",this::saveRestored));
        if(r.outgoing&&!expired(r)&&!r.packet.isEmpty()){body.addView(button("Share again",()->shareHistory(r)));body.addView(button("Save .enigma file (for USB)",()->saveEncrypted(r)));}
        if(viewBytes!=null)note("Save to device asks for your passphrase and writes a readable copy outside Enigma.");
    }
    private void saveRestored(){
        byte[] content=viewBytes;MessageRecord r=viewRecord;if(content==null||r==null)return;
        reauth("Unlock to save a readable copy",()->{if(viewBytes!=content)return;fileAction="restore";Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(r.mime.isEmpty()?"application/octet-stream":r.mime).putExtra(Intent.EXTRA_TITLE,Inbox.TEXT_NAME.equals(r.name)?"message.txt":r.name);beginHandoff();saveFile.launch(intent);});
    }
    private void saveEncrypted(MessageRecord r){
        try{checkHistoryPacket(r);encryptedFile=new File(historyDirectory(),r.packet);fileGroupId=r.group;preparedExpiresAt=r.expiresAt;fileAction="save-encrypted";
            beginHandoff();saveFile.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,(Inbox.TEXT_NAME.equals(r.name)?"message":r.name)+".enigma"));}
        catch(Exception e){error(e.getMessage());}
    }
    private void ttlSelector(){
        body.addView(label("EXPIRY",10,GREEN));long[] values={0,300000,3600000,86400000,604800000,2592000000L};
        String[] labels={"No expiry","5 minutes","1 hour","24 hours","7 days","30 days"};Spinner ttl=new Spinner(this);ttl.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
        int current=0;for(int i=0;i<values.length;i++)if(values[i]==ttlMillis)current=i;ttl.setSelection(current);
        ttl.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){ttlMillis=values[pos];}});body.addView(ttl);
        note("Optional. Expiry blocks opening in Enigma after that time; it cannot erase a copy someone already saved.");
    }

    /** Packets name their group, so receiving never depends on which group is selected on screen. */
    private Protocol.Group packetGroup(String id){Protocol.Group g=group(id);if(g==null)throw new SecurityException("This was encrypted for a group that is not on this device. Join that group first, then open it again.");return g;}
    private void selectPacketGroup(String id){Protocol.Group g=group(id);if(g!=null&&g!=selected){selected=g;chooseDefaultRecipient();}}

    private String expiryLabel(long expires){return expires==0?"No message expiry":System.currentTimeMillis()>=expires?"Expired":"Expires "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(expires));}
    /** Outgoing .enigma packages, kept for Share again. */
    private File historyDirectory()throws IOException{File directory=new File(getFilesDir(),"packages");if(!directory.exists() && !directory.mkdirs())throw new IOException("Could not prepare encrypted history");return directory;}
    /** Readable message content, each file encrypted under its own key derived from the vault master key. */
    private File plainDirectory()throws IOException{File directory=new File(getFilesDir(),"history");if(!directory.exists() && !directory.mkdirs())throw new IOException("Could not prepare encrypted history");return directory;}
    private String storePlain(byte[] content)throws Exception{
        String name=UUID.randomUUID()+".plain";byte[] key=vault.historyKey(name),nonce=Protocol.random(12);
        try(FileOutputStream out=new FileOutputStream(new File(plainDirectory(),name))){out.write(nonce);out.write(Protocol.aes(javax.crypto.Cipher.ENCRYPT_MODE,key,nonce,name.getBytes(java.nio.charset.StandardCharsets.UTF_8),content));out.getFD().sync();}
        finally{Arrays.fill(key,(byte)0);}return name;
    }
    private byte[] readPlain(String name)throws Exception{
        File f=new File(plainDirectory(),name);if(f.length()<28||f.length()>SealedProtocol.MAX_CONTENT+64)throw new SecurityException("Stored message is missing or damaged");
        byte[] all=new byte[(int)f.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(f))){in.readFully(all);}
        byte[] key=vault.historyKey(name);try{return Protocol.aes(javax.crypto.Cipher.DECRYPT_MODE,key,Arrays.copyOf(all,12),name.getBytes(java.nio.charset.StandardCharsets.UTF_8),Arrays.copyOfRange(all,12,all.length));}finally{Arrays.fill(key,(byte)0);}
    }
    /** Deletes stored files no record refers to. Never runs on a 0.6.0 vault awaiting Start fresh. */
    private void cleanupHistory(Vault v)throws Exception{
        if(v.legacy)return;HashSet<String> retained=new HashSet<>();for(MessageRecord r:v.history){retained.add(r.payload);retained.add(r.packet);}
        for(File dir:new File[]{historyDirectory(),plainDirectory()}){File[] files=dir.listFiles();if(files!=null)for(File f:files)if(!retained.contains(f.getName()))f.delete();}
    }
    private void remember(MessageRecord record)throws Exception{
        for(MessageRecord r:vault.history)if(r.id.equals(record.id) && r.group.equals(record.group)){persist();return;}
        vault.history.add(record);
        while(vault.history.size()>200)vault.history.remove(0);
        while(storedSize()>150L*1024*1024 && vault.history.size()>1)vault.history.remove(0);
        persist();cleanupHistory(vault);
    }
    private long storedSize()throws IOException{HashSet<String> names=new HashSet<>();long size=0;for(MessageRecord r:vault.history){if(names.add(r.payload)&&!r.payload.isEmpty())size+=new File(plainDirectory(),r.payload).length();if(names.add(r.packet)&&!r.packet.isEmpty())size+=new File(historyDirectory(),r.packet).length();}return size;}

    private void openHistory(MessageRecord r){
        if(r.kind.equals("once")){Toast.makeText(this,"View-once messages are not kept.",Toast.LENGTH_SHORT).show();return;}
        work(()->openStored(r),this::showOpened);
    }
    private void shareHistory(MessageRecord r){Protocol.Group g=group(r.group);try{
        checkHistoryPacket(r);
        work(()->{checkHistoryPacket(r);File cached=cacheFile();boolean done=false;try{try(InputStream in=new FileInputStream(new File(historyDirectory(),r.packet));OutputStream out=new FileOutputStream(cached)){copy(in,out,SealedProtocol.MAX_CONTENT+1024*1024);}done=true;return cached;}finally{if(!done)cached.delete();}},cached->shareEncryptedFile(cached,memberName(g,r.recipient)));
    }catch(Exception e){error(e.getMessage());}}
    private void checkHistoryPacket(MessageRecord r)throws Exception{Protocol.Group g=group(r.group);if(g==null || r.packet.isEmpty() || System.currentTimeMillis()>=g.expires || (r.expiresAt>0 && System.currentTimeMillis()>=r.expiresAt))throw new SecurityException("This message has expired or its group is gone");}
    private void pickFile(String action){fileAction=action;beginHandoff();openFile.launch(new String[]{"*/*"});}
    private File cacheFile()throws IOException {File directory=new File(getCacheDir(),"encrypted");if(!directory.exists() && !directory.mkdirs())throw new IOException("Could not prepare file storage");return File.createTempFile("enigma-",".enigma",directory);}
    private void cleanupCache(){File directory=new File(getCacheDir(),"encrypted");File[] files=directory.listFiles();if(files!=null)for(File file:files)if(file.lastModified()<System.currentTimeMillis()-86400000L)file.delete();}
    private void pickedFile(Uri uri){if("attach".equals(fileAction))attach(uri);else if("decrypt".equals(fileAction))receiveFile(uri);}
    private void shareEncryptedFile(File file,String who){Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);Intent share=new Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newRawUri("Enigma encrypted file",uri));startActivity(Intent.createChooser(share,"Send encrypted file to "+who));}
    private void checkPreparedFile()throws Exception{Protocol.Group g=group(fileGroupId);if(g==null || System.currentTimeMillis()>=g.expires || (preparedExpiresAt>0 && System.currentTimeMillis()>=preparedExpiresAt))throw new SecurityException("This file has expired or its group is gone");}
    private void guide(){
        ScrollView scroll=new ScrollView(this);LinearLayout steps=column();steps.setPadding(dp(20),dp(10),dp(20),dp(10));scroll.addView(steps);
        String[][] sections={
            {"1. Protect and set up","Set an app passphrase (12+ characters). Tap Show passphrase to check it. Name your device, then complete five seconds of camera setup."},
            {"2. Create or join a group","Owner: Groups → Create a group → open it → Invite new recipient. Recipient: Groups → Join group. Joining copies the group's secret and member keys to the new phone, encrypted so only that phone can open it."},
            {"3. Nearby joining (recommended)","Owner: Invite new recipient → Nearby: scan or tap. Enigma starts a private hotspot with no Internet and shows one QR code. Recipient: Join group → Nearby: scan or tap, then tap Scan QR code or hold the phones back to back, and allow the connection if Android asks. Both phones show a six-digit check code: the owner taps Invite only if both codes match, then the recipient taps Join group. Keep both apps open; the code works once and expires after five minutes."},
            {"4. Same Wi-Fi","If both phones are already on the same private Wi-Fi, the owner can choose Nearby on the same Wi-Fi instead. The recipient still uses Nearby: scan or tap."},
            {"5. Manual transfer","For a USB cable or any file copy. Recipient: Join group → By file: save my request, and copy it to the owner. Owner: Invite new recipient → By file: open their request → Invite, then save the invitation file and copy it back. Recipient: Join group → By file: open the owner's invitation. Compare the six-digit check code in person or on a call before tapping Join."},
            {"6. Send","Messages → Send → type a message or choose a file → choose the group → tick one or more people → Encrypt & share. Enigma makes one .enigma file only they can open; send it with any app or over USB. Send as text instead makes a block of text for a chat. Expiry and View once are optional. Each message uses a one-time key that is deleted after reading; Reduced protection means none were left, and they refill as people reply."},
            {"7. Receive","Tap the .enigma file in your messenger and choose Enigma, or Messages → Receive → Open .enigma file. Enigma finds the group, checks the sender and shows photos and text inside the app. A message opens once from its file; after that it is kept encrypted under Messages. Save to device asks for your passphrase; that copy is outside Enigma. Pasted text works too."},
            {"8. Unlock and reset","Unlocking takes your passphrase or fingerprint, then your 4-digit PIN. 5 wrong PINs, or the panic PIN (9999 until you change it), erase Enigma's keys and data at once. Device → Security & unlock enables fingerprint unlock and changes the passphrase, PIN or panic PIN. Backgrounding locks Enigma. Device → Reset app & delete local data requires the passphrase and typed RESET; it deletes local keys/groups/history. There is no recovery."}
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
            dialog.dismiss();if(message!=null)message.setText("");clearResult();PinLock.wipe(this);
            try{ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);if(manager==null || !manager.clearApplicationUserData())error("Android could not reset the app. Use Settings → Apps → Enigma → Storage → Clear data.");}catch(Exception e){error(e.getMessage());}
        }));transientDialog=dialog;dialog.show();
    }
    private void exportFile(Uri destination) {
        boolean restore="restore".equals(fileAction);byte[] content=viewBytes;File source=encryptedFile;
        if(restore?content==null:source==null){error("File workflow interrupted. Open the message again.");return;}
        work(()->{
            final int exportSession=session;try(OutputStream raw=getContentResolver().openOutputStream(destination,"wt")){
                OutputStream out=raw==null?null:new FilterOutputStream(raw){private void check(){if(sessionLocked||session!=exportSession)throw new SecurityException("Export cancelled because Enigma locked");}@Override public void write(byte[] b,int off,int len)throws IOException{check();out.write(b,off,len);}@Override public void write(int value)throws IOException{check();out.write(value);}};
                if(out==null)throw new IOException("Could not save to that location");
                if(restore)out.write(content);else{checkPreparedFile();try(InputStream in=new FileInputStream(source)){copy(in,out,SealedProtocol.MAX_CONTENT+1024*1024);}}out.flush();return true;
            }catch(Exception e){try{DocumentsContract.deleteDocument(getContentResolver(),destination);}catch(Exception ignored){}throw e;}
        },ok->{render();Toast.makeText(this,restore?"Readable file saved to your chosen location":"Encrypted file saved",Toast.LENGTH_LONG).show();});
    }
    private static void copy(InputStream in,OutputStream out,long max)throws IOException {byte[] buffer=new byte[65536];long total=0;int count;while((count=in.read(buffer))!=-1){if(count==0)throw new IOException("File provider stopped responding");total+=count;if(total>max)throw new IOException("File exceeds the size limit");out.write(buffer,0,count);}out.flush();}
    private void closeVerified(){if(viewBytes!=null){Arrays.fill(viewBytes,(byte)0);viewBytes=null;}}
    private String formatSize(long bytes){return bytes>=1048576?String.format(Locale.ROOT,"%.1f MB",bytes/1048576.0):String.format(Locale.ROOT,"%.1f KB",bytes/1024.0);}
    private void clearResult(){if(output!=null && output.getParent() instanceof View)body.removeView((View)output.getParent());output=null;}
    private EditText passwordEditor(String hint){EditText edit=editor(hint,false);edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);edit.setTypeface(android.graphics.Typeface.DEFAULT);limit(edit,128);return edit;}
    private View passwordField(EditText edit){
        LinearLayout field=column();field.addView(edit);CheckBox reveal=new CheckBox(this);reveal.setText("Show "+edit.getHint().toString().toLowerCase(Locale.ROOT));reveal.setTextColor(MUTED);reveal.setOnCheckedChangeListener((v,shown)->{int cursor=edit.getSelectionStart();edit.setTransformationMethod(shown?null:android.text.method.PasswordTransformationMethod.getInstance());edit.setSelection(Math.max(0,Math.min(cursor,edit.length())));});field.addView(reveal);return field;
    }
    private void lockScreen(){
        if(isDestroyed())return;body.removeAllViews();nav.removeAllViews();recipientActions.clear();output=null;message=null;progress.setVisibility(View.GONE);
        if(pendingSecret!=null){pinScreen();return;}
        boolean create=!passwordLock.configured();title(create?"Protect Enigma":"Unlock Enigma");note(create?"Use at least 12 characters. There is no password recovery.":"Enter your app passphrase, or use biometrics. Your PIN is asked next.");
        EditText password=passwordEditor(create?"New passphrase":"App passphrase");body.addView(passwordField(password));EditText confirm=create?passwordEditor("Repeat passphrase"):null;if(confirm!=null)body.addView(passwordField(confirm));
        EditText pin=create?pinEditor("4-digit PIN"):null,pinAgain=create?pinEditor("Repeat PIN"):null,panic=create?pinEditor("Panic PIN"):null;
        if(create){note("Every unlock needs your passphrase or fingerprint and then this PIN. After "+PinBox.MAX_ATTEMPTS+" wrong PINs Enigma erases itself.");body.addView(pin);body.addView(pinAgain);panic.setText(PinBox.DEFAULT_PANIC);note("Panic PIN: entering it instead of your PIN erases Enigma at once. It starts as "+PinBox.DEFAULT_PANIC+"; change it to something only you know.");body.addView(panic);body.addView(keypad(pin,pinAgain,panic));}
        body.addView(primary(create?"Protect my app":"Unlock",()->{
            char[] chars=password.getText().toString().toCharArray();if(create && (chars.length<12 || !password.getText().toString().equals(confirm.getText().toString()))){Arrays.fill(chars,'\0');password.setError("Use 12–128 characters and matching passphrases");return;}
            String pinValue=create?pin.getText().toString():null,panicValue=create?panic.getText().toString():null;
            if(create){String problem=pinProblem(pinValue,pinAgain.getText().toString(),panicValue);if(problem!=null){Arrays.fill(chars,'\0');pin.setError(problem);return;}}
            long wait=getPreferences(MODE_PRIVATE).getLong("retryAfter",0)-System.currentTimeMillis();if(wait>0){Arrays.fill(chars,'\0');error("Try again in "+((wait+999)/1000)+" seconds");return;}password.setText("");if(confirm!=null)confirm.setText("");if(create){pin.setText("");pinAgain.setText("");panic.setText("");}
            unlockWork(()->{byte[] key=null;boolean done=false;try{
                if(create){key=passwordLock.create(chars);byte[] master=Protocol.random(32);try{pinLock.set(key,pinValue,panicValue,master);Vault opened=new Vault(this,master);cleanupCache();cleanupHistory(opened);opened.save();done=true;return new Object[]{opened,key};}finally{Arrays.fill(master,(byte)0);}}
                key=passwordLock.unlock(chars);getPreferences(MODE_PRIVATE).edit().remove("failures").remove("retryAfter").apply();Object result=firstFactor(key);done=true;return result;
            }catch(Exception e){if(!create){int n=Math.min(10,getPreferences(MODE_PRIVATE).getInt("failures",0)+1);getPreferences(MODE_PRIVATE).edit().putInt("failures",n).putLong("retryAfter",System.currentTimeMillis()+Math.min(60000,1000L*(1L<<n))).apply();}throw new SecurityException(create?"Could not protect the vault. Your existing data was retained.":"Could not unlock. Check your passphrase; damaged data or missing device keys also prevent unlocking.",e);}finally{Arrays.fill(chars,'\0');if(!done&&key!=null)Arrays.fill(key,(byte)0);}},this::afterFirstFactor);
        }));
        if(!create && passwordLock.biometricConfigured())body.addView(button("Unlock with biometrics",()->biometric(false)));
        note("Forgotten passphrase? There is no bypass. Android Settings can clear this app's data to start over; that permanently loses local keys and data.");
    }
    private EditText pinEditor(String hint){EditText edit=editor(hint,false);edit.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);edit.setShowSoftInputOnFocus(false);edit.setGravity(Gravity.CENTER);edit.setTextSize(24);limit(edit,4);return edit;}
    /** On-screen number pad for the PIN fields, so the phone keyboard is never needed; types into whichever field has focus. */
    private LinearLayout keypad(EditText... fields){
        LinearLayout pad=column();pad.setPadding(0,dp(8),0,0);String[][] rows={{"1","2","3"},{"4","5","6"},{"7","8","9"},{"clear","0","\u232b"}};
        for(String[] keys:rows){LinearLayout row=new LinearLayout(this);for(String key:keys){
            Button b=button(key,()->{EditText target=fields[0];for(EditText f:fields)if(f.hasFocus())target=f;
                if(key.equals("\u232b")){int n=target.length();if(n>0)target.getText().delete(n-1,n);}else if(key.equals("clear"))target.setText("");else if(target.length()<4)target.append(key);target.setError(null);});
            b.setTextSize(20);b.setContentDescription(key.equals("\u232b")?"Delete":key.equals("clear")?"Clear":"Digit "+key);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(56),1);lp.setMargins(dp(4),dp(4),dp(4),dp(4));b.setLayoutParams(lp);row.addView(b);}
          pad.addView(row);}
        return pad;
    }
    private static String pinProblem(String pin,String again,String panic){
        if(!PinBox.valid(pin))return "Use exactly 4 digits";if(!pin.equals(again))return "PINs do not match";
        if(!PinBox.valid(panic))return "The panic PIN is 4 digits";if(pin.equals(panic))return "Your PIN and panic PIN must differ";return null;
    }
    /**
     * Worker thread, after the passphrase or biometrics released the first-factor secret: returns
     * the secret when a PIN is needed next, or {vault, secret} for an installation that has no PIN
     * yet (earlier versions; the PIN is then set before anything else).
     */
    private Object firstFactor(byte[] secret)throws Exception{
        boolean stale=false;
        if(pinLock.configured()){if(pinLock.state().belongsTo(secret))return secret;stale=true;}
        Vault opened=new Vault(this,secret);if(stale)pinLock.delete(); // PIN setup was interrupted before the passphrase changed
        cleanupCache();cleanupHistory(opened);opened.save();return new Object[]{opened,secret};
    }
    private void afterFirstFactor(Object result)throws Exception{
        if(result instanceof byte[]){wipeSecrets();pendingSecret=(byte[])result;pinScreen();return;}
        Object[] pair=(Object[])result;wipeSecrets();unlockSecret=(byte[])pair[1];openedVault((Vault)pair[0]);
    }
    private void wipeSecrets(){if(pendingSecret!=null)Arrays.fill(pendingSecret,(byte)0);if(unlockSecret!=null)Arrays.fill(unlockSecret,(byte)0);pendingSecret=null;unlockSecret=null;}
    private void pinScreen(){
        if(isDestroyed())return;body.removeAllViews();nav.removeAllViews();
        title("Enter your PIN");int left=PinBox.MAX_ATTEMPTS;try{left=pinLock.state().remaining();}catch(Exception ignored){}
        note(left<PinBox.MAX_ATTEMPTS?left+(left==1?" attempt":" attempts")+" left before Enigma erases itself.":"Second step: your 4-digit PIN.");
        EditText pin=pinEditor("PIN");body.addView(pin);body.addView(keypad(pin));pin.requestFocus();
        body.addView(primary("Unlock",()->{
            String value=pin.getText().toString();pin.setText("");if(!PinBox.valid(value)){pin.setError("Enter your 4-digit PIN");return;}
            byte[] secret=pendingSecret;if(secret==null){lockScreen();return;}byte[] copy=secret.clone();
            unlockWork(()->{byte[] master=null;try{master=pinLock.unlock(copy,value);Vault opened=new Vault(this,master);cleanupCache();cleanupHistory(opened);opened.save();return opened;}
                catch(PinLock.Wiped w){return "wiped";}finally{Arrays.fill(copy,(byte)0);if(master!=null)Arrays.fill(master,(byte)0);}},
                result->{if("wiped".equals(result)){erased();return;}byte[] s=pendingSecret;pendingSecret=null;unlockSecret=s;openedVault((Vault)result);});
        }));
        body.addView(button("Cancel",()->{wipeSecrets();lockScreen();}));
    }
    /** After the panic PIN or too many wrong PINs: keys and files are already gone; Android clears the rest. */
    private void erased(){
        wipeSecrets();vault=null;selected=null;
        try{ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);if(manager!=null&&manager.clearApplicationUserData())return;}catch(Exception ignored){}
        finishAndRemoveTask();
    }
    /** Installations from before the PIN (decision 12): set a PIN before anything else. */
    private void pinSetup(){
        title("Add an unlock PIN");
        note("Enigma now needs two steps to unlock: your passphrase or fingerprint, then a 4-digit PIN. After "+PinBox.MAX_ATTEMPTS+" wrong PINs Enigma erases itself.");
        EditText pass=passwordEditor("App passphrase");body.addView(passwordField(pass));EditText pin=pinEditor("New 4-digit PIN"),again=pinEditor("Repeat PIN"),panic=pinEditor("Panic PIN");
        body.addView(pin);body.addView(again);panic.setText(PinBox.DEFAULT_PANIC);note("Panic PIN: entering it instead of your PIN erases Enigma at once. It starts as "+PinBox.DEFAULT_PANIC+"; change it to something only you know.");body.addView(panic);body.addView(keypad(pin,again,panic));
        body.addView(primary("Save PIN",()->{
            String problem=pinProblem(pin.getText().toString(),again.getText().toString(),panic.getText().toString());if(problem!=null){pin.setError(problem);return;}
            char[] chars=pass.getText().toString().toCharArray();String pinValue=pin.getText().toString(),panicValue=panic.getText().toString();pass.setText("");pin.setText("");again.setText("");
            work(()->{byte[] current=null,next=Protocol.random(32),master=vault.copyMaster();boolean done=false;try{
                current=passwordLock.unlock(chars);if(!java.security.MessageDigest.isEqual(current,unlockSecret))throw new SecurityException("Incorrect passphrase");
                pinLock.set(next,pinValue,panicValue,master);
                try{passwordLock.replace(chars,next);}catch(Exception e){pinLock.delete();throw e;}
                done=true;return next;
            }catch(Exception e){throw new SecurityException("PIN not saved. Check your passphrase.",e);}finally{Arrays.fill(chars,'\0');Arrays.fill(master,(byte)0);if(current!=null)Arrays.fill(current,(byte)0);if(!done)Arrays.fill(next,(byte)0);}},
                next->{if(unlockSecret!=null)Arrays.fill(unlockSecret,(byte)0);unlockSecret=next;render();Toast.makeText(this,"PIN saved. Biometric unlock was turned off; turn it on again in Device → Security & unlock.",Toast.LENGTH_LONG).show();});
        }));
        body.addView(button("Lock Enigma",this::lockNow));
    }
    private void changePin(){reauth("Confirm PIN change",()->{
        LinearLayout view=column();EditText pin=pinEditor("New 4-digit PIN"),again=pinEditor("Repeat PIN"),panic=pinEditor("Panic PIN");view.addView(pin);view.addView(again);view.addView(panic);view.addView(keypad(pin,again,panic));
        try{panic.setText(pinLock.state().panic);}catch(Exception ignored){}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Change PIN or panic PIN").setView(view).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String problem=pinProblem(pin.getText().toString(),again.getText().toString(),panic.getText().toString());if(problem!=null){pin.setError(problem);return;}
            String pinValue=pin.getText().toString(),panicValue=panic.getText().toString();dialog.dismiss();
            work(()->{byte[] master=vault.copyMaster();try{pinLock.set(unlockSecret,pinValue,panicValue,master);return true;}finally{Arrays.fill(master,(byte)0);}},ok->{render();Toast.makeText(this,"PIN saved",Toast.LENGTH_LONG).show();});
        }));transientDialog=dialog;dialog.show();
    });}
    private void openedVault(Vault opened)throws Exception{
        if(!foreground){opened.close();lockScreen();return;}vault=opened;sessionLocked=false;ownId=opened.identity==null?null:opened.identity.fingerprint();broken=false;startMotion();
        if(!opened.groups.isEmpty())selected=opened.groups.get(0);if(group(restoredGroup)!=null)selected=group(restoredGroup);chooseDefaultRecipient();render();if(opened.setupCompleted&&pinLock.configured())handleIncoming();
    }
    private void lockNow(){
        stopNearby();
        session++;sessionLocked=true;handoff=false;biometricAttempt++;wipeSecrets();if(biometricPrompt!=null){biometricPrompt.cancelAuthentication();biometricPrompt=null;}if(vault==null){if(transientDialog!=null)transientDialog.dismiss();setEnabled(root,true);lockScreen();return;}Vault previous=vault;restoredGroup=selected==null?null:selected.id;vault=null;selected=null;recipients.clear();stopMotion();
        if(transientDialog!=null)transientDialog.dismiss();transientDialog=null;if(message!=null)message.setText("");clearResult();sendDraft="";sendUri=null;viewRecord=null;viewTitle=null;viewText=null;if(viewImage!=null){viewImage.recycle();viewImage=null;}if("viewer".equals(page))page="chat";closeVerified();encryptedFile=null;
        worker.execute(previous::close);if(!isDestroyed()){setEnabled(root,true);lockScreen();}
    }
    /** Accelerometer and gyroscope readings feed key generation while unlocked; no permission needed. */
    private void startMotion(){
        if(motion!=null)return;sensors=(SensorManager)getSystemService(SENSOR_SERVICE);if(sensors==null)return;
        motion=new SensorEventListener(){public void onSensorChanged(SensorEvent e){entropy.addMotion(e.sensor.getType(),e.timestamp,e.values);}public void onAccuracyChanged(Sensor s,int accuracy){}};
        for(int type:new int[]{Sensor.TYPE_ACCELEROMETER,Sensor.TYPE_GYROSCOPE}){Sensor s=sensors.getDefaultSensor(type);if(s!=null)sensors.registerListener(motion,s,SensorManager.SENSOR_DELAY_UI);}
    }
    private void stopMotion(){if(sensors!=null&&motion!=null)sensors.unregisterListener(motion);motion=null;}
    private void beginHandoff(){beginHandoff(120000);}
    private void beginHandoff(long duration){handoff=true;handoffUntil=SystemClock.elapsedRealtime()+duration;int generation=session;ui.postDelayed(()->{if(handoff && generation==session && SystemClock.elapsedRealtime()>=handoffUntil)lockNow();},duration+100);}
    private void returnedFromHandoff(){if(handoff && SystemClock.elapsedRealtime()>handoffUntil)lockNow();handoff=false;}
    private void reauth(String title,Runnable after){
        if(vault==null)return;long delay=getPreferences(MODE_PRIVATE).getLong("retryAfter",0)-System.currentTimeMillis();if(delay>0){error("Try again in "+((delay+999)/1000)+" seconds");return;}EditText pass=passwordEditor("App passphrase");int generation=session;
        transientDialog=new AlertDialog.Builder(this).setTitle(title).setView(passwordField(pass)).setNegativeButton("Cancel",null).setPositiveButton("Unlock",(d,w)->{char[] chars=pass.getText().toString().toCharArray();pass.setText("");work(()->{byte[] key=null;try{key=passwordLock.unlock(chars);getPreferences(MODE_PRIVATE).edit().remove("failures").remove("retryAfter").apply();return true;}catch(Exception e){int n=Math.min(10,getPreferences(MODE_PRIVATE).getInt("failures",0)+1);getPreferences(MODE_PRIVATE).edit().putInt("failures",n).putLong("retryAfter",System.currentTimeMillis()+Math.min(60000,1000L*(1L<<n))).apply();throw new SecurityException("Incorrect passphrase or unavailable device key");}finally{Arrays.fill(chars,'\0');if(key!=null)Arrays.fill(key,(byte)0);}},ok->{if(generation==session && !sessionLocked)after.run();});}).show();
    }
    private void changePassword(){reauth("Confirm current passphrase",()->{
        LinearLayout view=column();EditText next=passwordEditor("New passphrase"),again=passwordEditor("Repeat new passphrase");view.addView(passwordField(next));view.addView(passwordField(again));
        transientDialog=new AlertDialog.Builder(this).setTitle("Change app passphrase").setView(view).setNegativeButton("Cancel",null).setPositiveButton("Change",(d,w)->{char[] chars=next.getText().toString().toCharArray();boolean same=next.getText().toString().equals(again.getText().toString());next.setText("");again.setText("");if(chars.length<12 || !same){Arrays.fill(chars,'\0');error("Use matching passphrases of 12–128 characters");return;}work(()->{byte[] key=unlockSecret.clone();try{passwordLock.replace(chars,key);return true;}finally{Arrays.fill(chars,'\0');Arrays.fill(key,(byte)0);}},ok->{render();Toast.makeText(this,"Passphrase changed; biometric unlock disabled",Toast.LENGTH_LONG).show();});}).show();
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
                    runWork(()->{byte[] key=enable?unlockSecret.clone():passwordLock.finishBiometricUnlock(result.getCryptoObject().getCipher());boolean kept=false;try{if(enable){passwordLock.finishBiometricEnable(result.getCryptoObject().getCipher(),key);return null;}Object next=firstFactor(key);kept=true;return next;}finally{if(!kept)Arrays.fill(key,(byte)0);}},v->{if(enable){render();Toast.makeText(MainActivity.this,"Biometric unlock enabled",Toast.LENGTH_LONG).show();}else afterFirstFactor(v);},!enable);
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
        worker.execute(()->{try{T result=job.run();ui.post(()->{busy=false;if(isDestroyed()||generation!=session){discardResult(result);return;}setEnabled(root,true);refreshSendActions();progress.setVisibility(View.GONE);try{success.run(result);}catch(Exception e){error(e.getMessage());}});}catch(Exception e){ui.post(()->{busy=false;if(isDestroyed()||generation!=session)return;setEnabled(root,true);refreshSendActions();progress.setText("Action needs attention");error(friendly(e));});}});
    }
    private void discardResult(Object result){if(result instanceof Bitmap)((Bitmap)result).recycle();else if(result instanceof Protocol.Group)Arrays.fill(((Protocol.Group)result).seed,(byte)0);else if(result instanceof Object[])for(Object item:(Object[])result)discardResult(item);else if(result instanceof AutoCloseable)try{((AutoCloseable)result).close();}catch(Exception ignored){}else if(result instanceof File)((File)result).delete();else if(result instanceof byte[])Arrays.fill((byte[])result,(byte)0);}
    private void setEnabled(View view,boolean enabled){view.setEnabled(enabled);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)setEnabled(group.getChildAt(i),enabled);}}
    /** Parsing failures (wrong version, other keys, damaged file) get one plain explanation instead of a low-level message. */
    private static String friendly(Exception e){
        if(e instanceof EOFException||(e instanceof IOException&&!(e instanceof FileNotFoundException)))return "This is not an Enigma message this version can open. It may come from an older Enigma version, be made for different keys or a different group, or be damaged. Nothing was opened.";
        return e.getMessage();
    }
    private void error(String text){if(!isDestroyed())transientDialog=new AlertDialog.Builder(this).setTitle("Action could not be completed").setMessage(text==null?"Invalid or unsupported encrypted data":text).setPositiveButton("OK",null).show();}
    private interface NameAction{void run(String value);}
    private void editName(String title,String initial,int max,NameAction action){EditText name=editor(title,false);name.setText(initial);limit(name,max);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(name).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("Enter a name");return;}dialog.dismiss();action.run(value);}));transientDialog=dialog;dialog.show();}
    private void limit(EditText field,int max){field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout row(View left,View right){LinearLayout row=new LinearLayout(this);LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,-2,1),b=new LinearLayout.LayoutParams(0,-2,1);a.setMargins(0,dp(6),0,dp(4));b.setMargins(dp(8),dp(6),0,dp(4));row.addView(left,a);row.addView(right,b);return row;}
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
    @Override protected void onResume(){super.onResume();foreground=true;if(handoff && (SystemClock.elapsedRealtime()>handoffUntil || ((android.app.KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()))lockNow();if(vault==null)lockScreen();else if(nearbyJoin!=null&&!nearbyJoin.closed())nearbyJoin.activityResumed();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);takeIntent(intent);if(vault!=null&&vault.setupCompleted&&!busy)handleIncoming();}
    /** A .enigma file or text packet opened or shared from another app; handled after unlock. Only content:// from other apps is read. */
    @SuppressWarnings("deprecation")
    private void takeIntent(Intent intent){
        if(intent==null)return;String action=intent.getAction();Uri uri=null;
        if(Intent.ACTION_VIEW.equals(action))uri=intent.getData();
        else if(Intent.ACTION_SEND.equals(action)){Object stream=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(stream instanceof Uri)uri=(Uri)stream;else{CharSequence text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);if(text!=null&&text.length()<=80000){incomingText=text.toString();incomingUri=null;}}}
        if(uri!=null&&"content".equals(uri.getScheme())&&!(getPackageName()+".files").equals(uri.getAuthority())){incomingUri=uri;incomingText=null;}
    }
    private void handleIncoming(){
        Uri uri=incomingUri;String text=incomingText;incomingUri=null;incomingText=null;
        if(uri!=null){fileAction="decrypt";receiveFile(uri);}else if(text!=null){page="receive";render();openPasted(text);}
    }
    @Override protected void onPause(){foreground=false;super.onPause();if(message!=null)message.setText("");clearResult();if(!handoff)lockNow();}
    @Override protected void onDestroy(){stopNearby();unregisterReceiver(screenLock);Vault previous=vault;vault=null;if(previous!=null)worker.execute(previous::close);stopMotion();closeVerified();worker.shutdown();super.onDestroy();}
}
