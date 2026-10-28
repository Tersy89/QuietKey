package app.quietkey;

import android.Manifest;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Opt-in smoke test for the disposable EnigmaTest emulator, never a user's phone vault. */
@RunWith(AndroidJUnit4.class)
public final class EnigmaUiTest {
    static final String PASS="Emulator passphrase 04";
    private static Vault openVault(Context context)throws Exception {byte[] key=new PasswordLock(context).unlock(PASS.toCharArray());try{return new Vault(context,key);}finally{Arrays.fill(key,(byte)0);}}
    private void editors(View v,List<EditText> result){if(v instanceof EditText)result.add((EditText)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)editors(((ViewGroup)v).getChildAt(i),result);}
    private void unlock(ActivityScenario<MainActivity> scenario)throws Exception{waitFor(scenario,"Unlock Enigma");scenario.onActivity(a->editor(a.getWindow().getDecorView()).setText(PASS));click(scenario,"Unlock");waitFor(scenario,"Device");}
    private void authenticateDialog(ActivityScenario<MainActivity> scenario)throws Exception{scenario.onActivity(a->{try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("transientDialog");field.setAccessible(true);android.app.AlertDialog dialog=(android.app.AlertDialog)field.get(a);EditText pass=editor(dialog.getWindow().getDecorView());assertNotNull("Passphrase dialog is showing: "+dialog.isShowing(),pass);pass.setText(PASS);assertEquals("Unlock",dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).getText().toString());dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();}catch(ReflectiveOperationException e){throw new AssertionError(e);}});Thread.sleep(1200);}
    private void authenticateDialog()throws Exception{AccessibilityNodeInfo edit=null;long until=System.currentTimeMillis()+5000;while(edit==null&&System.currentTimeMillis()<until){edit=node(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),"",true);if(edit==null)Thread.sleep(200);}assertNotNull("Authentication password",edit);Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,PASS);assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));assertTrue(systemClick("Unlock"));Thread.sleep(1200);}

    private TextView find(View view,String text) {
        if(view instanceof TextView && ((TextView)view).getText().toString().equals(text))return (TextView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=find(group.getChildAt(i),text);if(found!=null)return found;}}
        return null;
    }
    private EditText editor(View view) {
        if(view instanceof EditText)return (EditText)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText found=editor(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private void spinners(View view,List<Spinner> found){if(view instanceof Spinner)found.add((Spinner)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)spinners(((ViewGroup)view).getChildAt(i),found);}
    private void waitFor(ActivityScenario<MainActivity> scenario,String text)throws Exception {
        long until=System.currentTimeMillis()+25000;
        while(System.currentTimeMillis()<until){AtomicBoolean found=new AtomicBoolean();scenario.onActivity(a->{TextView view=find(a.getWindow().getDecorView(),text);found.set(a.hasWindowFocus()&&view!=null&&view.isShown()&&view.isEnabled());});if(found.get())return;Thread.sleep(200);}
        preview(scenario,"failed");StringBuilder active=new StringBuilder();describe(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),active);fail("Screen did not show "+text+"; active window: "+active);
    }
    private void click(ActivityScenario<MainActivity> scenario,String text) {
        scenario.onActivity(a->{TextView view=find(a.getWindow().getDecorView(),text);assertNotNull(text,view);assertTrue("Main window must be reachable: "+text,a.hasWindowFocus());assertTrue(text,view.isEnabled());assertTrue(view.performClick());});
    }
    private void preview(ActivityScenario<MainActivity> scenario,String name)throws Exception {
        Thread.sleep(250);
        scenario.onActivity(a->{
            View view=a.getWindow().getDecorView();try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("transientDialog");field.setAccessible(true);android.app.Dialog dialog=(android.app.Dialog)field.get(a);if(dialog!=null&&dialog.isShowing())view=dialog.getWindow().getDecorView();}catch(Exception e){throw new AssertionError(e);}Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
            try(FileOutputStream out=new FileOutputStream(new File(a.getCacheDir(),"preview-"+name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}catch(IOException e){throw new AssertionError(e);}finally{bitmap.recycle();}
            assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);
        });
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File source=new File(context.getCacheDir(),"preview-"+name+".png");
        try(InputStream in=new FileInputStream(source)){download("enigma-ui-"+name+"-"+System.currentTimeMillis()+".png","image/png",in);}
    }
    private void download(String name,String mime,InputStream source)throws Exception {
        assertTrue(Build.VERSION.SDK_INT>=29);
        if(Build.VERSION.SDK_INT>=29){
            ContentResolver resolver=InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver();
            ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,name);values.put(MediaStore.Downloads.MIME_TYPE,mime);values.put(MediaStore.Downloads.RELATIVE_PATH,"Download/");
            Uri uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);assertNotNull(uri);
            try(OutputStream out=resolver.openOutputStream(uri)){assertNotNull(out);byte[] buffer=new byte[4096];int n;while((n=source.read(buffer))!=-1)out.write(buffer,0,n);}
        }
    }
    private byte[] shell(String command)throws Exception {
        try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();
        }
    }
    private AccessibilityNodeInfo node(AccessibilityNodeInfo root,String text,boolean editable) {
        if(root==null)return null;
        if(editable?root.isEditable():text.equalsIgnoreCase(String.valueOf(root.getText())) || text.equalsIgnoreCase(String.valueOf(root.getContentDescription())))return root;
        for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo found=node(root.getChild(i),text,editable);if(found!=null)return found;}return null;
    }
    private void waitForSystem(String text)throws Exception{
        long until=System.currentTimeMillis()+10000;
        while(System.currentTimeMillis()<until){AccessibilityNodeInfo found=node(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),text,false);if(found!=null&&found.isVisibleToUser())return;Thread.sleep(150);}
        fail("Active system window did not show "+text);
    }
    private boolean systemClick(String text)throws Exception {
        long until=System.currentTimeMillis()+5000;
        while(System.currentTimeMillis()<until){AccessibilityNodeInfo found=node(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),text,false);
            AccessibilityNodeInfo original=found;
            for(int i=0;found!=null && i<4;i++,found=found.getParent())if(found.isClickable() && found.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(400);return true;}
            // DocumentsUI handles document rows through its RecyclerView touch listener.
            if(original!=null && original.isVisibleToUser()){Rect bounds=new Rect();original.getBoundsInScreen(bounds);if(!bounds.isEmpty()){shell("input tap "+bounds.centerX()+" "+bounds.centerY());Thread.sleep(500);return true;}}
            Thread.sleep(150);
        }return false;
    }
    private void chooseDocument(String filename)throws Exception {
        if(systemClick(filename))return;
        assertTrue("File-picker roots",systemClick("Show roots"));assertTrue("Downloads root",systemClick("Downloads"));
        if(systemClick(filename))return;
        StringBuilder tree=new StringBuilder();describe(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),tree);
        fail("Document "+filename+"; picker: "+tree);
    }
    private void describe(AccessibilityNodeInfo root,StringBuilder text) {
        if(root==null)return;text.append('[').append(root.getText()).append('|').append(root.getContentDescription()).append('|').append(root.getViewIdResourceName()).append('|').append(root.isScrollable()).append(']');
        for(int i=0;i<root.getChildCount();i++)describe(root.getChild(i),text);
    }
    private void saveDocument(String filename)throws Exception {
        AccessibilityNodeInfo edit=null;long until=System.currentTimeMillis()+10000;while(edit==null&&System.currentTimeMillis()<until){edit=node(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),"",true);if(edit==null)Thread.sleep(200);}assertNotNull("Save filename",edit);
        Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,filename);assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));
        assertTrue("Save document",systemClick("Save"));
    }
    private void clickCard(ActivityScenario<MainActivity> scenario,String text){scenario.onActivity(a->{TextView view=find(a.getWindow().getDecorView(),text);assertNotNull(text,view);assertTrue(text,((View)view.getParent()).performClick());});}
    /** Sharing leaves Enigma, which locks it; dismiss the share sheet and unlock again. */
    private void leaveShareSheet(ActivityScenario<MainActivity> scenario)throws Exception{Thread.sleep(1500);shell("input keyevent 4");unlock(scenario);}
    private void filesThroughSystemPicker(ActivityScenario<MainActivity> scenario)throws Exception {
        String suffix=Long.toString(System.currentTimeMillis()),input="enigma-source-"+suffix+".bin",encrypted="enigma-package-"+suffix+".enigma",restored="enigma-restored-"+suffix+".bin";
        byte[] original=Protocol.random(200000);
        download(input,"application/octet-stream",new ByteArrayInputStream(original));
        click(scenario,"Send");waitFor(scenario,"1 · MESSAGE OR FILE");
        click(scenario,"Or choose a file or photo");chooseDocument(input);waitFor(scenario,"Remove file");
        scenario.onActivity(a->{List<Spinner> pickers=new ArrayList<>();spinners(a.getWindow().getDecorView(),pickers);pickers.get(1).setSelection(1);});Thread.sleep(200);
        click(scenario,"Encrypt & share");leaveShareSheet(scenario);waitFor(scenario,input);
        clickCard(scenario,input);waitFor(scenario,"Save .enigma file (for USB)");
        click(scenario,"Save .enigma file (for USB)");saveDocument(encrypted);waitFor(scenario,"Share again");
        click(scenario,"Messages");click(scenario,"Receive");click(scenario,"Open .enigma file");chooseDocument(encrypted);waitFor(scenario,"Save to device");
        click(scenario,"Save to device");authenticateDialog();saveDocument(restored);waitFor(scenario,"Back to chat");Thread.sleep(500);
        assertArrayEquals(original,shell("cat /sdcard/Download/"+restored));
    }
    @Test public void setupCameraAndNavigation()throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("enigmaUiTest")));
        Assume.assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"));
        String pkg=InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(pkg,Manifest.permission.CAMERA);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitFor(scenario,"Protect Enigma");preview(scenario,"password");scenario.onActivity(a->{List<EditText> fields=new ArrayList<>();editors(a.getWindow().getDecorView(),fields);for(EditText f:fields)f.setText(PASS);TextView reveal=find(a.getWindow().getDecorView(),"Show new passphrase");assertNotNull(reveal);reveal.performClick();assertNull(fields.get(0).getTransformationMethod());assertEquals(PASS,fields.get(0).getText().toString());reveal.performClick();assertNotNull(fields.get(0).getTransformationMethod());});click(scenario,"Protect my app");waitFor(scenario,"Set up your device");preview(scenario,"setup");
            scenario.onActivity(a->editor(a.getWindow().getDecorView()).setText("Emulator test"));
            click(scenario,"Set up with live camera");waitFor(scenario,"Receive");
            click(scenario,"Send");waitFor(scenario,"No one else is in this group yet. Invite someone from Groups first.");
            scenario.onActivity(a->assertFalse(find(a.getWindow().getDecorView(),"Encrypt & share").isEnabled()));
            // A genuine second RSA identity is registered only in this disposable emulator vault.
            Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();Vault paired=openVault(target);KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair friend=generator.generateKeyPair();String friendId=Protocol.fingerprint(friend.getPublic());
            paired.groups.get(0).members.put(friendId,Protocol.b64(friend.getPublic().getEncoded()));paired.groups.get(0).memberNames.put(friendId,"Test recipient");PairingProtocol.signRoster(paired.groups.get(0),paired.publicKey(),paired.privateKey());paired.save();scenario.recreate();unlock(scenario);waitFor(scenario,"1 · MESSAGE OR FILE");
            scenario.onActivity(a->{assertFalse(find(a.getWindow().getDecorView(),"Encrypt & share").isEnabled());List<Spinner> pickers=new ArrayList<>();spinners(a.getWindow().getDecorView(),pickers);pickers.get(1).setSelection(1);});Thread.sleep(200);preview(scenario,"send");
            scenario.onActivity(a->{List<Spinner> pickers=new ArrayList<>();spinners(a.getWindow().getDecorView(),pickers);pickers.get(2).setSelection(1);editor(a.getWindow().getDecorView()).setText("Unified history test");});Thread.sleep(100);
            click(scenario,"Send as text instead");leaveShareSheet(scenario);waitFor(scenario,"Message (sent as text)");preview(scenario,"chat");
            Vault withText=openVault(target);assertEquals(1,withText.history.size());MessageRecord text=withText.history.get(0);assertEquals(300000,text.expiresAt-text.createdAt);assertTrue(text.payload.startsWith("QKD3:"));
            click(scenario,"Receive");scenario.onActivity(a->editor(a.getWindow().getDecorView()).setText(text.payload));click(scenario,"Open pasted text");waitFor(scenario,"Back to chat");
            assertTrue(openVault(target).groups.get(0).seen.contains(text.id));
            click(scenario,"Messages");click(scenario,"Receive");scenario.onActivity(a->editor(a.getWindow().getDecorView()).setText(text.payload));click(scenario,"Open pasted text");assertTrue("External replay must be rejected",systemClick("OK"));assertEquals(1,openVault(target).history.size());
            click(scenario,"Messages");clickCard(scenario,"Test recipient");clickCard(scenario,"Message (sent as text)");waitFor(scenario,"Share again");
            click(scenario,"Messages");click(scenario,"Send");scenario.onActivity(a->editor(a.getWindow().getDecorView()).setText("Background clear test"));
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            waitFor(scenario,"Unlock Enigma");unlock(scenario);scenario.onActivity(a->assertEquals("",editor(a.getWindow().getDecorView()).getText().toString()));
            click(scenario,"Messages");waitFor(scenario,"Receive");
            filesThroughSystemPicker(scenario);
            click(scenario,"Groups");waitFor(scenario,"Groups");preview(scenario,"groups");
            click(scenario,"Starter group");click(scenario,"Manage members (2)");waitFor(scenario,"Starter group members");
            click(scenario,"Device");waitFor(scenario,"About me & my device");preview(scenario,"device");
            click(scenario,"How to use Enigma");assertTrue(systemClick("Got it"));
            click(scenario,"Reset app & delete local data");authenticateDialog();assertTrue(systemClick("Delete local app data"));assertTrue("Empty reset confirmation must preserve the app",systemClick("Keep my data"));
            Vault persisted=openVault(InstrumentationRegistry.getInstrumentation().getTargetContext());assertTrue(persisted.setupCompleted);assertEquals("Emulator test",persisted.deviceName);assertTrue(persisted.setupCapturedAt>0);assertNotNull(persisted.personalGroupId);
            assertEquals(2,persisted.history.size());assertEquals("text",persisted.history.get(0).kind);assertEquals("file",persisted.history.get(1).kind);assertEquals(300000,persisted.history.get(1).expiresAt-persisted.history.get(1).createdAt);
            click(scenario,"Messages");waitFor(scenario,"Test recipient");preview(scenario,"messages");
            click(scenario,"Device");click(scenario,"Security & unlock");click(scenario,"Lock Enigma now");waitFor(scenario,"Unlock Enigma");preview(scenario,"locked");assertThrows(SecurityException.class,()->new Vault(target));unlock(scenario);
            click(scenario,"Groups");click(scenario,"Starter group");
            click(scenario,"Invite new recipient");assertTrue(systemClick("QR fallback"));Thread.sleep(600);shell("input keyevent 4");waitFor(scenario,"Groups");
            scenario.onActivity(a->{try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("handoff");field.setAccessible(true);assertFalse("Cancelled scanner must end the handoff allowance",field.getBoolean(a));}catch(Exception e){throw new AssertionError(e);}});
            KeyPair newcomer=generator.generateKeyPair();String newRequest=PairingProtocol.request(newcomer.getPublic(),newcomer.getPrivate(),"New recipient");String starterId=persisted.groups.get(0).id;
            scenario.onActivity(a->{try{java.lang.reflect.Field action=MainActivity.class.getDeclaredField("scanAction"),groupId=MainActivity.class.getDeclaredField("scanGroupId");action.setAccessible(true);groupId.setAccessible(true);action.set(a,"invite");groupId.set(a,starterId);java.lang.reflect.Method scanned=MainActivity.class.getDeclaredMethod("scanned",String.class);scanned.setAccessible(true);scanned.invoke(a,newRequest);}catch(Exception e){throw new AssertionError(e);}});
            assertTrue("Owner approves recipient",systemClick("Invite"));Thread.sleep(700);preview(scenario,"owner-invitation");assertTrue("Encrypted invitation shown",systemClick("Done"));
            try(Vault invited=openVault(target)){String newId=Protocol.fingerprint(newcomer.getPublic());assertEquals("Invited",invited.groups.get(0).memberStatus.get(newId));assertEquals(newId,invited.pendingInvites.get(PairingProtocol.parseRequest(newRequest).id));assertEquals(3,invited.groups.get(0).members.size());}
            // Reopen an unfinished invitation after a real vault reload; the grant stays identical.
            String issued;try(Vault cache=openVault(target)){issued=cache.invitationCache.get(PairingProtocol.parseRequest(newRequest).id).packet;}
            scenario.recreate();unlock(scenario);click(scenario,"Invite new recipient");assertTrue(systemClick("Continue QR invitation: New recipient"));assertTrue(systemClick("Invite"));assertTrue(systemClick("Done"));
            try(Vault cache=openVault(target)){assertEquals(issued,cache.invitationCache.get(PairingProtocol.parseRequest(newRequest).id).packet);assertEquals(3,cache.groups.get(0).members.size());}
            click(scenario,"Manage members (3)");scenario.onActivity(a->{TextView member=find(a.getWindow().getDecorView(),"New recipient · Invited");assertNotNull(member);TextView remove=find((View)member.getParent(),"Remove local trust");assertNotNull(remove);remove.performClick();});assertTrue(systemClick("Remove"));
            try(Vault removed=openVault(target)){assertTrue(removed.invitationCache.isEmpty());assertTrue(removed.pendingInvites.isEmpty());assertTrue(removed.pairingUsed.contains(PairingProtocol.parseRequest(newRequest).id));assertEquals(2,removed.groups.get(0).members.size());}
            click(scenario,"Back to group");click(scenario,"Back to groups");click(scenario,"Join group");assertTrue(systemClick("QR fallback"));Thread.sleep(500);
            String previous;try(Vault before=openVault(target)){previous=before.joiningRequest;}
            Thread.sleep(700);preview(scenario,"joining-request");assertTrue(systemClick("More"));assertTrue(systemClick("Start again"));Thread.sleep(500);try(Vault fresh=openVault(target)){assertNotEquals(previous,fresh.joiningRequest);}
            assertTrue(systemClick("More"));assertTrue(systemClick("Owner already has my codes"));assertTrue(systemClick("Scan owner's invitation"));Thread.sleep(600);shell("input keyevent 4");waitForSystem("Show 1/2");assertTrue("Cancelled scan returns to request instructions",systemClick("Cancel"));waitFor(scenario,"Groups");
            // Exercise the real joining commit/UI with a software owner and this device's Keystore key.
            Vault requesting=openVault(target);String request=requesting.joiningRequest;
            KeyPair owner=generator.generateKeyPair(),existing=generator.generateKeyPair();Protocol.Group remote=Protocol.create("Joined group",Protocol.random(32),owner.getPublic());
            String existingId=Protocol.fingerprint(existing.getPublic());remote.members.put(existingId,Protocol.b64(existing.getPublic().getEncoded()));remote.memberNames.put(existingId,"Existing member");
            String invitation=PairingProtocol.invite(remote,PairingProtocol.parseRequest(request),owner.getPublic(),owner.getPrivate());requesting.close();
            click(scenario,"Join group");assertTrue(systemClick("QR fallback"));assertTrue(systemClick("More"));assertTrue(systemClick("Paste invitation"));
            scenario.onActivity(a->{try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("transientDialog");field.setAccessible(true);android.app.AlertDialog dialog=(android.app.AlertDialog)field.get(a);editor(dialog.getWindow().getDecorView()).setText(invitation+"\n");dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();}catch(Exception e){throw new AssertionError(e);}});
            assertTrue("Joined confirmation",systemClick("Done"));waitFor(scenario,"Groups");
            try(Vault joined=openVault(target)){assertEquals(2,joined.groups.size());Protocol.Group imported=joined.groups.stream().filter(g->g.id.equals(remote.id)).findFirst().orElseThrow(AssertionError::new);assertEquals(remote.members,imported.members);assertArrayEquals(remote.seed,imported.seed);assertEquals(2,joined.history.size());assertEquals("",joined.joiningRequest);assertTrue(joined.pairingUsed.contains(PairingProtocol.parseRequest(request).id));}
            preview(scenario,"joined");

            if("true".equals(InstrumentationRegistry.getArguments().getString("enigmaResetTest"))){
                Bundle status=new Bundle();status.putString("beforeIdentity",Protocol.fingerprint(persisted.publicKey()));InstrumentationRegistry.getInstrumentation().sendStatus(2,status);
                click(scenario,"Device");click(scenario,"Reset app & delete local data");authenticateDialog();Thread.sleep(200);
                AccessibilityNodeInfo edit=node(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),"",true);assertNotNull(edit);
                Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"RESET");assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));
                assertTrue(systemClick("Delete local app data")); // Android terminates this instrumentation with the app.
            }
        }
    }
    @Test public void biometricEnableCancelAndUnlock()throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("enigmaBiometricTest")));
        Assume.assumeTrue(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk_gphone"));
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();assertEquals(androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS,androidx.biometric.BiometricManager.from(target).canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG));
        PasswordLock lock=new PasswordLock(target);lock.disableBiometric();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            unlock(scenario);click(scenario,"Device");click(scenario,"Security & unlock");click(scenario,"Enable biometric unlock");authenticateDialog(scenario);
            Bundle stage=new Bundle();stage.putString("stream","BIO_ENABLE_READY\n");InstrumentationRegistry.getInstrumentation().sendStatus(2,stage);
            // Host responds to each READY stage with adb emu finger touch 1.
            waitFor(scenario,"Disable biometric unlock");assertTrue(lock.biometricConfigured());
            click(scenario,"Lock Enigma now");waitFor(scenario,"Unlock Enigma");click(scenario,"Unlock with biometrics");assertTrue(systemClick("Use passphrase"));waitFor(scenario,"Unlock Enigma");assertThrows(SecurityException.class,()->new Vault(target));
            assertTrue("Unlock button is reachable after the cancelled system prompt closes",systemClick("Unlock with biometrics"));stage=new Bundle();stage.putString("stream","BIO_UNLOCK_READY\n");InstrumentationRegistry.getInstrumentation().sendStatus(2,stage);waitFor(scenario,"Device");
            click(scenario,"Device");click(scenario,"Security & unlock");click(scenario,"Disable biometric unlock");authenticateDialog(scenario);waitFor(scenario,"Enable biometric unlock");assertFalse(lock.biometricConfigured());
        }
    }

}
