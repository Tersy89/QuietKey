package app.quietkey;
import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.journeyapps.barcodescanner.*;
import com.journeyapps.barcodescanner.camera.CameraSettings;
import com.journeyapps.barcodescanner.camera.FitCenterStrategy;
import com.google.zxing.*;
import java.util.*;

/** One QR-only continuous scanner for joining requests, invitations and confirmations. */
public final class JoinScanActivity extends ComponentActivity {
    private BarcodeView camera;private TextView status;private QrTransfer transfer=new QrTransfer();private boolean completed,torch;private String expected;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        expected=getIntent().getStringExtra("expected");if(expected==null)expected="QKG2:";
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,24,24,24);layout.setBackgroundColor(0xff080f1d);layout.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(24,insets.getSystemWindowInsetTop()+24,24,insets.getSystemWindowInsetBottom()+24);return insets;});
        status=new TextView(this);status.setTextColor(0xffedf5ff);status.setTextSize(18);status.setText(instruction());layout.addView(status);
        // SurfaceView honours FitCenter; TextureView in ZXing 4.3 always centre-crops.
        camera=new BarcodeView(this){@Override protected android.graphics.Rect calculateFramingRect(android.graphics.Rect container,android.graphics.Rect surface){android.graphics.Rect frame=new android.graphics.Rect(container);return frame.intersect(surface)?frame:null;}};camera.setUseTextureView(false);camera.setPreviewScalingStrategy(new FitCenterStrategy());Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);camera.setDecoderFactory(new DefaultDecoderFactory(Collections.singleton(BarcodeFormat.QR_CODE),hints,"UTF-8",2));CameraSettings settings=new CameraSettings();settings.setAutoFocusEnabled(true);settings.setContinuousFocusEnabled(true);camera.setCameraSettings(settings);layout.addView(camera,new LinearLayout.LayoutParams(-1,0,1));
        TextView tip=new TextView(this);tip.setTextColor(0xff93a9bf);tip.setText("Keep the whole QR in view. Move back if it fills the preview.");layout.addView(tip);
        LinearLayout actions=new LinearLayout(this);Button light=new Button(this);light.setText("Light on");light.setOnClickListener(v->{torch=!torch;camera.setTorch(torch);light.setText(torch?"Light off":"Light on");});actions.addView(light,new LinearLayout.LayoutParams(0,-2,1));Button cancel=new Button(this);cancel.setText("Cancel");cancel.setOnClickListener(v->finish());actions.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));layout.addView(actions);setContentView(layout);
        camera.addStateListener(new CameraPreview.StateListener(){public void previewSized(){}public void previewStarted(){}public void previewStopped(){}public void cameraClosed(){}public void cameraError(Exception e){status.setText("Camera could not open. Close other camera apps, then try again.");}});
        camera.decodeContinuous(new BarcodeCallback(){public void barcodeResult(BarcodeResult result){acceptFrame(result.getText());}});
        ActivityResultLauncher<String> permission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->{if(granted)camera.resume();else status.setText("Allow Camera permission in Android Settings to scan a QR.");});if(checkSelfPermission(Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.CAMERA);
    }
    private String instruction(){return expected.equals("QKJ1:")?"Scan their Groups → Join group QR":expected.equals("QKA1:")?"Scan their joining confirmation":"Scan the owner's invitation";}
    // Same boundary used by the live decoder and image/camera regression tests.
    void acceptFrame(String frame){if(completed)return;try{String packet=transfer.add(frame);if(packet==null){status.setText("Scanned "+transfer.received()+" of "+transfer.total()+" frames. Keep holding steady.");return;}if(!packet.startsWith(expected))throw new SecurityException(instruction());completed=true;setResult(RESULT_OK,new Intent().putExtra("packet",packet));finish();}catch(Exception e){transfer=new QrTransfer();status.setText(instruction()+". This QR is different or damaged; try again.");}}
    @Override protected void onResume(){super.onResume();if(camera!=null&&checkSelfPermission(Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED)camera.resume();}
    @Override protected void onPause(){if(camera!=null)camera.pauseAndWait();super.onPause();}
}
