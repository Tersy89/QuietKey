package app.quietkey;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.*;
import java.security.PublicKey;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends ComponentActivity {
    private static final int BG=0xff101b22, CARD=0xff1a2933, WHITE=0xfff0f5f2, MUTED=0xffaebdbd, GREEN=0xffa6e3b8;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Vault vault;
    private volatile boolean broken;
    private LinearLayout root, body;
    private TextView output, progress;
    private EditText message;
    private Protocol.Group selected;
    private String page="groups", pendingName, scanAction;
    private Protocol.Group scanGroup;
    private boolean busy, foreground;
    private ActivityResultLauncher<Intent> capture;
    private ActivityResultLauncher<ScanOptions> scanner;
    private interface Job<T> { T run() throws Exception; }
    private interface Success<T> { void run(T result) throws Exception; }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(saved!=null) { pendingName=saved.getString("pendingName"); scanAction=saved.getString("scanAction"); }
        capture=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result -> {
            if(result.getResultCode()!=RESULT_OK || result.getData()==null) return;
            byte[] digest=result.getData().getByteArrayExtra("digest"); String name=pendingName;
            if (vault==null || name==null) { error("Capture interrupted. Please create the group again."); return; }
            work(() -> { Protocol.Group g=Protocol.create(name,digest,vault.publicKey());
                if(vault.groups.size()>=30)throw new IllegalArgumentException("Maximum 30 groups"); vault.groups.add(g); persist(); Arrays.fill(digest,(byte)0); return g;
            },g -> { selected=g; page="groups"; render(); });
        });
        scanner=registerForActivityResult(new ScanContract(),result -> {
            if(result.getContents()==null) return;
            if(vault==null) { error("Please scan again after initialization."); return; }
            if("join".equals(scanAction)) join(result.getContents());
            else if(scanGroup!=null && ("invite".equals(scanAction)||"trust".equals(scanAction))) recipient(result.getContents(),scanGroup,"invite".equals(scanAction));
            else error("Pairing interrupted. Please start the scan again.");
        });
        shell(); progress.setText("Preparing your device identity…");
        work(() -> new Vault(this),v -> { vault=v; if(!v.groups.isEmpty())selected=v.groups.get(0); render(); });
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("pendingName",pendingName); out.putString("scanAction",scanAction); }
    private void shell() {
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(22),dp(12),dp(22),dp(16)); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets) -> { v.setPadding(dp(22),insets.getSystemWindowInsetTop()+dp(12),dp(22),insets.getSystemWindowInsetBottom()+dp(16)); return insets; });
        TextView brand=label("QuietKey",30,WHITE); brand.setTypeface(null,Typeface.BOLD); root.addView(brand);
        root.addView(label("PRIVATE WORDS. ANY CHANNEL.",11,GREEN));
        progress=label("",13,GREEN); progress.setPadding(0,dp(8),0,dp(4)); root.addView(progress);
        LinearLayout nav=new LinearLayout(this);
        for(String tab:new String[]{"groups","messages","identity"}) { Button b=button(tab.substring(0,1).toUpperCase(Locale.ROOT)+tab.substring(1),()->{ page=tab; render(); }); nav.addView(b,new LinearLayout.LayoutParams(0,dp(50),1)); }
        root.addView(nav);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(0,dp(18),0,dp(24)); scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }
    private void render() {
        if(vault==null || isDestroyed())return;
        body.removeAllViews(); message=null; output=null;
        progress.setText(busy?"Working…":"OFFLINE · KEYS PROTECTED ON THIS DEVICE");
        if(page.equals("identity")) identity(); else if(page.equals("messages")) messages(); else groups();
    }
    private void identity() {
        title("Your public identity"); note("A public key is created on this device. Your private key stays in Android Keystore. Show this QR to someone inviting you to a group.");
        try { showInlineQr(body,Protocol.identity(vault.publicKey())); body.addView(label("Verify this fingerprint in person",15,GREEN)); body.addView(label(pretty(Protocol.fingerprint(vault.publicKey())),14,WHITE)); }
        catch(Exception e){error(e.getMessage());}
        note("Pair in person: B shows this QR → A scans it and shows an invitation → B scans A's invitation. Compare the fingerprints on both devices.");
        note("This prototype has not had an independent security audit. Anyone with an unlocked device can use its keys. Uninstalling the app or losing the device loses access to your groups.");
    }
    private void groups() {
        title("Your private circles"); note("Create a shared group secret with live camera input and secure system randomness.");
        body.addView(button("＋ Create group with camera",this::newGroup));
        body.addView(button("Scan invitation QR",()->scan("join",null)));
        if(vault.groups.isEmpty())note("No groups yet. Create one, or show your identity QR to a friend and scan their invitation.");
        for(Protocol.Group g:vault.groups) {
            LinearLayout card=card(); card.addView(label(g.name+(g==selected?"  • SELECTED":""),20,WHITE));
            card.addView(label((isOwner(g)?"You created this group · ":"Member · ")+g.members.size()+" trusted identities · expires "+DateFormat.getDateInstance().format(new Date(g.expires)),12,MUTED));
            card.addView(button("Use this group",()->{selected=g; page="messages";render();}));
            if(isOwner(g))card.addView(button("Invite member via QR",()->scan("invite",g)));
            else card.addView(label("Only the group creator can invite members",13,GREEN));
            card.addView(button("Trust sender's identity QR",()->scan("trust",g)));
            card.addView(button("View trusted fingerprints",()->members(g)));
            card.addView(button("Delete from this device",()->new AlertDialog.Builder(this).setTitle("Delete "+g.name+"?").setMessage("This removes the group key and replay log from this device. Other members keep their keys. Re-pairing may make previously received messages readable again.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->work(()->{vault.groups.remove(g);persist();Arrays.fill(g.seed,(byte)0);return true;},ok->{if(selected==g)selected=vault.groups.isEmpty()?null:vault.groups.get(0);render();})).show()));
            body.addView(card);
        }
        note("Only a group’s creator can share its key through this app. For groups with more than two people, each receiver must scan and verify every sender's identity QR. To remove a member, create a new group secret and pair only the remaining members.");
    }
    private void newGroup() {
        EditText name=editor("Group name",false); name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Create a private circle").setView(name).setNegativeButton("Cancel",null).setPositiveButton("Capture video frames",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=name.getText().toString().trim(); if(value.isEmpty()){name.setError("Enter a name");return;}pendingName=value;dialog.dismiss();capture.launch(new Intent(this,CameraActivity.class));})); dialog.show();
    }
    private boolean isOwner(Protocol.Group g) { try{return g.owner.equals(Protocol.fingerprint(vault.publicKey()));}catch(Exception e){return false;} }
    private void scan(String action,Protocol.Group group) {
        if("invite".equals(action) && (group==null || !isOwner(group))) {error("Only the group creator can share this group key.");return;}
        scanAction=action;scanGroup=group;
        ScanOptions options=new ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(true).setCaptureActivity(SecureCaptureActivity.class).setPrompt(action.equals("join")?"Scan the invitation shown by your friend":"Scan and verify your friend's public identity QR"); scanner.launch(options);
    }
    private void recipient(String qr,Protocol.Group g,boolean invite) {
        work(()->Protocol.parseIdentity(qr),key->{
            String fingerprint=Protocol.fingerprint(key);
            new AlertDialog.Builder(this).setTitle("Verify your friend's identity").setMessage("Compare this full fingerprint with the identity screen on their device, in person:\n\n"+pretty(fingerprint)+"\n\nOnly confirm if they match.")
                .setNegativeButton("Cancel",null).setPositiveButton("Fingerprints match",(d,w)->work(()->{
                    String packet=invite?Protocol.invite(g,key,vault.publicKey(),vault.privateKey()):null;
                    g.members.put(fingerprint,Protocol.b64(key.getEncoded()));persist();return packet;
                },packet->{render();if(packet!=null)qrDialog("Invitation for "+g.name,packet,"Your friend scans this with Scan invitation QR. It is encrypted for their device only.");})).show();
        });
    }
    private void join(String qr) {
        work(()->Protocol.accept(qr,vault.publicKey(),vault.privateKey()),g->{
            if(vault.groups.stream().anyMatch(existing->existing.id.equals(g.id))) { Arrays.fill(g.seed,(byte)0);error("You already have this group. Scan other members' identity QRs to trust them.");return; }
            String inviter=g.owner;
            new AlertDialog.Builder(this).setTitle("Join "+g.name+"?").setMessage("Compare the inviter's full fingerprint with their identity screen, in person:\n\n"+pretty(inviter)+"\n\nConfirm only if it matches. Group expires "+DateFormat.getDateInstance().format(new Date(g.expires))+".")
                .setNegativeButton("Cancel",(d,w)->Arrays.fill(g.seed,(byte)0)).setOnCancelListener(d->Arrays.fill(g.seed,(byte)0)).setPositiveButton("Match — join group",(d,w)->work(()->{if(vault.groups.size()>=30)throw new IllegalArgumentException("Maximum 30 groups");vault.groups.add(g);persist();return g;},joined->{selected=joined;page="messages";render();})).show();
        });
    }
    private void members(Protocol.Group g) {
        StringBuilder text=new StringBuilder();for(String id:g.members.keySet())text.append(pretty(id)).append("\n\n");
        new AlertDialog.Builder(this).setTitle(g.name+" · trusted identities").setMessage(text.toString()).setPositiveButton("Done",null).show();
    }
    private void messages() {
        title("Words for your circle");
        if(selected==null){note("Create or join a group first.");return;}
        Protocol.Group g=selected;note("Using “"+g.name+"” · encrypted text can travel over SMS, email, or any messenger.");
        if(vault.groups.size()>1) { Spinner picker=new Spinner(this);String[] names=vault.groups.stream().map(group->group.name).toArray(String[]::new);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names);picker.setAdapter(adapter);picker.setSelection(vault.groups.indexOf(g)); picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?>p){} public void onItemSelected(android.widget.AdapterView<?>p,View v,int pos,long id){if(selected!=vault.groups.get(pos)){selected=vault.groups.get(pos);render();}}});body.addView(picker); }
        message=editor("Write a message, or paste QKM1 encrypted text…",true);body.addView(message,new LinearLayout.LayoutParams(-1,dp(150)));
        body.addView(button("Encrypt message",()->{
            String clear=message.getText().toString(); work(()->Protocol.encrypt(g,clear,vault.publicKey(),vault.privateKey()),wire->{message.setText("");result(wire,true);});
        }));
        body.addView(button("Decrypt pasted message",()->{
            String wire=message.getText().toString();work(()->{Protocol.Opened opened=Protocol.decrypt(g,wire);g.seen.add(opened.messageId);persist();return opened;},opened->{message.setText("");result("Verified sender\n"+pretty(opened.sender)+"\n\n"+opened.text,false);});
        }));
        note("Each message uses a fresh derived AES key and is signed by its sender. Modified messages and previously opened messages are rejected.");
        note("Plaintext clears when the app leaves the foreground. Your keyboard and the app you copy text from may still see the original text.");
    }
    private void result(String value,boolean encrypted) {
        if(!foreground)return;
        if(output!=null && output.getParent()!=null)body.removeView((View)output.getParent());
        LinearLayout card=card();card.addView(label(encrypted?"READY TO SEND":"VERIFIED MESSAGE",12,GREEN));
        output=label(value,15,WHITE);output.setTextIsSelectable(encrypted);card.addView(output);
        if(encrypted) {
            card.addView(button("Share encrypted text",()->{Intent share=new Intent(Intent.ACTION_SEND);share.setType("text/plain");share.putExtra(Intent.EXTRA_TEXT,value);startActivity(Intent.createChooser(share,"Send encrypted message"));}));
            card.addView(button("Copy encrypted text",()->{ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("QuietKey encrypted message",value));Toast.makeText(this,"Encrypted text copied",Toast.LENGTH_SHORT).show();}));
        }
        body.addView(card);
    }
    private void qrDialog(String title,String packet,String note) throws Exception {
        LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);view.setPadding(dp(16),dp(16),dp(16),dp(16));showInlineQr(view,packet);
        TextView caption=label(note,15,WHITE);view.addView(caption);
        new AlertDialog.Builder(this).setTitle(title).setView(view).setPositiveButton("Done",null).show();
    }
    private void showInlineQr(LinearLayout parent,String packet) throws Exception {
        BitMatrix matrix=new MultiFormatWriter().encode(packet,BarcodeFormat.QR_CODE,700,700);
        int[] pixels=new int[700*700];for(int y=0;y<700;y++)for(int x=0;x<700;x++)pixels[y*700+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;
        ImageView image=new ImageView(this);image.setImageBitmap(Bitmap.createBitmap(pixels,700,700,Bitmap.Config.ARGB_8888));image.setContentDescription("QuietKey offline pairing QR code");image.setAdjustViewBounds(true);
        parent.addView(image,new LinearLayout.LayoutParams(-1,dp(310)));
    }
    private void persist() throws Exception { try { vault.save(); } catch(Exception e) { broken=true;throw new SecurityException("Could not save securely. Close and reopen the app before continuing.",e); } }
    private <T> void work(Job<T> job,Success<T> success) {
        if(busy || broken){if(broken)error("Close and reopen the app to reload the secure vault.");return;}
        busy=true;if(root!=null)setEnabled(root,false);if(progress!=null)progress.setText("Working…");
        worker.execute(()->{
            try { T result=job.run();ui.post(()->{busy=false;if(isDestroyed())return;setEnabled(root,true);progress.setText("OFFLINE · KEYS PROTECTED ON THIS DEVICE");try{success.run(result);}catch(Exception e){error(e.getMessage());}}); }
            catch(Exception e){ui.post(()->{busy=false;if(isDestroyed())return;setEnabled(root,true);progress.setText("Action could not be completed");error(e instanceof java.io.EOFException?"Incomplete message or QR":e.getMessage());});}
        });
    }
    private void setEnabled(View view,boolean enabled){view.setEnabled(enabled);if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)setEnabled(group.getChildAt(i),enabled);}}
    private void error(String text){new AlertDialog.Builder(this).setTitle("Could not complete action").setMessage(text==null?"Invalid or unsupported message / QR":text).setPositiveButton("OK",null).show();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(6),0,dp(6));return v;}
    private void title(String text){TextView v=label(text,25,WHITE);v.setTypeface(null,Typeface.BOLD);body.addView(v);}
    private void note(String text){body.addView(label(text,15,MUTED));}
    private LinearLayout card(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(dp(16),dp(16),dp(16),dp(16));android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(CARD);bg.setCornerRadius(dp(18));v.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(16),0,dp(8));v.setLayoutParams(p);return v;}
    private Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setTextColor(GREEN);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    private EditText editor(String hint,boolean multiline){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(MUTED);e.setTextColor(WHITE);e.setTextSize(17);e.setGravity(Gravity.TOP);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));e.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);return e;}
    private String pretty(String value){StringBuilder b=new StringBuilder();for(int i=0;i<value.length();i+=8){if(i>0)b.append(' ');b.append(value,i,Math.min(i+8,value.length()));}return b.toString();}
    @Override protected void onResume(){super.onResume();foreground=true;}
    @Override protected void onPause(){foreground=false;super.onPause();if(message!=null)message.setText("");if(output!=null){output.setText("");if(output.getParent() instanceof View)body.removeView((View)output.getParent());output=null;}}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
