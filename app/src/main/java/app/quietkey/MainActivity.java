package app.quietkey;

import android.app.AlertDialog;
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
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.*;
import java.io.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends ComponentActivity {
    private static final int BG=0xff080f1d, CARD=0xff111e30, LINE=0xff20344b, WHITE=0xffedf5ff, MUTED=0xff93a9bf, GREEN=0xff4be3c1;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private Vault vault;
    private volatile boolean broken;
    private LinearLayout root,body,nav;
    private ScrollView scroll;
    private TextView progress,output;
    private EditText message;
    private Protocol.Group selected;
    private String page="home",ownId,recipientId,pendingName,capturePurpose,scanAction,scanGroupId,fileGroupId,fileRecipientId,fileAction;
    private boolean busy,foreground;
    private byte[] waitingDigest;
    private String waitingQr;
    private File encryptedFile;
    private FileProtocol.Verified verifiedFile;
    private ActivityResultLauncher<Intent> capture,saveFile;
    private ActivityResultLauncher<ScanOptions> scanner;
    private ActivityResultLauncher<String[]> openFile;
    private interface Job<T>{T run()throws Exception;}
    private interface Success<T>{void run(T result)throws Exception;}

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(saved!=null) {
            pendingName=saved.getString("pendingName");capturePurpose=saved.getString("capturePurpose");
            scanAction=saved.getString("scanAction");scanGroupId=saved.getString("scanGroupId");
            fileAction=saved.getString("fileAction");fileGroupId=saved.getString("fileGroupId");fileRecipientId=saved.getString("fileRecipientId");
            page=saved.getString("page","home");
        }
        capture=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            if(result.getResultCode()!=RESULT_OK || result.getData()==null)return;
            byte[] digest=result.getData().getByteArrayExtra("digest");
            if(vault==null){waitingDigest=digest;return;}captured(digest);
        });
        scanner=registerForActivityResult(new ScanContract(),result->{
            if(result.getContents()==null)return;
            if(vault==null){waitingQr=result.getContents();return;}scanned(result.getContents());
        });
        openFile=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{
            if(uri!=null){if(vault==null)error("Please choose the file again after initialization.");else pickedFile(uri);}
        });
        saveFile=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            if(result.getResultCode()==RESULT_OK && result.getData()!=null && result.getData().getData()!=null)exportFile(result.getData().getData());
        });
        shell();progress.setText("Opening your secure vault…");
        work(()->{cleanupCache();return new Vault(this);},v->{
            vault=v;ownId=Protocol.fingerprint(v.publicKey());
            if(!v.groups.isEmpty())selected=v.groups.get(0);
            chooseDefaultRecipient();render();
            if(waitingDigest!=null){byte[] digest=waitingDigest;waitingDigest=null;captured(digest);}
            else if(waitingQr!=null){String qr=waitingQr;waitingQr=null;scanned(qr);}
        });
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);out.putString("pendingName",pendingName);out.putString("capturePurpose",capturePurpose);
        out.putString("scanAction",scanAction);out.putString("scanGroupId",scanGroupId);out.putString("page",page);
        out.putString("fileAction",fileAction);out.putString("fileGroupId",fileGroupId);out.putString("fileRecipientId",fileRecipientId);
    }
    private void shell() {
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(12),dp(20),dp(12));root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(20),insets.getSystemWindowInsetTop()+dp(12),dp(20),insets.getSystemWindowInsetBottom()+dp(12));return insets;});
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.ic_enigma);icon.setContentDescription("Enigma shield");header.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout brand=column();brand.addView(label("ENIGMA",22,WHITE));brand.addView(label("PRIVATE BY DESIGN",10,GREEN));header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(label("● OFFLINE",10,GREEN));root.addView(header);
        progress=label("",11,MUTED);root.addView(progress);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        body=column();body.setPadding(0,dp(12),0,dp(18));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        nav=new LinearLayout(this);nav.setPadding(0,dp(8),0,0);root.addView(nav);setContentView(root);
    }
    private void render() {
        if(vault==null || isDestroyed())return;
        body.removeAllViews();message=null;output=null;nav.removeAllViews();
        progress.setText(busy?"Securing your data…":"ON-DEVICE KEYS  /  AUTHENTICATED ENCRYPTION");
        if(!vault.setupCompleted){setup();return;}
        String[] tabs={"home","messages","files","groups","identity"},names={"Home","Text","Files","Groups","Device"};
        for(int i=0;i<tabs.length;i++){
            String tab=tabs[i];Button b=button(names[i],()->{page=tab;render();});b.setTextSize(10);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);
            b.setTextColor(tab.equals(page)?BG:MUTED);b.setBackground(shape(tab.equals(page)?GREEN:CARD,12,0));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.setMargins(dp(2),0,dp(2),0);nav.addView(b,p);
        }
        switch(page){case "messages":messages();break;case "files":files();break;case "groups":groups();break;case "members":memberList();break;case "identity":identity();break;default:home();}
        scroll.post(()->scroll.scrollTo(0,0));
    }
    private void setup() {
        badge("YOUR PRIVATE SPACE",false);title("Set up your device");note("Give this device a name, then collect live camera frames to create your personal encryption vault.");
        LinearLayout card=card();card.addView(label("01   DEVICE IDENTITY",11,GREEN));card.addView(label("Private key protected on this device",17,WHITE));
        card.addView(label("Android Keystore creates your device's public/private key pair using secure system randomness. The private key is not exported.",14,MUTED));
        card.addView(label("02   PERSONAL VAULT",11,GREEN));card.addView(label("Add five seconds of camera input",17,WHITE));
        card.addView(label("Live frame hashes are mixed with system randomness to make a separate personal vault secret. No video or audio is saved. Camera input does not replace Android's private-key generator.",14,MUTED));body.addView(card);
        EditText name=editor("Device name, e.g. Chris's phone",false);name.setText(vault.deviceName.equals("This device")?"":vault.deviceName);limit(name,40);body.addView(name);
        body.addView(primary("Set up with live camera",()->{
            String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("Name this device");return;}
            pendingName=value;capturePurpose="setup";capture.launch(new Intent(this,CameraActivity.class).putExtra("purpose","setup"));
        }));
        if(!vault.groups.isEmpty()){
            note("Your existing identity and "+vault.groups.size()+" group(s) will be kept.");
            body.addView(button("Continue with existing groups",()->{
                String value=name.getText().toString().trim();work(()->{vault.deviceName=value.isEmpty()?"This device":value;vault.setupCompleted=true;persist();return true;},ok->{page="home";render();});
            }));
        }
        note("Keep this installation: uninstalling or clearing app storage loses its private keys. Enigma encrypts text and files; it does not route internet traffic.");
    }
    private void captured(byte[] digest) {
        String name=pendingName;boolean setup="setup".equals(capturePurpose);
        if(name==null || digest==null){error("Capture interrupted. Start the setup or group capture again.");return;}
        work(()->{
            try {
                if(vault.groups.size()>=30)throw new IllegalArgumentException("Maximum 30 groups. Remove an unused group before creating another.");
                Protocol.Group group=Protocol.create(setup?"Personal vault":name,digest,vault.publicKey());
                group.memberNames.put(ownId,setup?name:vault.deviceName);vault.groups.add(group);
                if(setup){vault.deviceName=name;vault.personalGroupId=group.id;vault.setupCompleted=true;vault.setupCapturedAt=System.currentTimeMillis();}
                persist();return group;
            }finally{Arrays.fill(digest,(byte)0);}
        },group->{selected=group;chooseDefaultRecipient();page=setup?"home":"groups";capturePurpose=null;render();});
    }
    private void home() {
        badge("ENCRYPTION READY",true);title("Your data. Your keys.");note("Private text and files, protected before you share them.");
        LinearLayout status=card();status.addView(label("SECURITY STATUS",11,GREEN));stat(status,"Device",vault.deviceName);stat(status,"Private key","Android Keystore");stat(status,"Encryption","AES-256-GCM + signed packets");stat(status,"Trusted groups",Integer.toString(vault.groups.size()));body.addView(status);
        if(selected!=null){LinearLayout active=card();active.addView(label("ACTIVE GROUP",11,GREEN));active.addView(label(selected.name,21,WHITE));active.addView(label(selected.members.size()+" verified device(s) • choose a recipient before sending",13,MUTED));body.addView(active);}
        body.addView(primary("Encrypt a message",()->{page="messages";render();}));body.addView(button("Protect a file or image",()->{page="files";render();}));
        note("OFFLINE ENCRYPTION • NO ACCOUNT • NO SERVER");
    }
    private void identity() {
        title("Device & keys");note(vault.deviceName+" • your installation identity");
        LinearLayout card=card();card.addView(label("PRIVATE KEY",11,GREEN));card.addView(label("Stored in Android Keystore",18,WHITE));card.addView(label("Your private key stays on this device. Only your public identity is shown below. Enigma never displays or exports the private key.",14,MUTED));
        card.addView(label("CAMERA SETUP",11,GREEN));card.addView(label(vault.setupCapturedAt>0?"Completed "+DateFormat.getDateInstance().format(new Date(vault.setupCapturedAt)):"Existing identity retained; personal camera vault not created",14,WHITE));body.addView(card);
        body.addView(button("Rename this device",()->editName("Device name",vault.deviceName,40,value->work(()->{vault.deviceName=value;for(Protocol.Group g:vault.groups)g.memberNames.put(ownId,value);persist();return true;},ok->render()))));
        if(vault.personalGroupId==null)body.addView(button("Create personal vault with camera",()->{pendingName=vault.deviceName;capturePurpose="setup";capture.launch(new Intent(this,CameraActivity.class).putExtra("purpose","setup"));}));
        LinearLayout qr=card();qr.addView(label("PUBLIC IDENTITY QR",11,GREEN));
        try{showInlineQr(qr,Protocol.identity(vault.publicKey()));qr.addView(label("Compare the full fingerprint in person",13,GREEN));qr.addView(label(pretty(ownId),13,WHITE));}catch(Exception e){error(e.getMessage());}body.addView(qr);
        note("Only share this public identity with someone you are pairing with. Camera input strengthens your separate vault secret; Android generates your device private key. This prototype has not had an independent security audit.");
    }
    private void groups() {
        title("Trusted groups");note("Keep recipients organized. Each group has its own identity, secret and verified member list.");
        body.addView(primary("Create a group",this::newGroup));body.addView(button("Join with invitation QR",()->scan("join",null)));
        if(vault.groups.isEmpty())note("Create a group or scan its owner's invitation to start.");
        for(Protocol.Group g:vault.groups){
            LinearLayout card=card();card.addView(label(g.id.equals(vault.personalGroupId)?"PERSONAL VAULT":isOwner(g)?"GROUP OWNER":"GROUP MEMBER",10,GREEN));
            card.addView(label(g.name,21,WHITE));card.addView(label(g.members.size()+" verified device(s) • "+(g==selected?"selected":"tap to select"),13,MUTED));
            card.addView(button(g==selected?"Manage selected group":"Select & manage",()->{selected=g;chooseDefaultRecipient();render();}));
            if(g==selected){
                card.addView(label("Expires "+DateFormat.getDateInstance().format(new Date(g.expires)),12,MUTED));
                card.addView(primary("Write to a member",()->{page="messages";render();}));
                card.addView(button("Manage members ("+g.members.size()+")",()->{selected=g;page="members";render();}));
                if(isOwner(g))card.addView(button("Invite a new member",()->scan("invite",g)));
                card.addView(button("Trust another member's public QR",()->scan("trust",g)));
                card.addView(button("Rename this group",()->editName("Group name",g.name,60,value->work(()->{g.name=value;persist();return true;},ok->render()))));
                card.addView(button("Remove group from this device",()->deleteGroup(g)));
            }body.addView(card);
        }
        note("Private messages and files are encrypted for the chosen member and your own device. Other group members cannot open them with the group secret. Removing local trust does not revoke keys held on another device.");
    }
    private void newGroup() {editName("Create a group","",60,value->{pendingName=value;capturePurpose="group";capture.launch(new Intent(this,CameraActivity.class).putExtra("purpose","group"));});}
    private void deleteGroup(Protocol.Group g) {
        new AlertDialog.Builder(this).setTitle("Remove "+g.name+"?").setMessage("This removes its secret, trusted members and replay history from this device. Other members keep their keys. Rejoining resets replay protection.")
            .setNegativeButton("Keep group",null).setPositiveButton("Remove",(d,w)->work(()->{vault.groups.remove(g);if(g.id.equals(vault.personalGroupId))vault.personalGroupId=null;persist();Arrays.fill(g.seed,(byte)0);return true;},ok->{if(selected==g)selected=vault.groups.isEmpty()?null:vault.groups.get(0);chooseDefaultRecipient();render();})).show();
    }
    private boolean isOwner(Protocol.Group g){return ownId!=null && g.owner.equals(ownId);}
    private Protocol.Group group(String id){if(vault!=null)for(Protocol.Group g:vault.groups)if(g.id.equals(id))return g;return null;}
    private void chooseDefaultRecipient(){recipientId=null;if(selected!=null){for(String id:selected.members.keySet())if(!id.equals(ownId)){recipientId=id;break;}if(recipientId==null)recipientId=ownId;}}
    private String memberName(Protocol.Group g,String id){if(id.equals(ownId))return "My device";String name=g.memberNames.get(id);return name==null?"Member "+id.substring(0,Math.min(8,id.length())):name;}
    private void scan(String action,Protocol.Group g) {
        if("invite".equals(action) && (g==null || !isOwner(g))){error("Only the group creator can invite members.");return;}
        scanAction=action;scanGroupId=g==null?null:g.id;
        ScanOptions options=new ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(true).setCaptureActivity(SecureCaptureActivity.class).setPrompt(action.equals("join")?"Scan the owner's invitation QR":"Scan the member's public identity QR");scanner.launch(options);
    }
    private void scanned(String qr){Protocol.Group g=group(scanGroupId);if("join".equals(scanAction))join(qr);else if(g!=null)recipient(qr,g,"invite".equals(scanAction));else error("Pairing interrupted. Start the scan again.");}
    private void recipient(String qr,Protocol.Group g,boolean invite) {
        work(()->Protocol.parseIdentity(qr),key->{
            String fingerprint=Protocol.fingerprint(key);EditText name=editor("Member name (optional)",false);limit(name,40);
            LinearLayout view=column();view.setPadding(dp(20),dp(12),dp(20),dp(12));view.addView(label("Compare the full fingerprint with their device in person. Confirm only if every character matches.",14,MUTED));view.addView(label(pretty(fingerprint),13,WHITE));view.addView(name);
            new AlertDialog.Builder(this).setTitle("Verify group member").setView(view).setNegativeButton("Cancel",null).setPositiveButton("Fingerprints match",(d,w)->{
                String alias=name.getText().toString().trim();work(()->{String packet=invite?Protocol.invite(g,key,vault.publicKey(),vault.privateKey()):null;g.members.put(fingerprint,Protocol.b64(key.getEncoded()));if(!alias.isEmpty())g.memberNames.put(fingerprint,alias);persist();return packet;},packet->{selected=g;recipientId=fingerprint;render();if(packet!=null)qrDialog("Invite to "+g.name,packet,"This invitation is encrypted for that member's device. They scan it with Join with invitation QR.");});
            }).show();
        });
    }
    private void join(String qr) {
        work(()->Protocol.accept(qr,vault.publicKey(),vault.privateKey()),g->{
            if(group(g.id)!=null){Arrays.fill(g.seed,(byte)0);error("You already have this group. Trust the other member's public identity QR instead.");return;}
            new AlertDialog.Builder(this).setTitle("Join "+g.name+"?").setMessage("Verify the owner's full fingerprint in person:\n\n"+pretty(g.owner)+"\n\nOnly join if it matches the owner's device.").setNegativeButton("Cancel",(d,w)->Arrays.fill(g.seed,(byte)0)).setOnCancelListener(d->Arrays.fill(g.seed,(byte)0)).setPositiveButton("Match — join",(d,w)->work(()->{if(vault.groups.size()>=30)throw new IllegalArgumentException("Maximum 30 groups");g.memberNames.put(ownId,vault.deviceName);g.memberNames.put(g.owner,"Group owner");vault.groups.add(g);persist();return g;},joined->{selected=joined;chooseDefaultRecipient();page="groups";render();})).show();
        });
    }
    private void memberList() {
        Protocol.Group g=selected;if(g==null){page="groups";groups();return;}
        title(g.name+" members");body.addView(button("Back to groups",()->{page="groups";render();}));
        for(String id:new ArrayList<>(g.members.keySet())){
            LinearLayout card=card();card.addView(label(memberName(g,id)+(id.equals(g.owner)?" · owner":""),18,WHITE));card.addView(label(pretty(id),12,MUTED));
            if(!id.equals(ownId)){
                card.addView(button("Name this member",()->editName("Member name",g.memberNames.getOrDefault(id,""),40,value->work(()->{g.memberNames.put(id,value);persist();return true;},ok->render()))));
                card.addView(primary("Send private message",()->{selected=g;recipientId=id;page="messages";render();}));
                if(!id.equals(g.owner))card.addView(button("Remove local trust",()->new AlertDialog.Builder(this).setTitle("Remove local trust?").setMessage("This device will stop accepting this member's signatures and offering them as a recipient. Their existing group key is not revoked. Create a new group to replace a shared group secret.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->work(()->{g.members.remove(id);g.memberNames.remove(id);persist();return true;},ok->{chooseDefaultRecipient();render();})).show()));
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
        List<String> ids=new ArrayList<>(selected.members.keySet()),labels=new ArrayList<>();for(String id:ids)labels.add(memberName(selected,id)+" · "+id.substring(0,8));
        if(!ids.contains(recipientId))chooseDefaultRecipient();Spinner recipients=new Spinner(this);
        recipients.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));recipients.setSelection(Math.max(0,ids.indexOf(recipientId)));
        recipients.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){recipientId=ids.get(pos);}});body.addView(recipients);
        note("Only the selected member and your device can open new private packets. Deliver the encrypted packet to that person in your messenger.");
    }
    private void messages() {
        title("Private messages");if(selected==null){note("Create or join a group first.");body.addView(primary("Manage groups",()->{page="groups";render();}));return;}
        selectors();Protocol.Group g=selected;
        message=editor("Write privately, or paste encrypted text…",true);body.addView(message,new LinearLayout.LayoutParams(-1,dp(145)));
        body.addView(primary("Encrypt for selected member",()->{String clear=message.getText().toString(),to=recipientId;work(()->DirectProtocol.encrypt(g,to,clear,vault.publicKey(),vault.privateKey()),wire->{if(message!=null)message.setText("");result(wire,true,"For "+memberName(g,to)+" • "+g.name+"\n"+pretty(to));});}));
        body.addView(button("Decrypt pasted message",()->{
            String wire=message.getText().toString();work(()->{
                String text,sender,id;
                if(wire.trim().startsWith("QKD1:")){DirectProtocol.Opened opened=DirectProtocol.decrypt(g,wire,vault.publicKey(),vault.privateKey());text=opened.text;sender=opened.sender;id=opened.messageId;}
                else{Protocol.Opened opened=Protocol.decrypt(g,wire);text="Legacy group message (shared with group members)\n\n"+opened.text;sender=opened.sender;id=opened.messageId;}
                g.seen.add(id);persist();return "Verified sender: "+memberName(g,sender)+"\n"+pretty(sender)+"\n\n"+text;
            },text->{if(message!=null)message.setText("");result(text,false);});
        }));note("Plaintext clears when you leave the app. Existing QKM1 group messages remain readable; new messages use recipient-private QKD1 encryption.");
    }
    private void result(String value,boolean encrypted) {
        result(value,encrypted,null);
    }
    private void result(String value,boolean encrypted,String recipient) {
        if(!foreground)return;
        clearResult();LinearLayout card=card();card.addView(label(encrypted?"ENCRYPTED • READY TO SHARE":"DECRYPTED • SIGNATURE VERIFIED",10,GREEN));
        if(recipient!=null)card.addView(label(recipient,13,WHITE));
        output=label(value,15,WHITE);output.setTextIsSelectable(encrypted);card.addView(output);
        if(encrypted){card.addView(primary("Share encrypted message",()->{Intent share=new Intent(Intent.ACTION_SEND);share.setType("text/plain");share.putExtra(Intent.EXTRA_TEXT,value);startActivity(Intent.createChooser(share,"Send to your selected member"));}));card.addView(button("Copy encrypted message",()->{ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("Enigma encrypted message",value));Toast.makeText(this,"Encrypted message copied",Toast.LENGTH_SHORT).show();}));}body.addView(card);
    }
    private void files() {
        title("File protection");note("Encrypt images, PDFs, video and other files up to 50 MB. The original name and type stay inside the encrypted package.");
        if(selected==null){body.addView(primary("Create or join a group",()->{page="groups";render();}));return;}
        selectors();LinearLayout card=card();card.addView(label("ANY FILE. ONE PRIVATE PACKAGE.",10,GREEN));card.addView(label("Images / documents / video / archives",17,WHITE));card.addView(label("Choose a file → encrypt → save or share its .enigma package. The source file stays in its original location.",14,MUTED));body.addView(card);
        body.addView(primary("Choose file to encrypt",()->pickFile("encrypt")));body.addView(button("Open .enigma file to decrypt",()->pickFile("decrypt")));
        note("Decryption verifies the sender, every encrypted chunk and the full package before offering Save restored file. Saving creates a readable file in the location you choose.");
        if(encryptedFile!=null && encryptedFile.exists() && verifiedFile==null)fileResult(false);
        else if(verifiedFile!=null)fileResult(true);
    }
    private void pickFile(String action){closeVerified();encryptedFile=null;clearResult();fileGroupId=selected.id;fileRecipientId=recipientId;fileAction=action;openFile.launch(new String[]{"*/*"});}
    private File cacheFile()throws IOException {File directory=new File(getCacheDir(),"encrypted");if(!directory.exists() && !directory.mkdirs())throw new IOException("Could not prepare file storage");return File.createTempFile("enigma-",".enigma",directory);}
    private void cleanupCache(){File directory=new File(getCacheDir(),"encrypted");File[] files=directory.listFiles();if(files!=null)for(File file:files)if(file.lastModified()<System.currentTimeMillis()-86400000L)file.delete();}
    private void pickedFile(Uri uri) {
        Protocol.Group g=group(fileGroupId);if(g==null){error("Choose a group and file again.");return;}String action=fileAction,to=fileRecipientId;
        if("encrypt".equals(action))work(()->{
            String name="file",mime=getContentResolver().getType(uri);
            try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                if(cursor!=null && cursor.moveToFirst()){int n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=cursor.getColumnIndex(OpenableColumns.SIZE);if(n>=0)name=cursor.getString(n);if(s>=0 && !cursor.isNull(s) && cursor.getLong(s)>FileProtocol.MAX_FILE_BYTES)throw new IOException("Files must be 50 MB or smaller");}
            }
            File file=cacheFile();boolean done=false;
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){
                if(in==null)throw new IOException("Could not open that file");FileProtocol.encrypt(g,to,in,out,name,mime,vault.publicKey(),vault.privateKey());done=true;return file;
            }finally{if(!done)file.delete();}
        },file->{closeVerified();encryptedFile=file;fileResult(false);});
        else if("decrypt".equals(action))work(()->{
            File file=cacheFile();boolean done=false;
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){
                if(in==null)throw new IOException("Could not open that file");copy(in,out,FileProtocol.MAX_FILE_BYTES+100000);
            }catch(Exception e){file.delete();throw e;}
            try{FileProtocol.Verified verified=FileProtocol.inspect(g,file,vault.publicKey(),vault.privateKey());done=true;return new Object[]{file,verified};}finally{if(!done)file.delete();}
        },pair->{closeVerified();encryptedFile=(File)pair[0];verifiedFile=(FileProtocol.Verified)pair[1];fileResult(true);});
    }
    private void fileResult(boolean decrypted) {
        if(!foreground)return;clearResult();LinearLayout card=card();card.addView(label(decrypted?(verifiedFile.previouslyOpened?"FILE VERIFIED • PREVIOUSLY OPENED":"FILE VERIFIED • READY TO RESTORE"):"FILE ENCRYPTED • READY TO SHARE",10,GREEN));
        Protocol.Group preparedGroup=group(fileGroupId);
        if(preparedGroup==null){closeVerified();encryptedFile=null;return;}
        output=label(decrypted?verifiedFile.name+"\n"+formatSize(verifiedFile.size)+" • from "+memberName(preparedGroup,verifiedFile.sender):"Private .enigma package\nFor "+memberName(preparedGroup,fileRecipientId)+" • "+preparedGroup.name,16,WHITE);card.addView(output);
        card.addView(primary(decrypted?"Save restored file":"Save encrypted file",()->{
            fileAction=decrypted?"restore":"save-encrypted";Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(decrypted?verifiedFile.mime:"application/octet-stream").putExtra(Intent.EXTRA_TITLE,decrypted?verifiedFile.name:encryptedFile.getName());saveFile.launch(intent);
        }));
        if(!decrypted)card.addView(button("Share encrypted file",()->{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",encryptedFile);Intent share=new Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newRawUri("Enigma encrypted file",uri));startActivity(Intent.createChooser(share,"Send to your selected member"));}));body.addView(card);
    }
    private void exportFile(Uri destination) {
        if(encryptedFile==null || ("restore".equals(fileAction) && verifiedFile==null)){error("File workflow interrupted. Choose the encrypted file again.");return;}
        boolean restore="restore".equals(fileAction);File source=encryptedFile;FileProtocol.Verified verified=verifiedFile;Protocol.Group g=group(fileGroupId);
        work(()->{
            try(OutputStream out=getContentResolver().openOutputStream(destination,"wt")){
                if(out==null)throw new IOException("Could not save to that location");
                if(restore){if(g==null || System.currentTimeMillis()>=g.expires || !g.members.containsKey(verified.sender) || !g.members.containsKey(verified.recipient))throw new SecurityException("Group or trusted member unavailable");g.seen.add(verified.messageId);persist();FileProtocol.restore(verified,out);}
                else try(InputStream in=new FileInputStream(source)){copy(in,out,FileProtocol.MAX_FILE_BYTES+100000);}return true;
            }catch(Exception e){try{DocumentsContract.deleteDocument(getContentResolver(),destination);}catch(Exception ignored){}if(restore)ui.post(()->{verifiedFile=null;encryptedFile=null;if(!isDestroyed())render();});throw e;}
            finally{if(restore)verified.close();}
        },ok->{if(restore){verifiedFile=null;encryptedFile=null;source.delete();}render();Toast.makeText(this,restore?"Readable file saved to your chosen location":"Encrypted file saved",Toast.LENGTH_LONG).show();});
    }
    private static void copy(InputStream in,OutputStream out,long max)throws IOException {byte[] buffer=new byte[65536];long total=0;int count;while((count=in.read(buffer))!=-1){if(count==0)throw new IOException("File provider stopped responding");total+=count;if(total>max)throw new IOException("File exceeds the size limit");out.write(buffer,0,count);}out.flush();}
    private void closeVerified(){if(verifiedFile!=null){verifiedFile.close();verifiedFile=null;}}
    private String formatSize(long bytes){return bytes>=1048576?String.format(Locale.ROOT,"%.1f MB",bytes/1048576.0):String.format(Locale.ROOT,"%.1f KB",bytes/1024.0);}
    private void clearResult(){if(output!=null && output.getParent() instanceof View)body.removeView((View)output.getParent());output=null;}
    private void qrDialog(String title,String packet,String caption)throws Exception {LinearLayout view=column();view.setPadding(dp(16),dp(12),dp(16),dp(12));showInlineQr(view,packet);view.addView(label(caption,14,MUTED));new AlertDialog.Builder(this).setTitle(title).setView(view).setPositiveButton("Done",null).show();}
    private void showInlineQr(LinearLayout parent,String packet)throws Exception {BitMatrix matrix=new MultiFormatWriter().encode(packet,BarcodeFormat.QR_CODE,700,700);int[] pixels=new int[700*700];for(int y=0;y<700;y++)for(int x=0;x<700;x++)pixels[y*700+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;ImageView image=new ImageView(this);image.setImageBitmap(Bitmap.createBitmap(pixels,700,700,Bitmap.Config.ARGB_8888));image.setContentDescription("Public identity or encrypted invitation QR");image.setAdjustViewBounds(true);parent.addView(image,new LinearLayout.LayoutParams(-1,dp(285)));}
    private void persist()throws Exception {try{vault.save();}catch(Exception e){broken=true;throw new SecurityException("Could not save securely. Close and reopen the app before continuing.",e);}}
    private <T> void work(Job<T> job,Success<T> success) {
        if(busy || broken){if(broken)error("Close and reopen the app to reload its secure vault.");return;}
        busy=true;setEnabled(root,false);progress.setText("Securing your data…");
        worker.execute(()->{try{T result=job.run();ui.post(()->{busy=false;if(isDestroyed()){discardResult(result);return;}setEnabled(root,true);progress.setText("ON-DEVICE KEYS  /  AUTHENTICATED ENCRYPTION");try{success.run(result);}catch(Exception e){error(e.getMessage());}});}catch(Exception e){ui.post(()->{busy=false;if(isDestroyed())return;setEnabled(root,true);progress.setText("Action needs attention");error(e instanceof EOFException?"Incomplete encrypted message or file":e.getMessage());});}});
    }
    private void discardResult(Object result){if(result instanceof Object[])for(Object item:(Object[])result)discardResult(item);else if(result instanceof AutoCloseable)try{((AutoCloseable)result).close();}catch(Exception ignored){}else if(result instanceof File)((File)result).delete();}
    private void setEnabled(View view,boolean enabled){view.setEnabled(enabled);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)setEnabled(group.getChildAt(i),enabled);}}
    private void error(String text){if(!isDestroyed())new AlertDialog.Builder(this).setTitle("Action could not be completed").setMessage(text==null?"Invalid or unsupported encrypted data":text).setPositiveButton("OK",null).show();}
    private interface NameAction{void run(String value);}
    private void editName(String title,String initial,int max,NameAction action){EditText name=editor(title,false);name.setText(initial);limit(name,max);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(name).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("Enter a name");return;}dialog.dismiss();action.run(value);}));dialog.show();}
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
    @Override protected void onResume(){super.onResume();foreground=true;}
    @Override protected void onPause(){foreground=false;super.onPause();if(message!=null)message.setText("");clearResult();}
    @Override protected void onDestroy(){FileProtocol.Verified pending=verifiedFile;verifiedFile=null;if(pending!=null)worker.execute(pending::close);worker.shutdown();super.onDestroy();}
}
