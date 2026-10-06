package app.quietkey;

import android.app.AlertDialog;
import android.content.*;
import android.os.Build;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.*;
import java.lang.reflect.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Actual receive UI, encrypted vault and Keystore identity on the disposable emulator. */
public final class ReceiveUiTest {
    private Context target;private KeyPair owner;private Protocol.Group remote;private String localId;
    @Before public void setup()throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("enigmaUiTest")));
        Assume.assumeTrue(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk_gphone"));
        target=InstrumentationRegistry.getInstrumentation().getTargetContext();KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);owner=gen.generateKeyPair();
        try(Vault vault=vault()){
            localId=Protocol.fingerprint(vault.publicKey());remote=Protocol.create("Receive test "+System.nanoTime(),Protocol.random(32),owner.getPublic());
            String request=PairingProtocol.request(vault.publicKey(),vault.privateKey(),vault.deviceName);String grant=PairingProtocol.invite(remote,PairingProtocol.parseRequest(request),owner.getPublic(),owner.getPrivate());
            vault.groups.add(PairingProtocol.accept(grant,request,vault.publicKey(),vault.privateKey()));vault.save();
        }
    }
    private Vault vault()throws Exception{byte[] key=new PasswordLock(target).unlock(EnigmaUiTest.PASS.toCharArray());try{return new Vault(target,key);}finally{Arrays.fill(key,(byte)0);}}
    private static TextView find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=find(((ViewGroup)v).getChildAt(i),text);if(t!=null)return t;}return null;}
    private static EditText edit(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText e=edit(((ViewGroup)v).getChildAt(i));if(e!=null)return e;}return null;}
    private static Object field(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    private static void click(View v,String text){TextView button=find(v,text);assertNotNull(text,button);assertTrue(button.isEnabled());assertTrue(button.performClick());}
    private void waitFor(ActivityScenario<MainActivity> scene,String text)throws Exception{long end=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<end){AtomicBoolean found=new AtomicBoolean();scene.onActivity(a->found.set(find(a.getWindow().getDecorView(),text)!=null));if(found.get())return;Thread.sleep(100);}fail("Missing "+text);}
    private void unlock(ActivityScenario<MainActivity> scene)throws Exception{waitFor(scene,"Unlock");scene.onActivity(a->{edit(a.getWindow().getDecorView()).setText(EnigmaUiTest.PASS);click(a.getWindow().getDecorView(),"Unlock");});waitFor(scene,"Home");}
    private String wire(String text)throws Exception{return DirectProtocol.encrypt(remote,localId,text,owner.getPublic(),owner.getPrivate());}
    private void wrongGroup(ActivityScenario<MainActivity> scene){scene.onActivity(a->{click(a.getWindow().getDecorView(),"Groups");click(a.getWindow().getDecorView(),"Starter group");assertNotEquals(remote.id,((Protocol.Group)field(a,"selected")).id);});}
    @Test public void pasteAndOpenRoutesFromWrongGroupAndDurablyRecordsReplay()throws Exception {
        String packet=wire("Hello from owner"),wrapped=packet.replaceAll("(.{65})","$1\n");
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){unlock(scene);wrongGroup(scene);
            scene.onActivity(a->{click(a.getWindow().getDecorView(),"Messages");click(a.getWindow().getDecorView(),"Open received text");assertEquals(0,countSpinners(a.getWindow().getDecorView()));((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Encrypted",wrapped));click(a.getWindow().getDecorView(),"Paste and open");});
            waitFor(scene,"DECRYPTED • SIGNATURE VERIFIED");scene.onActivity(a->{assertEquals(remote.id,((Protocol.Group)field(a,"selected")).id);assertTrue(((TextView)field(a,"output")).getText().toString().endsWith("Hello from owner"));View view=a.getWindow().getDecorView();android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));try(OutputStream out=new FileOutputStream(new File(a.getExternalFilesDir(null),"receive-message.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new AssertionError(e);}finally{bitmap.recycle();}});
            scene.recreate();unlock(scene);
            scene.onActivity(a->{edit(a.getWindow().getDecorView()).setText(packet);click(a.getWindow().getDecorView(),"Open pasted message");});
            long end=System.currentTimeMillis()+10000;AtomicBoolean rejected=new AtomicBoolean();while(System.currentTimeMillis()<end){scene.onActivity(a->{AlertDialog d=(AlertDialog)field(a,"transientDialog");rejected.set(d!=null&&d.isShowing()&&find(d.getWindow().getDecorView(),"This message has already been decrypted")!=null);});if(rejected.get())break;Thread.sleep(100);}assertTrue(rejected.get());
        }
        try(Vault v=vault()){assertEquals(1,v.history.stream().filter(r->r.group.equals(remote.id)).count());}
    }
    private static int countSpinners(View v){int count=v instanceof Spinner?1:0;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)count+=countSpinners(((ViewGroup)v).getChildAt(i));return count;}
    @Test public void shareIntentWaitsForUnlockThenVerifiesWithActualKeystore()throws Exception {
        String packet=wire("Shared directly to Enigma");Intent intent=new Intent(target,MainActivity.class).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,packet);
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(intent)){waitFor(scene,"Unlock");try(Vault v=vault()){assertEquals(0,v.history.stream().filter(r->r.group.equals(remote.id)).count());}scene.recreate();unlock(scene);waitFor(scene,"DECRYPTED • SIGNATURE VERIFIED");scene.onActivity(a->assertTrue(((TextView)field(a,"output")).getText().toString().endsWith("Shared directly to Enigma")));scene.recreate();unlock(scene);scene.onActivity(a->assertNull(field(a,"transientDialog")));}
        try(Vault v=vault()){assertEquals(1,v.history.stream().filter(r->r.group.equals(remote.id)).count());}
    }
    @Test public void receivedFileUsesItsGroupDespiteStarterGroupSelection()throws Exception {
        File file=File.createTempFile("receive-",".enigma",target.getCacheDir());byte[] original=Protocol.random(200000);
        try(OutputStream out=new FileOutputStream(file)){FileProtocol.encrypt(remote,localId,new ByteArrayInputStream(original),out,"received.bin","application/octet-stream",owner.getPublic(),owner.getPrivate());}
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){unlock(scene);wrongGroup(scene);scene.onActivity(a->{try{Field action=MainActivity.class.getDeclaredField("fileAction");action.setAccessible(true);action.set(a,"decrypt");Field page=MainActivity.class.getDeclaredField("page");page.setAccessible(true);page.set(a,"files");Method picked=MainActivity.class.getDeclaredMethod("pickedFile",android.net.Uri.class);picked.setAccessible(true);picked.invoke(a,android.net.Uri.fromFile(file));}catch(Exception e){throw new AssertionError(e);}});
            waitFor(scene,"Save restored file");scene.onActivity(a->{assertEquals(remote.id,((Protocol.Group)field(a,"selected")).id);try{ByteArrayOutputStream restored=new ByteArrayOutputStream();FileProtocol.restore((FileProtocol.Verified)field(a,"verifiedFile"),restored);assertArrayEquals(original,restored.toByteArray());}catch(Exception e){throw new AssertionError(e);}});
        }finally{file.delete();}
    }
}
