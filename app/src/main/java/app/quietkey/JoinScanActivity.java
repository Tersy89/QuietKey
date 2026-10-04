package app.quietkey;
import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.journeyapps.barcodescanner.*;

/** Collects cycling invitation frames in one continuous scan. */
public final class JoinScanActivity extends ComponentActivity {
    private DecoratedBarcodeView camera;private TextView status;private QrTransfer transfer=new QrTransfer();private boolean completed;
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(0xff080f1d);status=new TextView(this);status.setTextColor(0xffedf5ff);status.setPadding(24,48,24,24);status.setText("Scan the owner's invitation. Hold steady while its QR frames change.");layout.addView(status);camera=new DecoratedBarcodeView(this);layout.addView(camera,new LinearLayout.LayoutParams(-1,0,1));Button cancel=new Button(this);cancel.setText("Cancel joining");cancel.setOnClickListener(v->finish());layout.addView(cancel);setContentView(layout);
        camera.decodeContinuous(new BarcodeCallback(){public void barcodeResult(BarcodeResult result){if(completed)return;try{String packet=transfer.add(result.getText());if(packet==null){status.setText("Collecting invitation: "+transfer.received()+" / "+transfer.total()+" frames. Hold steady.");return;}if(!packet.startsWith("QKG2:"))throw new SecurityException("Scan the owner’s encrypted group invitation");completed=true;setResult(RESULT_OK,new Intent().putExtra("packet",packet));finish();}catch(Exception e){transfer=new QrTransfer();status.setText(e.getMessage()+". Start scanning the owner's QR again.");}}});
        ActivityResultLauncher<String> permission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->{if(granted)camera.resume();else{status.setText("Camera permission is needed to scan an invitation.");}});permission.launch(Manifest.permission.CAMERA);
    }
    @Override protected void onResume(){super.onResume();if(camera!=null&&checkSelfPermission(Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED)camera.resume();}
    @Override protected void onPause(){if(camera!=null)camera.pause();super.onPause();}
}
