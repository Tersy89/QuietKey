package app.quietkey;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.*;
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

/** Opt-in emulator: use production QR pixels and decoder rather than injecting packet strings. */
@RunWith(AndroidJUnit4.class)
public final class QrScanTest {
    private com.journeyapps.barcodescanner.BarcodeView barcode(android.view.View v){if(v instanceof com.journeyapps.barcodescanner.BarcodeView)return (com.journeyapps.barcodescanner.BarcodeView)v;if(v instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)v;for(int i=0;i<group.getChildCount();i++){com.journeyapps.barcodescanner.BarcodeView result=barcode(group.getChildAt(i));if(result!=null)return result;}}return null;}
    private void failedCamera(Context target,JoinScanActivity activity){com.journeyapps.barcodescanner.BarcodeView view=barcode(activity.getWindow().getDecorView());Rect crop=new Rect(view.getPreviewFramingRect());view.getCameraInstance().requestPreview(new com.journeyapps.barcodescanner.camera.PreviewCallback(){public void onPreview(com.journeyapps.barcodescanner.SourceData data){Bitmap preview=data.getBitmap(null,1);try(OutputStream out=new FileOutputStream(new File(target.getExternalFilesDir(null),"qr-camera-failed.png"))){preview.compress(Bitmap.CompressFormat.PNG,100,out);Bundle details=new Bundle();try{details.putString("stream","CAMERA_DIAGNOSTIC raw decoded="+decode(preview).length()+" crop="+crop+"\n");}catch(Exception e){details.putString("stream","CAMERA_DIAGNOSTIC raw not decoded, crop="+crop+"\n");}InstrumentationRegistry.getInstrumentation().sendStatus(2,details);}catch(IOException e){throw new AssertionError(e);}finally{preview.recycle();}}public void onPreviewError(Exception e){}});}
    private void emulator(){Assume.assumeTrue(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk_gphone"));}
    private String decode(Bitmap image)throws Exception{
        int width=image.getWidth(),height=image.getHeight();int[] pixels=new int[width*height];image.getPixels(pixels,0,width,0,0,width,height);
        Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singleton(BarcodeFormat.QR_CODE));hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width,height,pixels))),hints).getText();
    }
    @Test public void displayedRequestAndInvitationDecodeAndReachScannerResult()throws Exception{
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("enigmaUiTest")));emulator();
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(target.getPackageName(),Manifest.permission.CAMERA);
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);KeyPair owner=gen.generateKeyPair(),joiner=gen.generateKeyPair();String request=PairingProtocol.request(joiner.getPublic(),joiner.getPrivate(),"QR test");Protocol.Group group=Protocol.create("QR group",Protocol.random(32),owner.getPublic());String invitation=PairingProtocol.invite(group,PairingProtocol.parseRequest(request),owner.getPublic(),owner.getPrivate());
        for(String packet:new String[]{request,invitation}){
            List<String> scanned=new ArrayList<>();for(String frame:QrTransfer.frames(packet)){assertTrue(frame.length()<=600);Bitmap full=MainActivity.qrBitmap(frame);assertEquals(frame,decode(full));for(int size:new int[]{320,480,720}){Bitmap scaled=MainActivity.qrBitmap(frame,size);try{assertEquals(frame,decode(scaled));}catch(NotFoundException e){File failed=new File(target.getExternalFilesDir(null),"qr-failed-"+size+".png");try(OutputStream out=new FileOutputStream(failed)){scaled.compress(Bitmap.CompressFormat.PNG,100,out);}fail("QR frame length "+frame.length()+" not decoded at "+size+" pixels");}finally{scaled.recycle();}}scanned.add(decode(full));full.recycle();}
            try(ActivityScenario<JoinScanActivity> scenario=ActivityScenario.launchActivityForResult(new Intent(target,JoinScanActivity.class).putExtra("expected",packet.startsWith("QKJ1:")?"QKJ1:":"QKG2:"))){for(String frame:scanned)scenario.onActivity(a->a.acceptFrame(frame));assertEquals(Activity.RESULT_OK,scenario.getResult().getResultCode());assertEquals(packet,scenario.getResult().getResultData().getStringExtra("packet"));}
        }
        assertEquals(group.members,PairingProtocol.accept(invitation,request,joiner.getPublic(),joiner.getPrivate()).members);
    }
    @Test public void cameraPipelineDecodesQr()throws Exception{
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("enigmaQrCameraTest")));emulator();Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(target.getPackageName(),Manifest.permission.CAMERA);
        // Padded/off-centre fixture compensates this emulator imagefile camera projection.
        StringBuilder text=new StringBuilder("QRTEST:");for(int i=0;i<520;i++)text.append((char)('!'+(i*37)%90));String packet=text.toString();Bitmap code=MainActivity.qrBitmap(packet,320),image=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.WHITE);canvas.drawBitmap(code,null,new Rect(204,200,524,520),null);code.recycle();File fixture=new File(target.getExternalFilesDir(null),"qr-camera-test.png");try(OutputStream out=new FileOutputStream(fixture)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}Assume.assumeFalse("Fixture-only preparation is not a camera test", "true".equals(InstrumentationRegistry.getArguments().getString("enigmaQrFixtureOnly")));
        try(ActivityScenario<JoinScanActivity> scenario=ActivityScenario.launchActivityForResult(new Intent(target,JoinScanActivity.class).putExtra("expected","QRTEST:"))){Bundle status=new Bundle();status.putString("stream","QR_CAMERA_READY "+fixture+"\n");InstrumentationRegistry.getInstrumentation().sendStatus(2,status);long until=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<until&&scenario.getState()!=androidx.lifecycle.Lifecycle.State.DESTROYED)Thread.sleep(100);if(scenario.getState()!=androidx.lifecycle.Lifecycle.State.DESTROYED){scenario.onActivity(a->failedCamera(target,a));Thread.sleep(1000);}assertEquals("Live camera must decode",androidx.lifecycle.Lifecycle.State.DESTROYED,scenario.getState());assertEquals(packet,scenario.getResult().getResultData().getStringExtra("packet"));}
    }
}
