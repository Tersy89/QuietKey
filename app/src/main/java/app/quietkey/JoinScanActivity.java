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
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;
import java.util.concurrent.*;

/** Original standard ZXing preview/autofocus, with one explicit success per code. */
public final class JoinScanActivity extends ComponentActivity {
    private static final int BG=0xff080f1d,WHITE=0xffedf5ff,GREEN=0xff4be3c1;
    private DecoratedBarcodeView standard;
    private BarcodeView camera;
    private PreviewView modernPreview;
    private ProcessCameraProvider provider;
    private androidx.camera.core.Camera modernCamera;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService analyser=Executors.newSingleThreadExecutor();
    private TextView status,tip;
    private LinearLayout success;
    private Button next;
    private android.app.AlertDialog optionDialog;
    private QrTransfer transfer=new QrTransfer();
    private volatile boolean active,modern,awaiting;
    private volatile int generation;
    private boolean completed,torch;
    private String expected,completePacket;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        expected=getIntent().getStringExtra("expected");if(expected==null)expected="QKG2:";
        modern=getIntent().getBooleanExtra("cameraX",false);
        LinearLayout page=column();page.setBackgroundColor(BG);
        page.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(8),0,dp(8),0);
        toolbar.addView(button("Cancel",this::finish));toolbar.addView(text(expected.equals("QKJ1:")?"Scan 1/2":expected.equals("QKA1:")?"Confirm":"Scan 2/2",19),new LinearLayout.LayoutParams(0,-2,1));toolbar.addView(button("More",this::options));page.addView(toolbar);
        ScrollView scroll=new ScrollView(this);LinearLayout content=column();content.setPadding(dp(16),dp(8),dp(16),dp(8));scroll.addView(content);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        status=text("Scan the first code",18);status.setTextColor(GREEN);content.addView(status);
        tip=text(expected.equals("QKJ1:")?"Other phone: Join group.":"Owner: show invitation codes.",14);content.addView(tip);
        FrameLayout preview=new FrameLayout(this){@Override protected void onMeasure(int w,int h){int side=MeasureSpec.getSize(w);super.onMeasure(w,MeasureSpec.makeMeasureSpec(side,MeasureSpec.EXACTLY));}};content.addView(preview,new LinearLayout.LayoutParams(-1,-2));
        // Same decorated preview, default framing/TextureView and AUTO focus as release 1.
        standard=new DecoratedBarcodeView(this);camera=standard.getBarcodeView();standard.getStatusView().setVisibility(View.GONE);
        camera.setDecoderFactory(new DefaultDecoderFactory(Collections.singleton(BarcodeFormat.QR_CODE),hints(),"UTF-8",2));preview.addView(standard,new FrameLayout.LayoutParams(-1,-1));
        modernPreview=new PreviewView(this);modernPreview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);modernPreview.setScaleType(PreviewView.ScaleType.FIT_CENTER);modernPreview.setVisibility(View.GONE);preview.addView(modernPreview,new FrameLayout.LayoutParams(-1,-1));
        modernPreview.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_UP){focus(event.getX(),event.getY());v.performClick();}return true;});
        success=column();success.setGravity(Gravity.CENTER);success.setPadding(dp(20),dp(20),dp(20),dp(20));success.setBackgroundColor(0xff112d29);success.setVisibility(View.GONE);preview.addView(success,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout controls=column();controls.setPadding(dp(16),dp(4),dp(16),dp(8));next=button("Waiting for code…",this::nextCode);next.setEnabled(false);next.setTextColor(BG);next.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GREEN));controls.addView(next,new LinearLayout.LayoutParams(-1,-2));page.addView(controls);setContentView(page);
        camera.addStateListener(new CameraPreview.StateListener(){public void previewSized(){}public void previewStarted(){}public void previewStopped(){}public void cameraClosed(){}public void cameraError(Exception e){if(active&&!modern&&!awaiting)status.setText("Camera could not open. More → Try other scanner, or Cancel to retry.");}});
        ActivityResultLauncher<String> permission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->{if(granted&&active)startCamera();else status.setText("Allow Camera in Android Settings, or cancel and use Copy/Paste.");});
        if(checkSelfPermission(Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.CAMERA);
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(WHITE);view.setPadding(0,dp(8),0,dp(8));return view;}
    private Button button(String value,Runnable action){Button button=new Button(this);button.setText(value);button.setAllCaps(false);button.setMinHeight(dp(48));button.setOnClickListener(v->action.run());return button;}
    private static Map<DecodeHintType,Object> hints(){Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singleton(BarcodeFormat.QR_CODE));hints.put(DecodeHintType.CHARACTER_SET,"UTF-8");return hints;}
    private void options(){optionDialog=new android.app.AlertDialog.Builder(this).setTitle("Scanner options").setItems(expected.equals("QKA1:")?new String[]{torch?"Light off":"Light on","Try other scanner","Restart scan"}:new String[]{torch?"Light off":"Light on","Try other scanner","Restart scan",expected.equals("QKJ1:")?"Paste request":"Paste invitation"},(d,which)->{if(which==0){torch=!torch;applyTorch();}else if(which==1)switchCamera(!modern);else if(which==3){setResult(RESULT_OK,new Intent().putExtra("paste",true));finish();}else{stopCamera();transfer=new QrTransfer();completePacket=null;awaiting=false;success.setVisibility(View.GONE);status.setText("Scan the first code");next.setText("Waiting for code…");next.setEnabled(false);startCamera();}}).create();optionDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);optionDialog.show();}
    private void armLegacy(){if(active&&!modern&&!awaiting&&!completed)standard.decodeSingle(new BarcodeCallback(){public void barcodeResult(BarcodeResult result){if(active&&!modern&&!awaiting)acceptFrame(result.getText());}});}
    private void startCamera(){
        if(!active||completed||awaiting||checkSelfPermission(Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        if(modern){standard.setVisibility(View.GONE);modernPreview.setVisibility(View.VISIBLE);startModern();}
        else{modernPreview.setVisibility(View.GONE);standard.setVisibility(View.VISIBLE);camera.resume();applyTorch();armLegacy();}
    }
    private void stopCamera(){generation++;camera.pauseAndWait();if(provider!=null)provider.unbindAll();modernCamera=null;}
    void switchCamera(boolean useModern){stopCamera();modern=useModern;if(!awaiting){status.setText("Keep the current code still · "+(modern?"alternate":"original")+" scanner");startCamera();}}
    private void startModern(){
        final int attempt=generation;ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{try{
            ProcessCameraProvider loaded=future.get();if(!active||!modern||awaiting||attempt!=generation||completed)return;provider=loaded;
            Preview preview=new Preview.Builder().setTargetResolution(new Size(1280,720)).build();preview.setSurfaceProvider(modernPreview.getSurfaceProvider());
            ImageAnalysis analysis=new ImageAnalysis.Builder().setTargetResolution(new Size(1280,720)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();MultiFormatReader reader=new MultiFormatReader();reader.setHints(hints());
            analysis.setAnalyzer(analyser,image->{byte[] pixels=null;try{
                if(!active||!modern||awaiting||attempt!=generation)return;
                ImageProxy.PlaneProxy plane=image.getPlanes()[0];pixels=QrLuma.copy(plane.getBuffer(),image.getWidth(),image.getHeight(),plane.getRowStride(),plane.getPixelStride());
                LuminanceSource source=new PlanarYUVLuminanceSource(pixels,image.getWidth(),image.getHeight(),0,0,image.getWidth(),image.getHeight(),false);
                Result result;try{result=reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));}catch(NotFoundException e){reader.reset();result=reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source.invert())));}
                String text=result.getText();ui.post(()->{if(active&&modern&&!awaiting&&attempt==generation)acceptFrame(text);});
            }catch(NotFoundException ignored){}catch(Exception e){ui.post(()->{if(active&&!awaiting&&attempt==generation)status.setText("More → Try other scanner, or Cancel to retry.");});}
            finally{reader.reset();if(pixels!=null)Arrays.fill(pixels,(byte)0);image.close();}});
            provider.unbindAll();modernCamera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis);applyTorch();modernPreview.post(()->focus(modernPreview.getWidth()/2f,modernPreview.getHeight()/2f));
        }catch(Exception e){if(active&&attempt==generation)status.setText("Camera could not open. More → Try other scanner.");}},ContextCompat.getMainExecutor(this));
    }
    private void focus(float x,float y){if(modernCamera!=null&&modernPreview.getWidth()>0){MeteringPoint point=modernPreview.getMeteringPointFactory().createPoint(x,y);modernCamera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(point,FocusMeteringAction.FLAG_AF|FocusMeteringAction.FLAG_AE).setAutoCancelDuration(3,TimeUnit.SECONDS).build());}}
    private void applyTorch(){if(modern){if(modernCamera!=null)modernCamera.getCameraControl().enableTorch(torch);}else camera.setTorch(torch);}
    void acceptFrame(String frame){
        if(completed||awaiting)return;
        try{
            int before=transfer.received();String packet=transfer.add(frame);
            if(packet!=null&&!packet.startsWith(expected))throw new SecurityException("Wrong joining step");
            if(packet==null&&before==transfer.received()){status.setText("Already scanned. Ask the other phone to tap Next code.");ui.postDelayed(this::armLegacy,500);return;}
            completePacket=packet;awaiting=true;stopCamera();success.removeAllViews();
            int total=transfer.total()==0?1:transfer.total(),received=packet!=null?total:transfer.received();
            status.setText("Code scanned · "+received+" of "+total);success.addView(text("✓ Code scanned",26));
            success.addView(text(packet==null?"On the other phone: tap Next code.\n\nThen tap Scan next code here.":expected.equals("QKJ1:")?"All joining codes received.\n\nContinue to confirm the invitation, then swap phones.":"All codes received.\n\nContinue to verify and finish joining.",20));success.setVisibility(View.VISIBLE);
            tip.setText(packet==null?"Scan paused · advance the other phone.":"No more codes to scan.");next.setEnabled(true);next.setText(packet==null?"Scan next code":expected.equals("QKG2:")?"Join group":"Continue");
        }catch(Exception e){status.setText("Wrong or damaged code. Collected codes kept. More → Restart scan to start over.");ui.postDelayed(this::armLegacy,500);}
    }
    void nextCode(){
        if(!awaiting)return;
        if(completePacket!=null){completed=true;setResult(RESULT_OK,new Intent().putExtra("packet",completePacket));finish();return;}
        awaiting=false;success.setVisibility(View.GONE);status.setText("Scan the next code · "+transfer.received()+" of "+transfer.total()+" received");tip.setText("Other phone: tap Next code. Hold it still.");next.setText("Waiting for code…");next.setEnabled(false);startCamera();
    }
    boolean waitingForNext(){return awaiting;}
    int receivedCodes(){return transfer.received();}
    @Override protected void onResume(){super.onResume();active=true;startCamera();}
    @Override protected void onPause(){active=false;if(optionDialog!=null){optionDialog.dismiss();optionDialog=null;}if(camera!=null)stopCamera();super.onPause();}
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);analyser.shutdown();completePacket=null;super.onDestroy();}
}
