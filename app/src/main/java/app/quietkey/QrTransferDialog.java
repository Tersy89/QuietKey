package app.quietkey;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** A non-floating, scrollable transfer. A displayed code never advances on a timer. */
public final class QrTransferDialog extends Dialog {
    private static final int BG=0xff080f1d,WHITE=0xffedf5ff,GREEN=0xff4be3c1;
    private final String packet;
    private final List<String> frames;
    private final Runnable scanInvitation,pasteInvitation,startAgain;
    private final LinearLayout content;
    private final ScrollView scroll;
    private final TextView heading;
    private final Button next;
    private final boolean request;
    private SquareQr image;
    private AlertDialog optionDialog;
    private String infoTitle,infoCaption;
    private int index;

    public QrTransferDialog(Activity activity,String packet,Runnable scanInvitation,Runnable pasteInvitation,Runnable startAgain)throws Exception{
        super(activity,R.style.AppTheme);
        this.packet=packet;frames=QrTransfer.frames(packet);request=packet.startsWith("QKJ1:");
        this.scanInvitation=scanInvitation;this.pasteInvitation=pasteInvitation;this.startAgain=startAgain;
        LinearLayout page=column();page.setBackgroundColor(BG);
        page.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout toolbar=new LinearLayout(getContext());toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(8),0,dp(8),0);
        toolbar.addView(button(request?"Cancel":"Done",this::dismiss));
        heading=text(request?"Join group":"Invite recipient",20);toolbar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button more=button("More",()->options());toolbar.addView(more);page.addView(toolbar);
        scroll=new ScrollView(getContext());scroll.setFillViewport(false);content=column();content.setPadding(dp(16),dp(8),dp(16),dp(8));scroll.addView(content);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=column();controls.setPadding(dp(16),dp(4),dp(16),dp(8));
        next=button("",this::advance);next.setTextColor(BG);next.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GREEN));controls.addView(next,new LinearLayout.LayoutParams(-1,-2));
        page.addView(controls);
        setContentView(page);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams attrs=getWindow().getAttributes();attrs.screenBrightness=1f;getWindow().setAttributes(attrs);
        showCode();
    }
    private int dp(int n){return Math.round(n*getContext().getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(getContext());view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size){TextView view=new TextView(getContext());view.setText(value);view.setTextSize(size);view.setTextColor(WHITE);view.setPadding(0,dp(8),0,dp(8));return view;}
    private Button button(String value,Runnable action){Button button=new Button(getContext());button.setText(value);button.setAllCaps(false);button.setMinHeight(dp(48));button.setOnClickListener(v->action.run());return button;}
    private void showCode(){
        content.removeAllViews();heading.setText(request?"Show 1/2":packet.startsWith("QKG2:")?"Show 2/2":"Confirmation");
        TextView count=text("Code "+(index+1)+" of "+frames.size(),18);count.setTextColor(GREEN);content.addView(count);
        content.addView(text("Next after Code scanned.",14));
        image=new SquareQr(getContext(),frames.get(index));content.addView(image,new LinearLayout.LayoutParams(-1,-2));
        next.setText(index<frames.size()-1?"Next code":request?"Swap phones":"Finish showing codes");
        next.setOnClickListener(v->advance());if(infoTitle!=null){heading.setText(infoTitle);content.addView(text(infoCaption,16));if(index==frames.size()-1){next.setText("Done");next.setOnClickListener(v->dismiss());}}scroll.scrollTo(0,0);
    }
    private void advance(){
        if(index<frames.size()-1){index++;showCode();return;}
        showSwap();
    }
    private void showSwap(){
        content.removeAllViews();image=null;scroll.scrollTo(0,0);
        if(request){
            heading.setText("Swap phones");content.addView(text("Your codes have been scanned",24));
            content.addView(text("Owner: tap Invite, then show the invitation codes.\n\nOn this phone: scan those codes, one at a time.",20));
            next.setText("Scan owner's invitation");next.setOnClickListener(v->{dismiss();scanInvitation.run();});
        }else{
            heading.setText("Finish on the other phone");content.addView(text("All codes shown",24));
            content.addView(text(packet.startsWith("QKG2:")?"On the joining phone, tap Join group. Joining is complete when the group appears there.":"On the other phone, tap Continue.",20));
            next.setText("Done");next.setOnClickListener(v->dismiss());
        }
    }
    private void options(){
        String[] options=request?new String[]{"Copy transfer","Paste invitation","Start again","Owner already has my codes","Previous code"}:new String[]{"Copy transfer","Show instructions","Previous code"};
        showOption(new AlertDialog.Builder(getContext()).setTitle("Transfer options").setItems(options,(d,which)->{
            if((request&&which==4)||(!request&&which==2)){if(index>0){index--;showCode();}}
            else if(which==0){ClipboardManager clipboard=(ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("Enigma group transfer",packet));Toast.makeText(getContext(),"Copied complete transfer",Toast.LENGTH_SHORT).show();}
            else if(request&&which==1){dismiss();pasteInvitation.run();}
            else if(request&&which==2){dismiss();startAgain.run();}
            else if(request)showSwap();
            else showOption(new AlertDialog.Builder(getContext()).setTitle("One code at a time").setMessage("Keep the code still until the other phone says Code scanned. Tap Next code here, then Scan next code on the scanning phone. Repeat until all codes are scanned.").setPositiveButton("Got it",null).create());
        }).create());
    }
    private void showOption(AlertDialog dialog){if(optionDialog!=null)optionDialog.dismiss();optionDialog=dialog;dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.show();}
    void information(String title,String caption){infoTitle=title;infoCaption=caption;showCode();}
    @Override public void dismiss(){if(optionDialog!=null){optionDialog.dismiss();optionDialog=null;}super.dismiss();}
    int codeIndex(){return index;}
    String code(){return frames.get(index);}
    ImageView qrView(){return image;}
    private static final class SquareQr extends ImageView {
        private final String frame;
        SquareQr(Context context,String frame){super(context);this.frame=frame;setBackgroundColor(Color.WHITE);setScaleType(ScaleType.FIT_CENTER);setContentDescription("Group transfer QR");}
        @Override protected void onMeasure(int widthSpec,int heightSpec){int side=MeasureSpec.getSize(widthSpec);setMeasuredDimension(side,side);}
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);if(w>0)try{setImageBitmap(MainActivity.qrBitmap(frame,w));}catch(Exception e){setContentDescription("QR unavailable. More → Copy transfer");Toast.makeText(getContext(),"Use More → Copy transfer",Toast.LENGTH_LONG).show();}}
    }
}
