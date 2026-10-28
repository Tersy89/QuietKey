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
import java.security.*;
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
            s.onActivity(a->{editor(a.getWindow().getDecorView()).setText("The new Wi-Fi password is on the fridge 🙂");List<Spinner> p=new ArrayList<>();spinners(a.getWindow().getDecorView(),p);p.get(1).setSelection(1);});Thread.sleep(300);shot(s,"06-send");
            click(s,"Messages");click(s,"Receive");waitFor(s,"Open .enigma file");shot(s,"07-receive");
            click(s,"Groups");waitFor(s,"Create a group");shot(s,"08-groups");
            click(s,"Join group");Thread.sleep(500);shot(s,"09-join");
        }
    }

    private void seed(Context context)throws Exception{
        byte[] key=new PasswordLock(context).create(PASS.toCharArray());Vault v;try{v=new Vault(context,key);}finally{Arrays.fill(key,(byte)0);}
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);KeyPair sam=gen.generateKeyPair(),jordan=gen.generateKeyPair();
        String me=Protocol.fingerprint(v.publicKey()),samId=Protocol.fingerprint(sam.getPublic()),jordanId=Protocol.fingerprint(jordan.getPublic());
        v.deviceName="Alex's phone";v.setupCompleted=true;v.setupCapturedAt=System.currentTimeMillis();
        Protocol.Group family=group(v,"Family",me),work=group(v,"Work",me);v.personalGroupId=family.id;
        member(v,family,sam,"Sam");member(v,family,jordan,"Jordan");member(v,work,jordan,"Jordan");
        File dir=new File(context.getFilesDir(),"packages");assertTrue(dir.isDirectory()||dir.mkdirs());long now=System.currentTimeMillis();
        file(v,dir,family,sam,me,Inbox.TEXT_NAME,Inbox.TEXT_MIME,"Gate code for Saturday is 4471. See you at 6!".getBytes(StandardCharsets.UTF_8),false,now-95*MIN);
        file(v,dir,family,null,samId,Inbox.TEXT_NAME,Inbox.TEXT_MIME,"Perfect, thanks. Sending the signed lease now.".getBytes(StandardCharsets.UTF_8),true,now-90*MIN);
        file(v,dir,family,null,samId,"Lease 2026 signed.pdf","application/pdf","%PDF-1.4 demo".getBytes(StandardCharsets.US_ASCII),true,now-89*MIN);
        file(v,dir,family,sam,me,"beach.jpg","image/jpeg",photo(),false,now-12*MIN);
        file(v,dir,work,jordan,me,"Q3 board pack.pdf","application/pdf","%PDF-1.4 demo".getBytes(StandardCharsets.US_ASCII),false,now-180*MIN);
        file(v,dir,family,jordan,me,Inbox.TEXT_NAME,Inbox.TEXT_MIME,"Passport scan is in the safe, not the drawer.".getBytes(StandardCharsets.UTF_8),false,now-30*MIN);
        v.save();v.close();
    }
    private static Protocol.Group group(Vault v,String name,String me)throws Exception{
        Protocol.Group g=Protocol.create(name,Protocol.random(32),v.publicKey());g.memberNames.put(me,v.deviceName);PairingProtocol.signRoster(g,v.publicKey(),v.privateKey());v.groups.add(g);return g;
    }
    private static void member(Vault v,Protocol.Group g,KeyPair k,String name)throws Exception{
        String id=Protocol.fingerprint(k.getPublic());g.members.put(id,Protocol.b64(k.getPublic().getEncoded()));g.memberNames.put(id,name);g.memberStatus.put(id,"Joined");PairingProtocol.signRoster(g,v.publicKey(),v.privateKey());
    }
    /** A real file-v3 package: from a demo member to this device, or from this device (from==null) to a member. */
    private static void file(Vault v,File dir,Protocol.Group g,KeyPair from,String to,String name,String mime,byte[] body,boolean outgoing,long created)throws Exception{
        File out=new File(dir,UUID.randomUUID()+".enigma");
        try(OutputStream o=new FileOutputStream(out)){FileProtocol.encrypt(g,to,new ByteArrayInputStream(body),o,name,mime,from==null?v.publicKey():from.getPublic(),from==null?v.privateKey():from.getPrivate(),0);}
        try(FileProtocol.Verified ver=FileProtocol.inspect(g,out,v.publicKey(),v.privateKey())){v.history.add(new MessageRecord(ver.messageId,g.id,ver.sender,ver.recipient,"file",out.getName(),ver.name,created,0,outgoing));}
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
    private static void spinners(View view,List<Spinner> found){if(view instanceof Spinner)found.add((Spinner)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)spinners(((ViewGroup)view).getChildAt(i),found);}
    private static void waitFor(ActivityScenario<MainActivity> s,String text)throws Exception{
        long until=System.currentTimeMillis()+30000;
        while(System.currentTimeMillis()<until){AtomicBoolean ok=new AtomicBoolean();s.onActivity(a->{TextView v=find(a.getWindow().getDecorView(),text);ok.set(v!=null&&v.isShown());});if(ok.get())return;Thread.sleep(200);}
        fail("Screen did not show "+text);
    }
    private static void click(ActivityScenario<MainActivity> s,String text){s.onActivity(a->{TextView v=find(a.getWindow().getDecorView(),text);if(v==null){try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("transientDialog");f.setAccessible(true);android.app.Dialog d=(android.app.Dialog)f.get(a);if(d!=null)v=find(d.getWindow().getDecorView(),text);}catch(Exception e){throw new AssertionError(e);}}assertNotNull(text,v);assertTrue(text,v.performClick());});}
    private static void clickCard(ActivityScenario<MainActivity> s,String text){s.onActivity(a->{TextView v=find(a.getWindow().getDecorView(),text);assertNotNull(text,v);assertTrue(text,((View)v.getParent()).performClick());});}
}
