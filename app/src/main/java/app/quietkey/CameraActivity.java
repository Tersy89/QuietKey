package app.quietkey;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.*;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** Hashes live video frames; never records video or requests microphone access. */
public final class CameraActivity extends Activity {
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler worker;
    private MessageDigest digest;
    private TextView status;
    private int frames;
    private long started;
    private volatile boolean stopped;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); view.setPadding(dp(22),dp(12),dp(22),dp(16)); view.setBackgroundColor(0xff101b22);
        view.setOnApplyWindowInsetsListener((v,insets) -> {
            v.setPadding(dp(22),insets.getSystemWindowInsetTop()+dp(12),dp(22),insets.getSystemWindowInsetBottom()+dp(16));
            return insets;
        });
        status = new TextView(this); status.setTextColor(0xffeff5f2); status.setTextSize(24); view.addView(status);
        TextView tip = new TextView(this); tip.setTextColor(0xffa6e3b8); tip.setTextSize(18); tip.setPadding(0,32,0,32);
        tip.setText("Move the camera around for five seconds. Live frames are mixed with Android secure randomness. Video is never saved."); view.addView(tip);
        Button cancel = new Button(this); cancel.setText("Cancel"); cancel.setOnClickListener(v -> finish()); view.addView(cancel); setContentView(view);
        status.setText("Preparing live camera…");
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.CAMERA},7); else start();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if (code==7 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) start(); else fail("Camera permission is required for this capture");
    }
    @SuppressWarnings("MissingPermission") private void start() {
        try {
            digest = MessageDigest.getInstance("SHA-256"); thread = new HandlerThread("camera-frames"); thread.start(); worker = new Handler(thread.getLooper());
            CameraManager manager = (CameraManager)getSystemService(CAMERA_SERVICE); String chosen = null; Size size = null;
            for (String id:manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Size[] sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888);
                if (sizes==null || sizes.length==0) continue;
                if (chosen==null || Objects.equals(c.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK)) {
                    chosen=id; size=Arrays.stream(sizes).min(Comparator.comparingLong(s -> (long)s.getWidth()*s.getHeight())).get();
                    if (Objects.equals(c.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK)) break;
                }
            }
            if (chosen==null) throw new IllegalStateException("No camera video stream available");
            reader = ImageReader.newInstance(size.getWidth(),size.getHeight(),ImageFormat.YUV_420_888,2);
            reader.setOnImageAvailableListener(r -> {
                if (stopped) return;
                try (Image image = r.acquireLatestImage()) {
                    if (image==null) return;
                    // Camera startup time does not count toward five seconds of live input.
                    if (frames==0) started=SystemClock.elapsedRealtime();
                    digest.update(ByteBuffer.allocate(8).putLong(image.getTimestamp()).array());
                    for (Image.Plane plane:image.getPlanes()) digest.update(plane.getBuffer());
                    frames++; long elapsed=SystemClock.elapsedRealtime()-started;
                    runOnUiThread(() -> status.setText("Collecting live frames\n"+frames+" frames · "+Math.min(5,elapsed/1000)+" / 5 seconds"));
                    if (elapsed>=5000 && frames>=60) {
                        stopped=true; byte[] hash=digest.digest(); runOnUiThread(() -> { setResult(RESULT_OK,new Intent().putExtra("digest",hash)); finish(); });
                    }
                } catch (Exception e) { fail("Camera capture failed; try again"); }
            },worker);
            manager.openCamera(chosen,new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (stopped) { device.close(); return; } camera=device;
                    try { device.createCaptureSession(Collections.singletonList(reader.getSurface()),new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            if (stopped) { s.close(); return; } session=s;
                            try { CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD); request.addTarget(reader.getSurface()); s.setRepeatingRequest(request.build(),null,worker); }
                            catch(Exception e) { fail("Could not start live video"); }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) { fail("Camera stream unavailable"); }
                    },worker); } catch(Exception e) { fail("Camera unavailable"); }
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); fail("Camera disconnected"); }
                @Override public void onError(CameraDevice d,int error) { d.close(); fail("Camera is unavailable or in use"); }
            },worker);
            worker.postDelayed(() -> { if (!stopped) fail("Capture timed out; try again in better light"); },20000);
        } catch(Exception e) { fail("Could not open camera: "+e.getMessage()); }
    }
    private void fail(String message) { if (stopped) return; stopped=true; runOnUiThread(() -> { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); finish(); }); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    @Override public void onPause() { super.onPause(); if (!isFinishing() && thread!=null) finish(); }
    @Override public void onDestroy() {
        stopped=true;
        if (worker!=null) {
            worker.post(() -> { if(session!=null)session.close(); if(camera!=null)camera.close(); if(reader!=null)reader.close(); thread.quitSafely(); });
        }
        super.onDestroy();
    }
}
