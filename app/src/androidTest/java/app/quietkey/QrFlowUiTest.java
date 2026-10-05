package app.quietkey;

import android.graphics.*;
import android.os.Build;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real transfer windows, rendered pixels and manual controls; no phone camera claim. */
@RunWith(AndroidJUnit4.class)
public final class QrFlowUiTest {
    private static TextView find(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView child=find(group.getChildAt(i),text);if(child!=null)return child;}}return null;}
    private static void click(QrTransferDialog dialog,String text){TextView button=find(dialog.getWindow().getDecorView(),text);assertNotNull(text,button);assertTrue(text,button.isEnabled());assertTrue(text,button.performClick());}
    private static String decode(Bitmap bitmap)throws Exception{int w=bitmap.getWidth(),h=bitmap.getHeight();int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.TRY_HARDER,true);hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singleton(BarcodeFormat.QR_CODE));return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w,h,pixels))),hints).getText();}
    @Test public void scannerSuccessAndFullSizePreview()throws Exception{
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("enigmaUiTest")));
        Assume.assumeTrue(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk_gphone"));
        android.content.Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(target.getPackageName(),android.Manifest.permission.CAMERA);
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);KeyPair joiner=gen.generateKeyPair();List<String> frames=QrTransfer.frames(PairingProtocol.request(joiner.getPublic(),joiner.getPrivate(),"Joining device"));
        try(ActivityScenario<JoinScanActivity> scenario=ActivityScenario.launchActivityForResult(new android.content.Intent(target,JoinScanActivity.class).putExtra("expected","QKJ1:"))){
            scenario.onActivity(a->{a.acceptFrame(frames.get(0));assertTrue(a.waitingForNext());assertNotNull(find(a.getWindow().getDecorView(),"Scan next code"));});Thread.sleep(400);
            scenario.onActivity(a->{View standard=decorated(a.getWindow().getDecorView());assertNotNull(standard);com.journeyapps.barcodescanner.DecoratedBarcodeView scanner=(com.journeyapps.barcodescanner.DecoratedBarcodeView)standard;assertEquals(com.journeyapps.barcodescanner.camera.CameraSettings.FocusMode.AUTO,scanner.getCameraSettings().getFocusMode());View area=(View)standard.getParent();assertEquals(area.getWidth(),area.getHeight());Rect visible=new Rect();assertTrue(area.getGlobalVisibleRect(visible));assertEquals("Scanner is fully visible at this text/display size",area.getHeight(),visible.height());Bitmap preview=Bitmap.createBitmap(a.getWindow().getDecorView().getWidth(),a.getWindow().getDecorView().getHeight(),Bitmap.Config.ARGB_8888);a.getWindow().getDecorView().draw(new Canvas(preview));try(OutputStream out=new FileOutputStream(new File(a.getExternalFilesDir(null),"joining-scan-success-"+a.getResources().getConfiguration().fontScale+".png"))){preview.compress(Bitmap.CompressFormat.PNG,100,out);}catch(IOException e){throw new AssertionError(e);}finally{preview.recycle();}});
            for(int i=1;i<frames.size();i++){final int index=i;scenario.onActivity(a->{TextView next=find(a.getWindow().getDecorView(),"Scan next code");assertNotNull(next);next.performClick();a.acceptFrame(frames.get(index));assertTrue(a.waitingForNext());});}
            scenario.onActivity(a->{TextView finish=find(a.getWindow().getDecorView(),"Continue");assertNotNull(finish);finish.performClick();});assertEquals(android.app.Activity.RESULT_OK,scenario.getResult().getResultCode());
        }
    }
    private static View decorated(View view){if(view instanceof com.journeyapps.barcodescanner.DecoratedBarcodeView)return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View result=decorated(group.getChildAt(i));if(result!=null)return result;}}return null;}
    @Test public void fullSizeManualRequestInvitationAndSwap()throws Exception{
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("enigmaUiTest")));
        Assume.assumeTrue(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk_gphone"));
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);KeyPair owner=gen.generateKeyPair(),joiner=gen.generateKeyPair();
        String request=PairingProtocol.request(joiner.getPublic(),joiner.getPrivate(),"Joining device");Protocol.Group group=Protocol.create("Layout test",Protocol.random(32),owner.getPublic());String invitation=PairingProtocol.invite(group,PairingProtocol.parseRequest(request),owner.getPublic(),owner.getPrivate());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            for(String packet:new String[]{request,invitation}){
                QrTransferDialog[] shown={null};boolean[] swapped={false};
                scenario.onActivity(a->{try{shown[0]=new QrTransferDialog(a,packet,()->swapped[0]=true,()->{},()->{});shown[0].show();shown[0].getWindow().setLayout(-1,-1);}catch(Exception e){throw new AssertionError(e);}});Thread.sleep(600);
                List<String> frames=QrTransfer.frames(packet);
                for(int i=0;i<frames.size();i++){
                    final int index=i;
                    scenario.onActivity(a->{QrTransferDialog dialog=shown[0];assertEquals(index,dialog.codeIndex());ImageView image=dialog.qrView();assertNotNull(image);int width=image.getWidth();assertEquals("QR is square",width,image.getHeight());assertTrue("QR occupies the screen width",width>=a.getResources().getDisplayMetrics().widthPixels*.80f);Rect visible=new Rect();assertTrue(image.getGlobalVisibleRect(visible));assertEquals("Full code is visible without scrolling",width,visible.width());assertEquals("QR cannot be squeezed or clipped vertically",width,visible.height());Bitmap rendered=Bitmap.createBitmap(width,width,Bitmap.Config.ARGB_8888);image.draw(new Canvas(rendered));try{assertEquals("Actual displayed pixels decode",frames.get(index),decode(rendered));}catch(Exception e){throw new AssertionError(e);}finally{rendered.recycle();}});
                    if(i==0){Thread.sleep(3500);scenario.onActivity(a->assertEquals("No timer may advance the code",0,shown[0].codeIndex()));savePreview(scenario,shown[0],packet.startsWith("QKJ1:")?"request":"invitation");}
                    if(i<frames.size()-1){scenario.onActivity(a->click(shown[0],"Next code"));Thread.sleep(150);}
                }
                if(packet.startsWith("QKJ1:")){
                    scenario.onActivity(a->{assertFalse(swapped[0]);click(shown[0],"Swap phones");assertNull("Swap instructions replace the QR",shown[0].qrView());assertNotNull(find(shown[0].getWindow().getDecorView(),"Scan owner's invitation"));assertFalse("Showing swap instructions does not launch the camera",swapped[0]);});savePreview(scenario,shown[0],"swap");scenario.onActivity(a->{click(shown[0],"Scan owner's invitation");assertTrue(swapped[0]);});
                }else{scenario.onActivity(a->{click(shown[0],"Finish showing codes");assertNotNull(find(shown[0].getWindow().getDecorView(),"All codes shown"));click(shown[0],"Done");});}
            }
        }
    }
    private void savePreview(ActivityScenario<MainActivity> scenario,QrTransferDialog dialog,String name){scenario.onActivity(a->{View decor=dialog.getWindow().getDecorView();Bitmap preview=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(preview));String scale=Float.toString(a.getResources().getConfiguration().fontScale);try(OutputStream out=new FileOutputStream(new File(a.getExternalFilesDir(null),"joining-"+name+"-"+scale+".png"))){assertTrue(preview.compress(Bitmap.CompressFormat.PNG,100,out));}catch(IOException e){throw new AssertionError(e);}finally{preview.recycle();}});}
}
