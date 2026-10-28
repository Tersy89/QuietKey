package app.quietkey;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.*;
import android.net.wifi.*;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Foreground-only nearby joining. The owner starts a temporary local-only hotspot (or uses the
 * current Wi-Fi) and shows one QKB1 code as a single QR and over an NFC tap. The recipient scans or
 * taps, connects, and both run the existing request → recipient-encrypted invitation → receipt
 * exchange inside frames keyed by the one-time KEK. No private key or plaintext group seed is here.
 */
final class NearbyJoinDialog extends Dialog implements AutoCloseable {
    static final int PORT=45873;
    interface Callbacks {
        CompletableFuture<String> invite(String request,String checkCode);
        CompletableFuture<String> accept(String invitation,String checkCode,JoinBootstrap pinned);
        CompletableFuture<Void> receipt(String receipt);
        void scanQr();void needPermission(Runnable retry);void handoff(boolean start);
        void finished();void stopped();void transportStopped();
    }
    private final Activity activity;private final boolean owner,sameWifi;private final String request;private final Callbacks callbacks;
    private final Handler ui=new Handler(Looper.getMainLooper());private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(PairingProtocol.WINDOW);
    private volatile boolean closed,committed,ended,requestSeen,started;private volatile Closeable channel;private volatile ServerSocket server;
    private WifiManager.LocalOnlyHotspotReservation reservation;private ConnectivityManager.NetworkCallback networkCallback;private boolean readerOn;
    private volatile JoinBootstrap bootstrap;private TextView status;private LinearLayout content;
    NearbyJoinDialog(Activity activity,boolean owner,boolean sameWifi,String request,Callbacks callbacks){
        super(activity,R.style.AppTheme);this.activity=activity;this.owner=owner;this.sameWifi=sameWifi;this.request=request;this.callbacks=callbacks;
    }
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=column();root.setBackgroundColor(0xff080f1d);root.setPadding(dp(20),dp(12),dp(20),dp(12));
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(20),insets.getSystemWindowInsetTop()+dp(12),dp(20),insets.getSystemWindowInsetBottom()+dp(12));return insets;});
        root.addView(button("Cancel",this::dismiss));root.addView(text(owner?"Invite nearby":"Join nearby",23));
        ScrollView scroll=new ScrollView(getContext());content=column();scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        status=text("",16);content.addView(status);setContentView(root);getWindow().setLayout(-1,-1);
        setOnDismissListener(d->{close();callbacks.stopped();});
        ui.postDelayed(()->{if(!closed&&!ended)failure("Joining timed out after five minutes. Start again for a fresh code.");},PairingProtocol.WINDOW);
        if(owner){if(sameWifi)startSameWifi();else startHotspot();}else recipientChoices();
    }

    // ---- Owner ----
    private void startHotspot(){
        status.setText("Starting a private hotspot for joining…");
        WifiManager wifi=(WifiManager)getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);Set<String> before=new HashSet<>(addresses());
        try{wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback(){
            @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation r){if(closed||ended){r.close();return;}reservation=r;hotspotReady(r,before,System.currentTimeMillis()+6000);}
            @Override public void onStopped(){if(!ended&&!closed)failure("The joining hotspot stopped. Start again.");}
            @Override public void onFailed(int reason){failure(reason==ERROR_INCOMPATIBLE_MODE?"Turn off your personal hotspot or tethering, then invite again.":reason==ERROR_TETHERING_DISALLOWED?"This phone does not allow hotspots. Use Same Wi-Fi or Manual transfer.":"Could not start the joining hotspot. Turn Wi-Fi on and try again, or use Same Wi-Fi.");}
        },ui);}
        catch(SecurityException e){failure(Build.VERSION.SDK_INT<33?"Android needs Location permission and Location turned on to start a joining hotspot. Allow it and try again.":"Allow Nearby devices permission and try again.");}
        catch(Exception e){failure("Could not start the joining hotspot: "+e.getMessage());}
    }
    @SuppressWarnings("deprecation")
    private void hotspotReady(WifiManager.LocalOnlyHotspotReservation r,Set<String> before,long until){
        if(closed||ended)return;
        String ssid,pass;int security=JoinBootstrap.WPA2;
        if(Build.VERSION.SDK_INT>=30){SoftApConfiguration c=r.getSoftApConfiguration();ssid=c.getSsid();pass=c.getPassphrase();if(c.getSecurityType()==SoftApConfiguration.SECURITY_TYPE_WPA3_SAE)security=JoinBootstrap.WPA3;}
        else{WifiConfiguration c=r.getWifiConfiguration();ssid=c==null?null:c.SSID;pass=c==null?null:c.preSharedKey;}
        ssid=unquote(ssid);pass=unquote(pass);
        if(ssid.isEmpty()||pass.isEmpty()){failure("The joining hotspot has no password. Use Same Wi-Fi or Manual transfer.");return;}
        List<String> now=addresses(),fresh=new ArrayList<>();for(String a:now)if(!before.contains(a))fresh.add(a);
        String address=!fresh.isEmpty()?fresh.get(0):null;
        if(address==null&&System.currentTimeMillis()<until){ui.postDelayed(()->hotspotReady(r,before,until),300);return;}
        if(address==null)for(String a:now)if(a.endsWith(".1"))address=a;
        if(address==null){failure("The joining hotspot started but has no address yet. Start again.");return;}
        final String ownerAddress=address,hotspotSsid=ssid,hotspotPass=pass;final int sec=security;
        listen(ownerAddress,port->JoinBootstrap.hotspot(hotspotSsid,hotspotPass,sec,ownerAddress,port,ownerFingerprint()));
    }
    private void startSameWifi(){
        status.setText("Preparing to invite over this Wi-Fi network…");
        Network wifi=wifiNetwork();String address=wifi==null?null:networkAddress(wifi);
        if(address==null){failure("Connect this phone to Wi-Fi first, or use the private hotspot method.");return;}
        listen(address,port->JoinBootstrap.sameWifi(address,port,ownerFingerprint()));
    }
    private interface BootstrapFactory{JoinBootstrap create(int port)throws Exception;}
    private String ownerFingerprint(){return ((MainActivity)activity).ownFingerprint();}
    private void listen(String address,BootstrapFactory factory){
        io.execute(()->{try{
            ServerSocket opened=new ServerSocket();server=opened;if(closed){opened.close();return;}opened.setReuseAddress(true);
            InetAddress bind=InetAddress.getByAddress(LocalJoinWire.addressBytes(address));
            try{opened.bind(new InetSocketAddress(bind,PORT));}catch(IOException busy){opened.bind(new InetSocketAddress(bind,0));}
            opened.setSoTimeout(1000);JoinBootstrap boot=factory.create(opened.getLocalPort());bootstrap=boot;String code=boot.encode();
            ui.post(()->showOwnerCode(code));byte[] key=boot.transportKey();
            try{int failed=0;while(!closed&&!ended&&System.nanoTime()<deadline){Socket socket;try{socket=opened.accept();}catch(SocketTimeoutException e){continue;}channel=socket;socket.setSoTimeout(10000);
                try(LocalJoinWire wire=new LocalJoinWire(socket.getInputStream(),socket.getOutputStream(),true,key)){
                    String packet=wire.receive(LocalJoinWire.REQUEST);PairingProtocol.parseRequest(packet);requestSeen=true;JoinNfcService.clear();socket.setSoTimeout((int)PairingProtocol.WINDOW);
                    status("Check that the other phone shows the same check code, then confirm.");
                    String invitation=callbacks.invite(packet,boot.checkCode(packet)).get(remaining(),TimeUnit.NANOSECONDS);wire.send(LocalJoinWire.INVITATION,invitation);status("Invitation sent. Waiting for them to join…");
                    callbacks.receipt(wire.receive(LocalJoinWire.RECEIPT)).get(remaining(),TimeUnit.NANOSECONDS);wire.send(LocalJoinWire.DONE,"Joined");complete();return;
                }catch(Exception e){if(closed)return;if(requestSeen)throw e;if(++failed>=5)throw new IOException("Too many failed connections");status("A connection failed verification. Waiting for the recipient to scan again…");}
                finally{socket.close();channel=null;}
            }if(!closed)failure("Joining expired. Start again.");}finally{Arrays.fill(key,(byte)0);}
        }catch(Exception e){if(!closed)failure(requestSeen?"Joining interrupted. Any approved member stays Invited until their first verified message or a fresh attempt.":"Could not complete nearby joining. Start again, or use Manual transfer.");}finally{closeConnections();}});
    }
    private void showOwnerCode(String code){
        if(closed||ended)return;content.removeAllViews();content.addView(status);
        status.setText("On the other phone: Groups → Join group → Scan or tap. Scan this code"+(nfc()!=null?", or hold the phones back to back.":"."));
        try{Bitmap qr=qrBitmap(code,Math.min(getContext().getResources().getDisplayMetrics().widthPixels-dp(40),dp(340)));ImageView image=new ImageView(getContext());image.setImageBitmap(qr);image.setAdjustViewBounds(true);content.addView(image,new LinearLayout.LayoutParams(-1,-2));}
        catch(Exception e){status.setText("Could not draw the joining code. Use NFC or Manual transfer.");}
        if(nfc()!=null){JoinNfcService.serve(code);content.addView(text("NFC: ready to tap",14));}
        content.addView(text(sameWifi?"Both phones must stay on the same Wi-Fi network.":"This phone briefly runs a private hotspot with no Internet. It may leave its normal Wi-Fi until joining ends.",14));
        content.addView(text("The code works once and expires in five minutes. Only show it to the person joining.",14));
    }

    // ---- Recipient ----
    private void recipientChoices(){
        status.setText("Scan the owner's joining code"+(nfc()!=null?", or hold your phone against theirs.":"."));
        content.addView(button("Scan owner's code",callbacks::scanQr));
        NfcAdapter adapter=nfc();
        if(adapter!=null&&adapter.isEnabled()){readerOn=true;adapter.enableReaderMode(activity,this::tapped,NfcAdapter.FLAG_READER_NFC_A|NfcAdapter.FLAG_READER_NFC_B|NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK|NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,null);content.addView(text("NFC: hold the phones back to back.",14));}
        else if(adapter!=null)content.addView(text("Turn on NFC in Android settings to join by tapping.",14));
    }
    private void tapped(Tag tag){
        try{String code=JoinNfcService.read(IsoDep.get(tag));ui.post(()->bootstrap(code));}
        catch(Exception e){status("NFC read failed. Hold the phones still, back to back, or scan the code.");}
    }
    /** Called with a scanned or tapped QKB1 code. */
    void bootstrap(String code){
        if(owner||started||closed||ended)return;
        JoinBootstrap b;try{b=JoinBootstrap.parse(code);}catch(Exception e){status(e.getMessage()==null?"That is not an Enigma joining code.":e.getMessage());return;}
        started=true;bootstrap=b;stopReader();
        String check;try{check=b.checkCode(request);}catch(Exception e){failure("Could not prepare joining.");return;}
        content.removeAllViews();content.addView(status);content.addView(text("Check code",14));TextView shown=text(check,34);shown.setTypeface(android.graphics.Typeface.MONOSPACE);content.addView(shown);
        content.addView(text("The owner's phone must show the same code before they tap Invite.",14));
        if(b.mode==JoinBootstrap.SAME_WIFI){Network n=wifiNetwork();if(n==null){failure("Connect to the same Wi-Fi network as the owner, then scan again.");return;}status("Connecting to the owner…");io.execute(()->connect(n,b,check));}
        else if(Build.VERSION.SDK_INT>=29)requestHotspot(b,check);
        else{status("In Android Wi-Fi settings, connect to \""+b.ssid+"\" with password "+b.passphrase+" , then return and tap Continue.");content.addView(button("Continue",()->{Network n=wifiNetwork();if(n==null){status("Not connected to \""+b.ssid+"\" yet.");return;}io.execute(()->connect(n,b,check));}));}
    }
    @androidx.annotation.RequiresApi(29)
    private void requestHotspot(JoinBootstrap b,String check){
        status("Connecting to the owner's joining hotspot. Allow the connection if Android asks.");
        WifiNetworkSpecifier.Builder spec=new WifiNetworkSpecifier.Builder().setSsid(b.ssid);if(b.security==JoinBootstrap.WPA3)spec.setWpa3Passphrase(b.passphrase);else spec.setWpa2Passphrase(b.passphrase);
        NetworkRequest req=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec.build()).build();
        ConnectivityManager manager=(ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback=new ConnectivityManager.NetworkCallback(){
            private boolean used;
            @Override public void onAvailable(Network n){callbacks.handoff(false);if(used||closed||ended)return;used=true;status("Connected. Sending your joining request…");io.execute(()->connect(n,b,check));}
            @Override public void onUnavailable(){callbacks.handoff(false);failure("Could not connect to the owner's joining hotspot. Keep the phones close and start again.");}
            @Override public void onLost(Network n){if(!ended&&!committed)failure("Lost the connection to the owner's phone. Start again.");}
        };
        try{callbacks.handoff(true);manager.requestNetwork(req,networkCallback,60000);}
        catch(SecurityException e){networkCallback=null;callbacks.handoff(false);started=false;callbacks.needPermission(()->{started=true;requestHotspot(b,check);});}
    }
    private void connect(Network network,JoinBootstrap b,String check){
        List<String> targets=new ArrayList<>(Collections.singletonList(b.address));String gateway=gateway(network);if(gateway!=null&&!targets.contains(gateway))targets.add(gateway);
        Exception last=null;
        for(String target:targets){Socket socket=new Socket();channel=socket;try{if(closed)return;network.bindSocket(socket);socket.connect(new InetSocketAddress(InetAddress.getByAddress(LocalJoinWire.addressBytes(target)),b.port),10000);socket.setSoTimeout((int)PairingProtocol.WINDOW);
                exchange(socket.getInputStream(),socket.getOutputStream(),b,check);return;}
            catch(IOException e){last=e;try{socket.close();}catch(IOException ignored){}}}
        if(!closed)failure("Could not reach the owner's phone. Make sure their invite screen is still open, then scan again."+(last==null?"":""));
    }
    private void exchange(InputStream in,OutputStream out,JoinBootstrap b,String check){
        byte[] key=null;
        try{key=b.transportKey();try(LocalJoinWire wire=new LocalJoinWire(in,out,false,key)){
            LocalJoinSession.exchange(wire,false,request,new LocalJoinSession.Approval(){
                public CompletableFuture<String> invite(String r){throw new SecurityException("Unexpected owner step");}
                public CompletableFuture<String> accept(String invitation){return callbacks.accept(invitation,check,b);}
                public CompletableFuture<Void> receipt(String r){throw new SecurityException("Unexpected owner step");}
                public void status(String text){NearbyJoinDialog.this.status(text);}
            },deadline);complete();}}
        catch(Exception e){if(!closed)failure(committed?"Group saved on this phone. The owner's confirmation was interrupted; your first verified message also confirms joining.":"Joining interrupted. Nothing was saved unless you confirmed. Start again for a fresh code.");}
        finally{if(key!=null)Arrays.fill(key,(byte)0);closeConnections();}
    }

    // ---- Shared ----
    private static Bitmap qrBitmap(String text,int side)throws Exception{
        com.google.zxing.common.BitMatrix m=QrImage.encode(text,side);int w=m.getWidth(),h=m.getHeight();int[] px=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)px[y*w+x]=m.get(x,y)?0xff000000:0xffffffff;
        return Bitmap.createBitmap(px,w,h,Bitmap.Config.ARGB_8888);
    }
    private NfcAdapter nfc(){try{return NfcAdapter.getDefaultAdapter(getContext());}catch(Exception e){return null;}}
    private void stopReader(){if(readerOn){readerOn=false;try{NfcAdapter a=nfc();if(a!=null)a.disableReaderMode(activity);}catch(Exception ignored){}}}
    private static String unquote(String s){if(s==null)return "";if(s.length()>=2&&s.startsWith("\"")&&s.endsWith("\""))return s.substring(1,s.length()-1);return s;}
    static List<String> addresses(){
        List<String> out=new ArrayList<>();try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces!=null&&interfaces.hasMoreElements()){NetworkInterface iface=interfaces.nextElement();String name=iface.getName().toLowerCase(Locale.ROOT);
                if(!iface.isUp()||iface.isLoopback()||!(name.startsWith("wlan")||name.startsWith("swlan")||name.startsWith("ap")||name.startsWith("wifi")||name.startsWith("softap")))continue;
                Enumeration<InetAddress> values=iface.getInetAddresses();while(values.hasMoreElements()){InetAddress a=values.nextElement();if(a instanceof Inet4Address&&LocalJoinWire.privateAddress(a.getHostAddress()))out.add(a.getHostAddress());}}}catch(SocketException ignored){}
        return out;
    }
    private Network wifiNetwork(){ConnectivityManager manager=(ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE);for(Network network:manager.getAllNetworks()){NetworkCapabilities capabilities=manager.getNetworkCapabilities(network);if(capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return network;}return null;}
    private String networkAddress(Network network){LinkProperties links=((ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE)).getLinkProperties(network);if(links!=null)for(LinkAddress a:links.getLinkAddresses()){InetAddress ip=a.getAddress();if(ip instanceof Inet4Address&&LocalJoinWire.privateAddress(ip.getHostAddress()))return ip.getHostAddress();}return null;}
    private String gateway(Network network){try{LinkProperties links=((ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE)).getLinkProperties(network);if(links!=null)for(RouteInfo route:links.getRoutes()){InetAddress g=route.getGateway();if(g instanceof Inet4Address&&LocalJoinWire.privateAddress(g.getHostAddress()))return g.getHostAddress();}}catch(Exception ignored){}return null;}
    private long remaining()throws TimeoutException{long n=deadline-System.nanoTime();if(n<=0)throw new TimeoutException("Joining expired");return n;}
    void markCommitted(){committed=true;}
    void status(String message){ui.post(()->{if(!closed)status.setText(message);});}
    private void complete(){ui.post(()->{if(closed||ended)return;stopTransport();content.removeAllViews();content.addView(status);status.setText("Joined successfully. Group details and all member public keys are ready.");content.addView(button("Done",()->{dismiss();callbacks.finished();}));});}
    void failure(String message){ui.post(()->{if(closed||ended)return;stopTransport();content.removeAllViews();content.addView(status);status.setText(message);content.addView(button("Back",()->{dismiss();callbacks.finished();}));});}
    private void stopTransport(){
        ended=true;JoinNfcService.clear();stopReader();closeConnections();
        if(reservation!=null){try{reservation.close();}catch(Exception ignored){}reservation=null;}
        if(networkCallback!=null){try{((ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}networkCallback=null;}
        JoinBootstrap b=bootstrap;bootstrap=null;if(b!=null)b.clear();io.shutdownNow();callbacks.transportStopped();
    }
    private void closeConnections(){Closeable c=channel;channel=null;if(c!=null)try{c.close();}catch(IOException ignored){}ServerSocket s=server;server=null;if(s!=null)try{s.close();}catch(IOException ignored){}}
    @Override public void close(){if(closed)return;closed=true;ui.removeCallbacksAndMessages(null);if(!ended)stopTransport();else{JoinNfcService.clear();stopReader();}}
    boolean closed(){return closed||ended;}
    boolean recipient(){return !owner;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView text(String value,int size){TextView v=new TextView(getContext());v.setText(value);v.setTextColor(0xffedf5ff);v.setTextSize(size);v.setPadding(0,dp(8),0,dp(8));return v;}
    private Button button(String text,Runnable action){Button v=new Button(getContext());v.setText(text);v.setAllCaps(false);boolean primary=text.equals("Scan owner's code")||text.equals("Continue")||text.equals("Done");v.setTextColor(primary?0xff080f1d:0xffedf5ff);v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?0xff4be3c1:0xff20344b));v.setOnClickListener(w->action.run());return v;}
    private int dp(int n){return Math.round(n*getContext().getResources().getDisplayMetrics().density);}
}
