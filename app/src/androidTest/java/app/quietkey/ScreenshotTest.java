package app.quietkey;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/**
 * README screenshots. Opt-in and emulator-only (-e enigmaScreenshots true): seeds a fresh,
 * disposable installation with demo members and genuinely encrypted demo messages, then renders
 * real screens to PNG inside the app (FLAG_SECURE blocks ordinary screen capture).
 * Output: /sdcard/Android/data/<package>/files/screenshots/.
 */
@RunWith(AndroidJUnit4.class)
public final class ScreenshotTest {
    private static final String PASS="Screenshot demo passphrase";
    private static final long MIN=60000;

    @Test public void readmeScreens()throws Exception{
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("enigmaScreenshots")));
        Assume.assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Needs a fresh installation",new PasswordLock(context).configured());
        seed(context);
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            waitFor(s,"Unlock Enigma");shot(s,"01-unlock");
            s.onActivity(a->editor(a.getWindow().getDecorView()).setText(PASS));click(s,"Unlock");waitFor(s,"Receive");shot(s,"02-messages");
            clickCard(s,"Sam");waitFor(s,"Back to messages");shot(s,"03-chat");
            clickCard(s,"beach.jpg");waitFor(s,"Back to chat");shot(s,"04-photo");
            click(s,"Back to chat");clickCard(s,"Message");waitFor(s,"Back to chat");shot(s,"05-message");
            click(s,"Messages");click(s,"Send");waitFor(s,"1 · MESSAGE OR FILE");
            s.onActivity(a->{editor(a.getWindow().getDecorView()).setText("The new Wi-Fi password is on the fridge 🙂");((CheckBox)((View)findPrefix(a.getWindow().getDecorView(),"Sam ·"))).setChecked(true);});Thread.sleep(300);shot(s,"06-send");
            click(s,"Messages");click(s,"Receive");waitFor(s,"Open .enigma file");shot(s,"07-receive");
            click(s,"Groups");waitFor(s,"Create a group");shot(s,"08-groups");
            click(s,"Join group");Thread.sleep(500);shot(s,"09-join");
        }
    }

    private static final Entropy ENTROPY=new Entropy();
    private static final class Demo {
        final Identity id=Identity.generate(ENTROPY);final KeyPools pools=new KeyPools();final String fp=id.fingerprint();
    }
    private void seed(Context context)throws Exception{
        ENTROPY.addCamera(MessageDigest.getInstance("SHA-256").digest("screenshot demo".getBytes(StandardCharsets.UTF_8)));
        byte[] key=new PasswordLock(context).create(PASS.toCharArray());Vault v;try{v=new Vault(context,key);}finally{Arrays.fill(key,(byte)0);}
        v.deviceName="Alex's phone";v.setupCompleted=true;v.setupCapturedAt=System.currentTimeMillis();v.identity=Identity.generate(ENTROPY);
        Demo sam=new Demo(),jordan=new Demo();
        Protocol.Group family=JoinProtocol.create("Family",v.deviceName,v.identity,v.pools,ENTROPY),work=JoinProtocol.create("Work",v.deviceName,v.identity,v.pools,ENTROPY);
        v.groups.add(family);v.groups.add(work);v.personalGroupId=family.id;
        member(v,family,sam,"Sam");member(v,family,jordan,"Jordan");member(v,work,jordan,"Jordan");
        long now=System.currentTimeMillis();
        file(context,v,family,sam,Inbox.TEXT_NAME,Inbox.TEXT_MIME,"Gate code for Saturday is 4471. See you at 6!".getBytes(StandardCharsets.UTF_8),false,now-95*MIN);
        file(context,v,family,sam,Inbox.TEXT_NAME,Inbox.TEXT_MIME,"Perfect, thanks. Sending the signed lease now.".getBytes(StandardCharsets.UTF_8),true,now-90*MIN);
        file(context,v,family,sam,"Lease 2026 signed.pdf","application/pdf","%PDF-1.4 demo".getBytes(StandardCharsets.US_ASCII),true,now-89*MIN);
        file(context,v,family,sam,"beach.jpg","image/jpeg",photo(),false,now-12*MIN);
        file(context,v,work,jordan,"Q3 board pack.pdf","application/pdf","%PDF-1.4 demo".getBytes(StandardCharsets.US_ASCII),false,now-180*MIN);
        file(context,v,family,jordan,Inbox.TEXT_NAME,Inbox.TEXT_MIME,"Passport scan is in the safe, not the drawer.".getBytes(StandardCharsets.UTF_8),false,now-30*MIN);
        v.save();v.close();
    }
    /** What joining leaves behind: both sides hold the other's backup key and a pool of one-time keys. */
    private static void member(Vault v,Protocol.Group g,Demo m,String name)throws Exception{
        long now=System.currentTimeMillis();
        v.pools.setTheirBackup(m.fp,m.id.publicKey,m.pools.backup(m.id,ENTROPY,now));v.pools.addTheirs(m.fp,m.id.publicKey,m.pools.issue(m.id,ENTROPY,v.identity.fingerprint(),KeyPools.POOL,now));
        m.pools.setTheirBackup(v.identity.fingerprint(),v.identity.publicKey,v.pools.backup(v.identity,ENTROPY,now));m.pools.addTheirs(v.identity.fingerprint(),v.identity.publicKey,v.pools.issue(v.identity,ENTROPY,m.fp,KeyPools.POOL,now));
        g.members.put(m.fp,Protocol.b64(m.id.publicKey.encode()));g.memberNames.put(m.fp,name);g.memberStatus.put(m.fp,"Joined");JoinProtocol.signRoster(g,v.identity,v.pools,ENTROPY,Collections.emptyMap());
    }
    /** A real ENG4 package between this device and a demo member, opened and stored as the app does. */
    private static void file(Context context,Vault v,Protocol.Group g,Demo m,String name,String mime,byte[] body,boolean outgoing,long created)throws Exception{
        Identity from=outgoing?v.identity:m.id;KeyPools fromPools=outgoing?v.pools:m.pools;Identity.Public to=outgoing?m.id.publicKey:v.identity.publicKey;String toId=to.fingerprint();
        byte[] packet=SealedProtocol.seal(g,from,fromPools.backup(from,ENTROPY,created),Collections.singletonList(new SealedProtocol.Recipient(to,fromPools.theirBackup(toId),fromPools.take(toId),null)),new SealedProtocol.Content(name,mime,body,0,false),new byte[0],ENTROPY);
        String id=SealedProtocol.idOf(packet),plain=UUID.randomUUID()+".plain",packetName="";
        if(outgoing){File packages=new File(context.getFilesDir(),"packages");assertTrue(packages.isDirectory()||packages.mkdirs());packetName=id+".enigma";Files.write(new File(packages,packetName).toPath(),packet);}
        else{SealedProtocol.Opened o=SealedProtocol.open(g,v.identity,v.pools,packet);SealedProtocol.accept(g,v.pools,o);assertArrayEquals(body,o.body);}
        File history=new File(context.getFilesDir(),"history");assertTrue(history.isDirectory()||history.mkdirs());byte[] key=v.historyKey(plain),nonce=Protocol.random(12);
        ByteArrayOutputStream stored=new ByteArrayOutputStream();stored.write(nonce);stored.write(Protocol.aes(javax.crypto.Cipher.ENCRYPT_MODE,key,nonce,plain.getBytes(StandardCharsets.UTF_8),body));Files.write(new File(history,plain).toPath(),stored.toByteArray());
        v.history.add(new MessageRecord(outgoing?id+"/"+toId:id,g.id,from.fingerprint(),toId,"file",plain,name,mime,packetName,created,0,outgoing,false));
    }
    /** A drawn stand-in for a holiday photo. */
    private static byte[] photo(){
        Bitmap b=Bitmap.createBitmap(1200,900,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0,0,0,560,0xff2b6cb0,0xfff6ad55,Shader.TileMode.CLAMP));c.drawRect(0,0,1200,560,p);p.setShader(null);
        p.setColor(0xffffe08a);c.drawCircle(860,430,110,p);
        p.setShader(new LinearGradient(0,560,0,700,0xff2c7a7b,0xff81e6d9,Shader.TileMode.CLAMP));c.drawRect(0,540,1200,700,p);p.setShader(null);
        p.setColor(0xfff6e0b5);Path sand=new Path();sand.moveTo(0,700);sand.quadTo(600,620,1200,690);sand.lineTo(1200,900);sand.lineTo(0,900);sand.close();c.drawPath(sand,p);
        p.setColor(0xff5a3e2b);c.drawRect(200,520,222,760,p);p.setColor(0xff2f855a);for(int i=0;i<5;i++){Path leaf=new Path();leaf.moveTo(211,520);leaf.quadTo(211+(i-2)*90,430,211+(i-2)*150,540);leaf.quadTo(211+(i-2)*80,470,211,520);c.drawPath(leaf,p);}
        ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,88,out);b.recycle();return out.toByteArray();
    }

    // ---- rendering and UI helpers ----
    private void shot(ActivityScenario<MainActivity> s,String name)throws Exception{
        Thread.sleep(400);
        s.onActivity(a->{
            View view=a.getWindow().getDecorView();
            try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("transientDialog");f.setAccessible(true);android.app.Dialog d=(android.app.Dialog)f.get(a);
                if(d!=null&&d.isShowing()){View back=view;Bitmap full=render(back);View dv=d.getWindow().getDecorView();Bitmap dialog=render(dv);
                    Canvas c=new Canvas(full);c.drawColor(0x99000000);c.drawBitmap(dialog,(full.getWidth()-dialog.getWidth())/2f,(full.getHeight()-dialog.getHeight())/2f,null);dialog.recycle();save(a,name,full);return;}
            }catch(Exception e){throw new AssertionError(e);}
            save(a,name,render(view));
        });
    }
    private static Bitmap render(View v){Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));return b;}
    private static void save(Context a,String name,Bitmap b){
        File dir=new File(a.getExternalFilesDir(null),"screenshots");assertTrue(dir.isDirectory()||dir.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}catch(IOException e){throw new AssertionError(e);}finally{b.recycle();}
    }
    private static TextView find(View view,String text){
        if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return (TextView)view;
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){TextView f=find(g.getChildAt(i),text);if(f!=null)return f;}}return null;
    }
    private static EditText editor(View view){
        if(view instanceof EditText)return (EditText)view;
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){EditText f=editor(g.getChildAt(i));if(f!=null)return f;}}return null;
    }
    private static TextView findPrefix(View view,String prefix){
        if(view instanceof TextView&&((TextView)view).getText().toString().startsWith(prefix))return (TextView)view;
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){TextView f=findPrefix(g.getChildAt(i),prefix);if(f!=null)return f;}}return null;
    }
    private static void spinners(View view,List<Spinner> found){if(view instanceof Spinner)found.add((Spinner)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)spinners(((ViewGroup)view).getChildAt(i),found);}
    private static void waitFor(ActivityScenario<MainActivity> s,String text)throws Exception{
        long until=System.currentTimeMillis()+30000;
        while(System.currentTimeMillis()<until){AtomicBoolean ok=new AtomicBoolean();s.onActivity(a->{TextView v=find(a.getWindow().getDecorView(),text);ok.set(v!=null&&v.isShown());});if(ok.get())return;Thread.sleep(200);}
        fail("Screen did not show "+text);
    }
    private static void click(ActivityScenario<MainActivity> s,String text){s.onActivity(a->{TextView v=find(a.getWindow().getDecorView(),text);if(v==null){try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("transientDialog");f.setAccessible(true);android.app.Dialog d=(android.app.Dialog)f.get(a);if(d!=null)v=find(d.getWindow().getDecorView(),text);}catch(Exception e){throw new AssertionError(e);}}assertNotNull(text,v);assertTrue(text,v.performClick());});}
    private static void clickCard(ActivityScenario<MainActivity> s,String text){s.onActivity(a->{TextView v=find(a.getWindow().getDecorView(),text);assertNotNull(text,v);assertTrue(text,((View)v.getParent()).performClick());});}
}
