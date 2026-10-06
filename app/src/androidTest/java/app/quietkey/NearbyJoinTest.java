package app.quietkey;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.lifecycle.Lifecycle;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Opt-in own-emulator tests: real Android TCP/UI/vault; software peer, no USB hardware claim. */
@RunWith(AndroidJUnit4.class)
public final class NearbyJoinTest {
    private Context target;
    @Before public void onlyDisposableEmulator(){Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("enigmaUiTest")));Assume.assumeTrue(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk_gphone"));target=InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private Vault vault()throws Exception{byte[] key=new PasswordLock(target).unlock(EnigmaUiTest.PASS.toCharArray());try{return new Vault(target,key);}finally{Arrays.fill(key,(byte)0);}}
    private static Object field(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    private static TextView find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=find(((ViewGroup)v).getChildAt(i),text);if(t!=null)return t;}return null;}
    private static void edits(View v,List<EditText> list){if(v instanceof EditText)list.add((EditText)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)edits(((ViewGroup)v).getChildAt(i),list);}
    private static ImageView cardImage(View v){if(v instanceof ImageView&&"Temporary hotspot connection card".contentEquals(v.getContentDescription()))return (ImageView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ImageView image=cardImage(((ViewGroup)v).getChildAt(i));if(image!=null)return image;}return null;}
    private static void click(View root,String text){TextView v=find(root,text);assertNotNull(text,v);assertTrue(text,v.isEnabled());assertTrue(v.performClick());}
    private void waitMain(ActivityScenario<MainActivity> scene,String text)throws Exception{long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){AtomicBoolean ok=new AtomicBoolean();scene.onActivity(a->ok.set(find(a.getWindow().getDecorView(),text)!=null));if(ok.get())return;Thread.sleep(100);}fail("Missing main control "+text);}
    private void unlock(ActivityScenario<MainActivity> scene)throws Exception{waitMain(scene,"Unlock");scene.onActivity(a->{List<EditText> list=new ArrayList<>();edits(a.getWindow().getDecorView(),list);list.get(0).setText(EnigmaUiTest.PASS);click(a.getWindow().getDecorView(),"Unlock");});waitMain(scene,"Home");}
    private void choose(ActivityScenario<MainActivity> scene,String name){scene.onActivity(a->{AlertDialog dialog=(AlertDialog)field(a,"transientDialog");assertTrue(dialog.isShowing());ListView list=dialog.getListView();int index=-1;for(int i=0;i<list.getAdapter().getCount();i++)if(name.equals(list.getAdapter().getItem(i)))index=i;assertTrue(name,index>=0);list.performItemClick(list.getChildAt(index),index,list.getAdapter().getItemId(index));});}
    private NearbyJoinDialog nearby(ActivityScenario<MainActivity> scene)throws Exception{NearbyJoinDialog[] result={null};long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){scene.onActivity(a->result[0]=(NearbyJoinDialog)field(a,"nearbyJoin"));if(result[0]!=null&&result[0].isShowing())return result[0];Thread.sleep(100);}throw new AssertionError("Joining window missing");}
    private void consent(ActivityScenario<MainActivity> scene,String title,String button)throws Exception{long end=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<end){AtomicBoolean done=new AtomicBoolean();scene.onActivity(a->{Dialog d=(Dialog)field(a,"nearbyConsent");if(d!=null&&d.isShowing()&&find(d.getWindow().getDecorView(),title)!=null){click(d.getWindow().getDecorView(),button);done.set(true);}});if(done.get())return;Thread.sleep(100);}fail("Consent missing: "+title);}
    private void success(ActivityScenario<MainActivity> scene,NearbyJoinDialog dialog)throws Exception{long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){AtomicBoolean done=new AtomicBoolean();scene.onActivity(a->done.set(find(dialog.getWindow().getDecorView(),"Joined successfully. Group details and all member public keys are ready.")!=null));if(done.get())return;Thread.sleep(100);}fail("Joining did not finish");}
    private void preview(ActivityScenario<MainActivity> scene,Dialog dialog,String name)throws Exception{Thread.sleep(300);scene.onActivity(a->{View view=dialog.getWindow().getDecorView();assertTrue((dialog.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);Bitmap b=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(b));try(OutputStream out=new FileOutputStream(new File(a.getExternalFilesDir(null),name+"-"+a.getResources().getConfiguration().fontScale+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}catch(IOException e){throw new AssertionError(e);}finally{b.recycle();}});}
    @Test public void preferredMethodsUsbWaitAndBackgroundCancel()throws Exception{
        int groups,history;String identity;try(Vault v=vault()){groups=v.groups.size();history=v.history.size();identity=Protocol.fingerprint(v.publicKey());}
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){unlock(scene);scene.onActivity(a->{click(a.getWindow().getDecorView(),"Groups");click(a.getWindow().getDecorView(),"Join group");AlertDialog d=(AlertDialog)field(a,"transientDialog");assertEquals("Personal hotspot (recommended)",d.getListView().getAdapter().getItem(0));assertEquals("USB cable",d.getListView().getAdapter().getItem(1));assertEquals("QR fallback",d.getListView().getAdapter().getItem(2));});choose(scene,"USB cable");NearbyJoinDialog dialog=nearby(scene);preview(scene,dialog,"joining-usb");Thread.sleep(1500);
            scene.onActivity(a->assertNotNull(find(dialog.getWindow().getDecorView(),"Connect a data cable. Open USB joining on the other phone too. Allow USB access when Android asks.")));
            scene.moveToState(Lifecycle.State.CREATED);scene.moveToState(Lifecycle.State.RESUMED);waitMain(scene,"Unlock");scene.onActivity(a->{assertNull(field(a,"nearbyJoin"));assertTrue(dialog.closed());});unlock(scene);
        }
        try(Vault v=vault()){assertEquals(groups,v.groups.size());assertEquals(history,v.history.size());assertEquals(identity,Protocol.fingerprint(v.publicKey()));}
    }
    @Test public void usbPermissionResultPreservesGrantExtrasAndCancelsOnClose()throws Exception{
        java.util.concurrent.atomic.AtomicInteger failures=new java.util.concurrent.atomic.AtomicInteger(),returned=new java.util.concurrent.atomic.AtomicInteger();
        UsbJoinLink link=new UsbJoinLink(target,new UsbJoinLink.Listener(){public void ready(InputStream in,OutputStream out){throw new AssertionError("No USB hardware is attached");}public void status(String text){}public void failure(String text){failures.incrementAndGet();}public void permissionPrompt(){}public void permissionReturned(){returned.incrementAndGet();}});
        PendingIntent intent=link.permissionIntent();
        try{intent.send(target,0,new android.content.Intent().putExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED,true));long until=System.currentTimeMillis()+3000;while(returned.get()<1&&System.currentTimeMillis()<until)Thread.sleep(50);assertEquals(1,returned.get());assertEquals("Android must preserve UsbManager's grant extras",0,failures.get());
            intent.send(target,0,new android.content.Intent().putExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED,false));until=System.currentTimeMillis()+3000;while(returned.get()<2&&System.currentTimeMillis()<until)Thread.sleep(50);assertEquals(2,returned.get());assertEquals(1,failures.get());
        }finally{link.close();}assertThrows(PendingIntent.CanceledException.class,()->intent.send());
    }
    @Test public void connectionSettingsReturnEndsHandoffAndKeepsVault()throws Exception{
        int groups,history;try(Vault v=vault()){groups=v.groups.size();history=v.history.size();}
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){unlock(scene);scene.onActivity(a->{click(a.getWindow().getDecorView(),"Groups");click(a.getWindow().getDecorView(),"Join group");});choose(scene,"Personal hotspot (recommended)");NearbyJoinDialog dialog=nearby(scene);scene.onActivity(a->click(dialog.getWindow().getDecorView(),"Open connection settings"));
            long until=System.currentTimeMillis()+5000;boolean settings=false;while(System.currentTimeMillis()<until){android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root!=null&&"com.android.settings".contentEquals(root.getPackageName())){settings=true;break;}Thread.sleep(100);}assertTrue("Real Android connection settings opened",settings);
            try(android.os.ParcelFileDescriptor ignored=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("input keyevent 4")){Thread.sleep(500);}
            until=System.currentTimeMillis()+5000;AtomicBoolean ended=new AtomicBoolean();while(System.currentTimeMillis()<until){try{scene.onActivity(a->ended.set(field(a,"nearbyJoin")!=null&&!((Boolean)field(a,"handoff"))));}catch(IllegalStateException ignored){}if(ended.get())break;Thread.sleep(100);}assertTrue("Returning from settings clears the allowance before a new join",ended.get());scene.onActivity(a->{assertNotNull(field(a,"nearbyJoin"));assertNotNull(find(a.getWindow().getDecorView(),"Home"));});
        }try(Vault v=vault()){assertEquals(groups,v.groups.size());assertEquals(history,v.history.size());}
    }
    private Protocol.Group ownerGroup()throws Exception{try(Vault v=vault()){Protocol.Group g=Protocol.create("Cable hotspot test "+System.nanoTime(),Protocol.random(32),v.publicKey());PairingProtocol.signRoster(g,v.publicKey(),v.privateKey());v.groups.add(g);v.save();return g;}}
    @Test public void addressChoiceClosesOnLockAndStaleCallbackCannotRestart()throws Exception {
        Protocol.Group group=ownerGroup();try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){unlock(scene);scene.onActivity(a->{click(a.getWindow().getDecorView(),"Groups");click(a.getWindow().getDecorView(),group.name);click(a.getWindow().getDecorView(),"Invite new recipient");});choose(scene,"Personal hotspot (recommended)");NearbyJoinDialog dialog=nearby(scene);NearbyJoinDialog.Callbacks callbacks=(NearbyJoinDialog.Callbacks)field(dialog,"callbacks");String address=NearbyJoinDialog.addresses().get(0);Dialog[] picker={null};
            scene.onActivity(a->{click(dialog.getWindow().getDecorView(),"Use another local address");picker[0]=(Dialog)field(dialog,"addressChooser");assertTrue(picker[0].isShowing());});scene.moveToState(Lifecycle.State.CREATED);scene.moveToState(Lifecycle.State.RESUMED);waitMain(scene,"Unlock");
            scene.onActivity(a->{assertFalse(picker[0].isShowing());assertTrue(dialog.closed());callbacks.address(address);callbacks.scanConnection();assertNull(field(a,"nearbyJoin"));});unlock(scene);scene.onActivity(a->{callbacks.address(address);assertNull(field(a,"nearbyJoin"));});
        }
    }
    @Test public void hotspotOwnerApprovesActualTcpAndSavesVerifiedReceipt()throws Exception{
        Protocol.Group own=ownerGroup();KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair peer=generator.generateKeyPair();String request=PairingProtocol.request(peer.getPublic(),peer.getPrivate(),"Nearby recipient");ExecutorService pool=Executors.newSingleThreadExecutor();
        try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){unlock(scene);scene.onActivity(a->{click(a.getWindow().getDecorView(),"Groups");click(a.getWindow().getDecorView(),own.name);click(a.getWindow().getDecorView(),"Invite new recipient");});choose(scene,"Personal hotspot (recommended)");NearbyJoinDialog dialog=nearby(scene);String[] code={null},address={null};scene.onActivity(a->{code[0]=(String)field(dialog,"code");try{address[0]=NearbyJoinDialog.addresses().get(0);}catch(Exception e){throw new AssertionError(e);}assertNotNull(code[0]);assertNull(find(dialog.getWindow().getDecorView(),"Wait for recipient"));});preview(scene,dialog,"joining-hotspot-owner");Thread.sleep(300);
            scene.onActivity(a->{ImageView image=cardImage(dialog.getWindow().getDecorView());assertNotNull(image);Rect visible=new Rect();assertTrue(image.getGlobalVisibleRect(visible));assertEquals(image.getWidth(),visible.width());assertEquals(image.getHeight(),visible.height());assertEquals(image.getWidth(),image.getHeight());try{Bitmap bitmap=((android.graphics.drawable.BitmapDrawable)image.getDrawable()).getBitmap();com.google.zxing.common.BitMatrix matrix=new com.google.zxing.common.BitMatrix(bitmap.getWidth(),bitmap.getHeight());for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)if((bitmap.getPixel(x,y)&0xffffff)==0)matrix.set(x,y);assertEquals(ConnectionCard.create(address[0],code[0]),QrImage.decode(matrix));}catch(Exception e){throw new AssertionError(e);}});
            Future<Protocol.Group> joined=pool.submit(()->{try(Socket socket=new Socket(address[0],NearbyJoinDialog.PORT);LocalJoinWire wire=new LocalJoinWire(socket.getInputStream(),socket.getOutputStream(),false,LocalJoinWire.codeKey(code[0]))){socket.setSoTimeout(20000);wire.send(1,request);Protocol.Group g=PairingProtocol.accept(wire.receive(2),request,peer.getPublic(),peer.getPrivate());wire.send(3,PairingProtocol.receipt(g,PairingProtocol.parseRequest(request).id,peer.getPublic(),peer.getPrivate()));assertEquals("Joined",wire.receive(4));return g;}});
            consent(scene,"Invite Nearby recipient?","Invite");Protocol.Group imported=joined.get(25,TimeUnit.SECONDS);success(scene,dialog);
            try(Vault v=vault()){Protocol.Group saved=v.groups.stream().filter(g->g.id.equals(own.id)).findFirst().orElseThrow(AssertionError::new);String id=Protocol.fingerprint(peer.getPublic());assertArrayEquals(saved.seed,imported.seed);assertEquals("Joined",saved.memberStatus.get(id));assertEquals(imported.members,saved.members);assertFalse(v.pendingInvites.containsKey(PairingProtocol.parseRequest(request).id));assertFalse(v.invitationCache.containsKey(PairingProtocol.parseRequest(request).id));}
            scene.onActivity(a->click(dialog.getWindow().getDecorView(),"Done"));
        }finally{pool.shutdownNow();}
    }
    @Test public void hotspotRecipientConfirmsAndCommitsAllKeys()throws Exception{recipient(false);}
    @Test public void hotspotRecipientDeclinePreservesVault()throws Exception{recipient(true);}
    @Test public void oneCardScanReturnsAndConnectsWithoutAnotherButton()throws Exception{recipient(false,true);}
    private void recipient(boolean decline)throws Exception{recipient(decline,false);}
    private void recipient(boolean decline,boolean scanCard)throws Exception{
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair owner=generator.generateKeyPair(),other=generator.generateKeyPair();Protocol.Group remote=Protocol.create("Hotspot import "+System.nanoTime(),Protocol.random(32),owner.getPublic());String otherId=Protocol.fingerprint(other.getPublic());remote.members.put(otherId,Protocol.b64(other.getPublic().getEncoded()));remote.memberNames.put(otherId,"Existing member");
        int groups,history;String ownId;try(Vault v=vault()){groups=v.groups.size();history=v.history.size();ownId=Protocol.fingerprint(v.publicKey());}String address=NearbyJoinDialog.addresses().get(0),code=LocalJoinWire.newCode();ExecutorService pool=Executors.newSingleThreadExecutor();Map<String,String> pending=new HashMap<>();String[] request={null};
        try(ServerSocket server=new ServerSocket(NearbyJoinDialog.PORT,1,InetAddress.getByName(address));ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)){
            Future<?> exchange=pool.submit(()->{try(Socket socket=server.accept();LocalJoinWire wire=new LocalJoinWire(socket.getInputStream(),socket.getOutputStream(),true,LocalJoinWire.codeKey(code))){socket.setSoTimeout(20000);request[0]=wire.receive(1);PairingProtocol.Request r=PairingProtocol.parseRequest(request[0]);pending.put(r.id,ownId);wire.send(2,PairingProtocol.invite(remote,r,owner.getPublic(),owner.getPrivate()));if(decline){try{wire.receive(3);fail("Declined join must not return a receipt");}catch(EOFException expected){}}else{assertEquals(ownId,PairingProtocol.receiptMember(remote,wire.receive(3),pending));wire.send(4,"Joined");}}catch(Exception e){throw new CompletionException(e);}});
            unlock(scene);scene.onActivity(a->{click(a.getWindow().getDecorView(),"Groups");click(a.getWindow().getDecorView(),"Join group");});choose(scene,"Personal hotspot (recommended)");NearbyJoinDialog initial=nearby(scene);
            android.app.Instrumentation.ActivityMonitor monitor=scanCard?InstrumentationRegistry.getInstrumentation().addMonitor(JoinScanActivity.class.getName(),null,false):null;
            scene.onActivity(a->{List<EditText> fields=new ArrayList<>();edits(initial.getWindow().getDecorView(),fields);assertEquals(2,fields.size());if(scanCard)click(initial.getWindow().getDecorView(),"Scan owner's connection card");else if(decline){fields.get(0).setText(address);fields.get(1).setText(code);click(initial.getWindow().getDecorView(),"Connect to owner");}else{try{((android.content.ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Connection",ConnectionCard.create(address,code)));click(initial.getWindow().getDecorView(),"Paste connection card");}catch(Exception e){throw new AssertionError(e);}}});
            if(scanCard){Activity scanner=InstrumentationRegistry.getInstrumentation().waitForMonitorWithTimeout(monitor,5000);assertTrue(scanner instanceof JoinScanActivity);String card=ConnectionCard.create(address,code);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->((JoinScanActivity)scanner).acceptFrame(card));InstrumentationRegistry.getInstrumentation().removeMonitor(monitor);}
            NearbyJoinDialog dialog=scanCard?nearby(scene):initial;
            consent(scene,"Join "+remote.name+"?",decline?"Decline":"Join group");exchange.get(25,TimeUnit.SECONDS);
            if(!decline)success(scene,dialog);preview(scene,dialog,decline?"joining-hotspot-declined":"joining-hotspot-complete");
            try(Vault v=vault()){assertEquals(history,v.history.size());assertEquals(groups+(decline?0:1),v.groups.size());assertEquals(ownId,Protocol.fingerprint(v.publicKey()));if(!decline){Protocol.Group saved=v.groups.stream().filter(g->g.id.equals(remote.id)).findFirst().orElseThrow(AssertionError::new);assertEquals(remote.members,saved.members);assertArrayEquals(remote.seed,saved.seed);assertEquals("",v.joiningRequest);assertTrue(v.pairingUsed.contains(PairingProtocol.parseRequest(request[0]).id));}else assertFalse(v.pairingUsed.contains(PairingProtocol.parseRequest(request[0]).id));}
            if(!decline){String packet=DirectProtocol.encrypt(remote,ownId,"First message after hotspot joining",owner.getPublic(),owner.getPrivate());
                scene.onActivity(a->{click(dialog.getWindow().getDecorView(),"Done");click(a.getWindow().getDecorView(),"Groups");click(a.getWindow().getDecorView(),"Starter group");assertNotEquals(remote.id,((Protocol.Group)field(a,"selected")).id);click(a.getWindow().getDecorView(),"Messages");click(a.getWindow().getDecorView(),"Open received text");List<EditText> fields=new ArrayList<>();edits(a.getWindow().getDecorView(),fields);fields.get(0).setText(packet);click(a.getWindow().getDecorView(),"Open pasted message");});waitMain(scene,"DECRYPTED • SIGNATURE VERIFIED");
                scene.onActivity(a->{assertEquals(remote.id,((Protocol.Group)field(a,"selected")).id);assertTrue(((TextView)field(a,"output")).getText().toString().endsWith("First message after hotspot joining"));});
                try(Vault v=vault()){assertEquals(history+1,v.history.size());}
            }else scene.onActivity(a->dialog.dismiss());
        }finally{pool.shutdownNow();}
    }
}
