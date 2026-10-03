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
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Opt-in smoke test for the disposable EnigmaTest emulator, never a user's phone vault. */
@RunWith(AndroidJUnit4.class)
public final class EnigmaUiTest {
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
    private void waitFor(ActivityScenario<MainActivity> scenario,String text)throws Exception {
        long until=System.currentTimeMillis()+25000;
        while(System.currentTimeMillis()<until){AtomicBoolean found=new AtomicBoolean();scenario.onActivity(a->found.set(find(a.getWindow().getDecorView(),text)!=null));if(found.get())return;Thread.sleep(200);}
        fail("Screen did not show "+text);
    }
    private void click(ActivityScenario<MainActivity> scenario,String text) {
        scenario.onActivity(a->{TextView view=find(a.getWindow().getDecorView(),text);assertNotNull(text,view);assertTrue(view.performClick());});
    }
    private void preview(ActivityScenario<MainActivity> scenario,String name)throws Exception {
        Thread.sleep(250);
        scenario.onActivity(a->{
            View view=a.getWindow().getDecorView();Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
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
        Thread.sleep(500);AccessibilityNodeInfo edit=node(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),"",true);assertNotNull("Save filename",edit);
        Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,filename);assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));
        assertTrue("Save document",systemClick("Save"));
    }
    private void filesThroughSystemPicker(ActivityScenario<MainActivity> scenario)throws Exception {
        String suffix=Long.toString(System.currentTimeMillis()),input="enigma-source-"+suffix+".bin",encrypted="enigma-package-"+suffix+".enigma",restored="enigma-restored-"+suffix+".bin";
        byte[] original=Protocol.random(200000);
        download(input,"application/octet-stream",new ByteArrayInputStream(original));
        click(scenario,"Choose file to encrypt");chooseDocument(input);waitFor(scenario,"Save encrypted file");
        click(scenario,"Save encrypted file");saveDocument(encrypted);waitFor(scenario,"File protection");
        click(scenario,"Open .enigma file to decrypt");chooseDocument(encrypted);waitFor(scenario,"Save restored file");
        click(scenario,"Save restored file");saveDocument(restored);waitFor(scenario,"File protection");Thread.sleep(500);
        assertArrayEquals(original,shell("cat /sdcard/Download/"+restored));
    }
    @Test public void setupCameraAndNavigation()throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("enigmaUiTest")));
        Assume.assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"));
        String pkg=InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(pkg,Manifest.permission.CAMERA);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitFor(scenario,"Set up your device");preview(scenario,"setup");
            scenario.onActivity(a->editor(a.getWindow().getDecorView()).setText("Emulator test"));
            click(scenario,"Set up with live camera");waitFor(scenario,"Your data. Your keys.");preview(scenario,"home");
            click(scenario,"Text");waitFor(scenario,"Private messages");preview(scenario,"text");
            scenario.onActivity(a->editor(a.getWindow().getDecorView()).setText("Background clear test"));
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            scenario.onActivity(a->assertEquals("",editor(a.getWindow().getDecorView()).getText().toString()));
            click(scenario,"Files");waitFor(scenario,"File protection");preview(scenario,"files");
            filesThroughSystemPicker(scenario);
            click(scenario,"Groups");waitFor(scenario,"Trusted groups");preview(scenario,"groups");
            click(scenario,"Manage members (1)");waitFor(scenario,"Personal vault members");
            click(scenario,"Device");waitFor(scenario,"Device & keys");preview(scenario,"device");
            Vault persisted=new Vault(InstrumentationRegistry.getInstrumentation().getTargetContext());assertTrue(persisted.setupCompleted);assertEquals("Emulator test",persisted.deviceName);assertTrue(persisted.setupCapturedAt>0);assertNotNull(persisted.personalGroupId);
        }
    }
}
