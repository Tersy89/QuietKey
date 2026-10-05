package app.quietkey;

import android.Manifest;
import android.content.Intent;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.journeyapps.barcodescanner.*;
import com.journeyapps.barcodescanner.camera.CameraSettings;
import com.journeyapps.barcodescanner.camera.FitCenterStrategy;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;
import java.util.concurrent.*;

/** One collector, two entirely offline camera paths; no captured images are stored. */
public final class JoinScanActivity extends ComponentActivity {
    private BarcodeView camera;
    private PreviewView modernPreview;
    private ProcessCameraProvider provider;
    private androidx.camera.core.Camera modernCamera;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService analyser=Executors.newSingleThreadExecutor();
    private TextView status;
    private Button light,retry;
    private QrTransfer transfer=new QrTransfer();
    private volatile boolean active,modern;
    private volatile int generation;
    private boolean completed,torch;
    private int decodedFrames;
    private String expected;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        expected=getIntent().getStringExtra("expected");if(expected==null)expected="QKG2:";
        modern=getIntent().getBooleanExtra("cameraX",false);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(0xff080f1d);
        layout.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(24,insets.getSystemWindowInsetTop()+24,24,insets.getSystemWindowInsetBottom()+24);return insets;});
        status=new TextView(this);status.setTextColor(0xffedf5ff);status.setTextSize(18);status.setText(instruction());layout.addView(status);
        FrameLayout preview=new FrameLayout(this);layout.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        camera=new BarcodeView(this){@Override protected android.graphics.Rect calculateFramingRect(android.graphics.Rect container,android.graphics.Rect surface){android.graphics.Rect frame=new android.graphics.Rect(container);return frame.intersect(surface)?frame:null;}};
        camera.setUseTextureView(false);camera.setPreviewScalingStrategy(new FitCenterStrategy());
        camera.setDecoderFactory(new DefaultDecoderFactory(Collections.singleton(BarcodeFormat.QR_CODE),hints(),"UTF-8",2));
        CameraSettings settings=new CameraSettings();settings.setAutoFocusEnabled(true);settings.setContinuousFocusEnabled(true);camera.setCameraSettings(settings);
        preview.addView(camera,new FrameLayout.LayoutParams(-1,-1));
        modernPreview=new PreviewView(this);modernPreview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);modernPreview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        modernPreview.setVisibility(View.GONE);preview.addView(modernPreview,new FrameLayout.LayoutParams(-1,-1));
        modernPreview.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_UP){focus(event.getX(),event.getY());v.performClick();return true;}return true;});
        TextView tip=new TextView(this);tip.setTextColor(0xff93a9bf);tip.setText("Keep the whole code visible, about 20–40 cm away. If nothing scans, try the other scanner. Cancel returns to joining; you can paste the transfer there.");layout.addView(tip);
        LinearLayout tools=new LinearLayout(this);light=new Button(this);light.setText("Light on");light.setOnClickListener(v->{torch=!torch;applyTorch();});tools.addView(light,new LinearLayout.LayoutParams(0,-2,1));
        Button alternate=new Button(this);alternate.setText("Try other scanner");alternate.setOnClickListener(v->switchCamera(!modern));tools.addView(alternate,new LinearLayout.LayoutParams(0,-2,1));layout.addView(tools);
        LinearLayout actions=new LinearLayout(this);retry=new Button(this);retry.setText("Restart scan");retry.setVisibility(View.GONE);retry.setOnClickListener(v->{transfer=new QrTransfer();decodedFrames=0;retry.setVisibility(View.GONE);status.setText(instruction());switchCamera(modern);});actions.addView(retry,new LinearLayout.LayoutParams(0,-2,1));
        Button cancel=new Button(this);cancel.setText("Cancel");cancel.setOnClickListener(v->finish());actions.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));layout.addView(actions);setContentView(layout);
        camera.addStateListener(new CameraPreview.StateListener(){public void previewSized(){}public void previewStarted(){}public void previewStopped(){}public void cameraClosed(){}public void cameraError(Exception e){if(active&&!modern)switchCamera(true);}});
        camera.decodeContinuous(new BarcodeCallback(){public void barcodeResult(BarcodeResult result){if(active&&!modern)acceptFrame(result.getText());}});
        ActivityResultLauncher<String> permission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->{if(granted&&active)startCamera();else status.setText("Allow Camera permission in Android Settings, or cancel and paste the transfer.");});
        if(checkSelfPermission(Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.CAMERA);
    }
    private static Map<DecodeHintType,Object> hints(){Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singleton(BarcodeFormat.QR_CODE));hints.put(DecodeHintType.CHARACTER_SET,"UTF-8");return hints;}
    private String instruction(){return expected.equals("QKJ1:")?"Owner · step 1 of 2\nScan the joining phone's Join group QR.":expected.equals("QKA1:")?"Optional confirmation\nScan the member's joining confirmation.":"Joining phone · step 2 of 2\nScan the owner's invitation. Do not scan your own QR.";}
    private void startCamera(){
        if(!active||completed||checkSelfPermission(Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        if(modern){camera.setVisibility(View.GONE);modernPreview.setVisibility(View.VISIBLE);startModern();}
        else{modernPreview.setVisibility(View.GONE);camera.setVisibility(View.VISIBLE);camera.resume();final int attempt=generation;ui.postDelayed(()->{if(active&&!modern&&!completed&&attempt==generation&&decodedFrames==0)switchCamera(true);},12000);}
    }
    private void switchCamera(boolean useModern){
        generation++;camera.pauseAndWait();if(provider!=null)provider.unbindAll();modernCamera=null;modern=useModern;
        status.setText((modern?"Trying the other scanner…\n":"Trying the first scanner…\n")+instruction());startCamera();
    }
    private void startModern(){
        final int attempt=generation;
        ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{try{
            ProcessCameraProvider loaded=future.get();if(!active||!modern||attempt!=generation||completed)return;provider=loaded;
            Preview preview=new Preview.Builder().setTargetResolution(new Size(1280,720)).build();preview.setSurfaceProvider(modernPreview.getSurfaceProvider());
            ImageAnalysis analysis=new ImageAnalysis.Builder().setTargetResolution(new Size(1280,720)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
            MultiFormatReader reader=new MultiFormatReader();reader.setHints(hints());
            analysis.setAnalyzer(analyser,image->{
                byte[] pixels=null;try{
                    if(!active||!modern||attempt!=generation)return;
                    ImageProxy.PlaneProxy plane=image.getPlanes()[0];pixels=QrLuma.copy(plane.getBuffer(),image.getWidth(),image.getHeight(),plane.getRowStride(),plane.getPixelStride());
                    LuminanceSource source=new PlanarYUVLuminanceSource(pixels,image.getWidth(),image.getHeight(),0,0,image.getWidth(),image.getHeight(),false);
                    Result result;try{result=reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));}catch(NotFoundException e){reader.reset();result=reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source.invert())));}
                    String text=result.getText();ui.post(()->{if(active&&modern&&attempt==generation)acceptFrame(text);});
                }catch(NotFoundException ignored){}catch(Exception e){ui.post(()->{if(active&&attempt==generation)status.setText("Camera frames unavailable. Try other scanner, or cancel and paste the transfer.");});}
                finally{reader.reset();if(pixels!=null)Arrays.fill(pixels,(byte)0);image.close();}
            });
            provider.unbindAll();modernCamera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis);applyTorch();modernPreview.post(()->focus(modernPreview.getWidth()/2f,modernPreview.getHeight()/2f));
        }catch(Exception e){if(active&&attempt==generation)status.setText("This scanner could not open the camera. Try other scanner, or cancel and paste the transfer.");}},ContextCompat.getMainExecutor(this));
    }
    private void focus(float x,float y){if(modernCamera!=null&&modernPreview.getWidth()>0){MeteringPoint point=modernPreview.getMeteringPointFactory().createPoint(x,y);modernCamera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(point,FocusMeteringAction.FLAG_AF|FocusMeteringAction.FLAG_AE).setAutoCancelDuration(3,TimeUnit.SECONDS).build());}}
    private void applyTorch(){if(modern){if(modernCamera!=null)modernCamera.getCameraControl().enableTorch(torch);}else camera.setTorch(torch);light.setText(torch?"Light off":"Light on");}
    void acceptFrame(String frame){
        if(completed)return;decodedFrames++;
        try{String packet=transfer.add(frame);if(packet==null){status.setText("Received "+transfer.received()+" of "+transfer.total()+" frames.\nKeep scanning. Missing: "+transfer.missing());return;}
            if(!packet.startsWith(expected))throw new SecurityException("Wrong joining step");completed=true;setResult(RESULT_OK,new Intent().putExtra("packet",packet));finish();
        }catch(Exception e){retry.setVisibility(View.VISIBLE);status.setText(instruction()+"\nDifferent or damaged code. Collected frames were kept; Restart scan clears them.");}
    }
    @Override protected void onResume(){super.onResume();active=true;startCamera();}
    @Override protected void onPause(){active=false;generation++;if(camera!=null)camera.pauseAndWait();if(provider!=null)provider.unbindAll();modernCamera=null;super.onPause();}
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);analyser.shutdown();super.onDestroy();}
}
