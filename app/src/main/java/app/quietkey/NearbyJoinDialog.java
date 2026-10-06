package app.quietkey;

import android.app.*;
import android.content.*;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Foreground-only cable or local-network joining. No private key or plaintext group seed here. */
final class NearbyJoinDialog extends Dialog implements AutoCloseable {
    static final int PORT=45873;
    interface Callbacks extends LocalJoinSession.Approval {void finished();void stopped();void transportStopped();void permissionPrompt();void permissionReturned();void settings();void scanConnection();void address(String address);}
    private final boolean owner,usb;private final String request;private final Callbacks callbacks;
    private final Handler ui=new Handler(Looper.getMainLooper());private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(PairingProtocol.WINDOW);
    private volatile boolean closed,committed,ended;private volatile Closeable channel;private volatile ServerSocket server;private volatile InputStream input;private volatile OutputStream output;
    private UsbJoinLink cable;private TextView status;private LinearLayout content;private Button retry;
    private byte[] connectionKey;private String code;private volatile boolean requestSeen;
    private EditText addressField,secretField;
    private final String preferredAddress;
    private Dialog addressChooser;
    NearbyJoinDialog(Activity activity,boolean owner,boolean usb,String request,Callbacks callbacks,String preferredAddress){
        super(activity,R.style.AppTheme);this.owner=owner;this.usb=usb;this.request=request;this.callbacks=callbacks;this.preferredAddress=preferredAddress;
    }
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root=column();root.setBackgroundColor(0xff080f1d);root.setPadding(dp(20),dp(12),dp(20),dp(12));
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(20),insets.getSystemWindowInsetTop()+dp(12),dp(20),insets.getSystemWindowInsetBottom()+dp(12));return insets;});
        root.addView(button("Cancel",this::dismiss));root.addView(text((usb?"USB ":"Hotspot ")+(owner?"invite":"join"),23));
        ScrollView scroll=new ScrollView(getContext());content=column();scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        status=text("",16);content.addView(status);setContentView(root);getWindow().setLayout(-1,-1);
        setOnDismissListener(d->{close();callbacks.stopped();});
        ui.postDelayed(()->{if(!closed&&!ended)failure("Joining timed out after five minutes. Cancel and start again for a fresh connection.");},PairingProtocol.WINDOW);
        if(usb)startUsb();else wifiSetup();
    }
    private void startUsb(){
        content.addView(text("1. Both phones: select USB joining.\n2. Connect a USB data cable.\n3. Let one phone control USB and allow Android's USB access prompts.\n4. Owner approves the device; recipient confirms the group.",15));
        content.addView(text("If the phones only charge, try a data cable or an OTG adapter. Personal hotspot is the alternative.",14));
        cable=new UsbJoinLink(getContext(),new UsbJoinLink.Listener(){
            public void ready(InputStream in,OutputStream out){if(closed){try{in.close();out.close();}catch(IOException ignored){}return;}input=in;output=out;io.execute(()->exchange(in,out,null));}
            public void status(String message){NearbyJoinDialog.this.status(message);}
            public void failure(String message){NearbyJoinDialog.this.failure(message);}
            public void permissionPrompt(){callbacks.permissionPrompt();}
            public void permissionReturned(){callbacks.permissionReturned();}
        });cable.start();
    }
    static List<String> addresses()throws SocketException{
        List<String> preferred=new ArrayList<>(),other=new ArrayList<>();Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
        while(interfaces!=null&&interfaces.hasMoreElements()){NetworkInterface iface=interfaces.nextElement();String name=iface.getName().toLowerCase(Locale.ROOT);
            if(!iface.isUp()||iface.isLoopback()||!(name.startsWith("wlan")||name.startsWith("swlan")||name.startsWith("ap")||name.startsWith("wifi")||name.startsWith("eth")||name.startsWith("rndis")))continue;
            Enumeration<InetAddress> values=iface.getInetAddresses();while(values.hasMoreElements()){InetAddress a=values.nextElement();if(a instanceof Inet4Address&&LocalJoinWire.privateAddress(a.getHostAddress()))(name.startsWith("swlan")||name.startsWith("ap")?preferred:other).add(a.getHostAddress());}}
        preferred.addAll(other);return preferred;
    }
    private void wifiSetup(){
        status.setText(owner?"Turn on this phone's hotspot and connect the other phone to it.":"Connect to the owner's hotspot, then scan their connection card. No Internet is needed.");
        content.addView(button("Open connection settings",()->{dismiss();callbacks.settings();}));
        if(owner){try{List<String> addresses=addresses();if(addresses.isEmpty()){status.setText("No hotspot or Wi-Fi address is available. Turn on your personal hotspot, then return and choose this method again.");return;}
                code=LocalJoinWire.newCode();startServer(addresses.contains(preferredAddress)?preferredAddress:addresses.get(0));
            }catch(Exception e){failure("Could not read a local address. Turn on your hotspot and try again.");}
        }else{
            content.addView(button("Scan owner's connection card",callbacks::scanConnection));
            content.addView(button("Paste connection card",()->{try{ClipData clip=((ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();if(clip==null||clip.getItemCount()==0||clip.getItemAt(0).getText()==null)throw new IllegalArgumentException("Copy the owner's connection card first");connectCard(clip.getItemAt(0).getText().toString());}catch(Exception e){status.setText(e.getMessage());}}));
            content.addView(text("Or enter the temporary code below. The hotspot address is filled automatically when available.",14));
            EditText address=addressField=field("Owner address, e.g. 192.168.43.1");address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);String gateway=gateway();if(LocalJoinWire.privateAddress(gateway))address.setText(gateway);content.addView(address);
            EditText secret=secretField=field("Owner's temporary connection code");secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);secret.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});content.addView(secret);
            Button connect=button("Connect to owner",()->{String host=address.getText().toString().trim();if(!LocalJoinWire.privateAddress(host)){address.setError("Enter the owner's private Wi-Fi address");return;}try{connectionKey=LocalJoinWire.codeKey(secret.getText().toString());((android.view.inputmethod.InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(secret.getWindowToken(),0);secret.setText("");secret.setEnabled(false);address.setEnabled(false);startClient(host);}catch(Exception e){secret.setError(e.getMessage());}});content.addView(connect);secret.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);secret.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){connect.performClick();return true;}return false;});
        }
    }
    void connectCard(String packet){
        if(closed||ended||started||owner||usb)return;
        try{ConnectionCard card=ConnectionCard.parse(packet);connectionKey=LocalJoinWire.codeKey(card.code);if(secretField!=null){secretField.setText("");secretField.setEnabled(false);}if(addressField!=null){addressField.setText(card.address);addressField.setEnabled(false);}startClient(card.address);}catch(Exception e){status.setText(e.getMessage());}
    }
    private Network wifiNetwork(){ConnectivityManager manager=(ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE);for(Network network:manager.getAllNetworks()){NetworkCapabilities capabilities=manager.getNetworkCapabilities(network);if(capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return network;}return null;}
    private String gateway(){try{Network network=wifiNetwork();if(network==null)return "";LinkProperties links=((ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE)).getLinkProperties(network);if(links!=null)for(RouteInfo route:links.getRoutes()){InetAddress gateway=route.getGateway();if(gateway instanceof Inet4Address&&LocalJoinWire.privateAddress(gateway.getHostAddress()))return gateway.getHostAddress();}}catch(Exception ignored){}return "";}
    private boolean started;
    private TextView codeView(){TextView view=text(code.substring(0,14)+"\n"+code.substring(15),22);view.setTypeface(android.graphics.Typeface.MONOSPACE);return view;}
    private void startServer(String address){if(started||closed)return;started=true;content.removeAllViews();content.addView(status);status.setText("Starting invitation…");
        try{String card=ConnectionCard.create(address,code);ImageView qr=new ImageView(getContext());int side=Math.max(240,getContext().getResources().getDisplayMetrics().widthPixels-dp(40));com.google.zxing.common.BitMatrix matrix=QrImage.encode(card,side);android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(side,side,android.graphics.Bitmap.Config.ARGB_8888);int[] pixels=new int[side*side];for(int y=0;y<side;y++)for(int x=0;x<side;x++)pixels[y*side+x]=matrix.get(x,y)?0xff000000:0xffffffff;bitmap.setPixels(pixels,0,side,0,0,side,side);qr.setImageBitmap(bitmap);qr.setContentDescription("Temporary hotspot connection card");qr.setAdjustViewBounds(true);content.addView(qr,new LinearLayout.LayoutParams(-1,-2));
            content.addView(text("Other phone: Scan owner's connection card.",15));
            content.addView(button("Copy connection card",()->{ClipData clip=ClipData.newPlainText("Enigma temporary connection",card);if(Build.VERSION.SDK_INT>=33){PersistableBundle extras=new PersistableBundle();extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true);clip.getDescription().setExtras(extras);}((ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(clip);}));
        }catch(Exception e){content.addView(text("Use the address and code below to connect.",14));}
        content.addView(text("Manual connection · "+address,16));content.addView(codeView());content.addView(text("Private code · expires in five minutes. Keep both apps open.",14));
        content.addView(button("Open connection settings",()->{dismiss();callbacks.settings();}));
        content.addView(button("Use another local address",()->{try{List<String> available=addresses();addressChooser=new AlertDialog.Builder(getContext()).setTitle("Choose the hotspot address").setItems(available.toArray(new String[0]),(d,w)->callbacks.address(available.get(w))).setNegativeButton("Cancel",null).create();addressChooser.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);addressChooser.show();}catch(Exception e){status(e.getMessage());}}));
        io.execute(()->{try{connectionKey=LocalJoinWire.codeKey(code);ServerSocket opened=new ServerSocket();server=opened;if(closed){opened.close();return;}opened.setReuseAddress(true);opened.bind(new InetSocketAddress(InetAddress.getByAddress(LocalJoinWire.addressBytes(address)),PORT));opened.setSoTimeout(1000);
                status("Ready · waiting for the other phone.");
                int failed=0;while(!closed&&System.nanoTime()<deadline){Socket socket;try{socket=opened.accept();}catch(SocketTimeoutException e){continue;}channel=socket;socket.setSoTimeout(10000);
                    try(LocalJoinWire wire=new LocalJoinWire(socket.getInputStream(),socket.getOutputStream(),true,connectionKey)){
                        String packet=wire.receive(LocalJoinWire.REQUEST);PairingProtocol.parseRequest(packet);requestSeen=true;socket.setSoTimeout((int)PairingProtocol.WINDOW);
                        String invitation=callbacks.invite(packet).get(remaining(),TimeUnit.NANOSECONDS);wire.send(LocalJoinWire.INVITATION,invitation);status("Invitation sent. Waiting for them to join…");
                        callbacks.receipt(wire.receive(LocalJoinWire.RECEIPT)).get(remaining(),TimeUnit.NANOSECONDS);wire.send(LocalJoinWire.DONE,"Joined");complete();return;
                    }catch(Exception e){if(closed)return;if(requestSeen)throw e;if(++failed>=5)throw new IOException("Too many failed connections");status("Connection code did not match or request was incomplete. Waiting for the recipient to retry…");}
                    finally{socket.close();channel=null;}
                }if(!closed)failure("Joining expired. Cancel and start again.");
            }catch(Exception e){if(!closed)failure("Could not complete hotspot joining. Check both phones use the same hotspot and code. Cancel and start again; any saved group remains saved.");}finally{closeConnections();}});
    }
    private long remaining()throws TimeoutException{long n=deadline-System.nanoTime();if(n<=0)throw new TimeoutException("Joining expired");return n;}
    private void startClient(String address){if(started||closed)return;started=true;status("Connecting to owner…");io.execute(()->{try{Socket socket=new Socket();channel=socket;if(closed){socket.close();return;}Network wifi=wifiNetwork();if(wifi==null)throw new IOException("Connect to the owner's Wi-Fi hotspot first");wifi.bindSocket(socket);socket.connect(new InetSocketAddress(InetAddress.getByAddress(LocalJoinWire.addressBytes(address)),PORT),10000);socket.setSoTimeout((int)PairingProtocol.WINDOW);exchange(socket.getInputStream(),socket.getOutputStream(),connectionKey);}catch(Exception e){if(!closed)failure("Could not connect. Check both phones use the owner's hotspot and that the invitation is still open. Stay connected even if Android says No Internet.");}});}
    private void exchange(InputStream in,OutputStream out,byte[] key){
        try(LocalJoinWire wire=new LocalJoinWire(in,out,owner,key)){LocalJoinSession.exchange(wire,owner,request,callbacks,deadline);complete();}
        catch(Exception e){if(!closed)failure(committed?"Group saved on this phone. The owner's confirmation was interrupted; your first verified message also confirms joining.":"Joining interrupted. Check the cable or hotspot and retry. No invitation is accepted without verification and your confirmation.");}
        finally{closeConnections();}
    }
    void markCommitted(){committed=true;}
    void status(String message){ui.post(()->{if(!closed)status.setText(message);});}
    private void complete(){ui.post(()->{if(closed||ended)return;stopTransport();content.removeAllViews();content.addView(status);status.setText("Joined successfully. Group details and all member public keys are ready.");content.addView(button("Done",()->{dismiss();callbacks.finished();}));});}
    void failure(String message){ui.post(()->{if(closed||ended)return;stopTransport();content.removeAllViews();content.addView(status);status.setText(message);retry=button("Back to group",()->{dismiss();callbacks.finished();});content.addView(retry);});}
    private void stopTransport(){ended=true;if(addressChooser!=null){addressChooser.dismiss();addressChooser=null;}if(cable!=null)cable.close();closeConnections();if(connectionKey!=null)Arrays.fill(connectionKey,(byte)0);connectionKey=null;code=null;io.shutdownNow();callbacks.transportStopped();}
    private void closeConnections(){Closeable c=channel;channel=null;if(c!=null)try{c.close();}catch(IOException ignored){}ServerSocket s=server;server=null;if(s!=null)try{s.close();}catch(IOException ignored){}InputStream in=input;input=null;if(in!=null)try{in.close();}catch(IOException ignored){}OutputStream out=output;output=null;if(out!=null)try{out.close();}catch(IOException ignored){}}
    @Override public void close(){if(closed)return;closed=true;ui.removeCallbacksAndMessages(null);stopTransport();}
    boolean closed(){return closed||ended;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView text(String value,int size){TextView v=new TextView(getContext());v.setText(value);v.setTextColor(0xffedf5ff);v.setTextSize(size);v.setPadding(0,dp(8),0,dp(8));return v;}
    private EditText field(String hint){EditText v=new EditText(getContext());v.setTextColor(0xffedf5ff);v.setHintTextColor(0xff93a9bf);v.setHint(hint);v.setSingleLine(true);return v;}
    private Button button(String text,Runnable action){Button v=new Button(getContext());v.setText(text);v.setAllCaps(false);boolean primary=text.equals("Scan owner's connection card")||text.equals("Connect to owner")||text.equals("Done");v.setTextColor(primary?0xff080f1d:0xffedf5ff);v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?0xff4be3c1:0xff20344b));v.setOnClickListener(w->action.run());return v;}
    private int dp(int n){return Math.round(n*getContext().getResources().getDisplayMetrics().density);}
}
