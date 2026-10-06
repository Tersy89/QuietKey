package app.quietkey;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Either phone may be the USB host. Only AOA data endpoints are used, never ADB. */
final class UsbJoinLink implements AutoCloseable {
    interface Listener {void ready(InputStream in,OutputStream out);void status(String text);void failure(String text);void permissionPrompt();void permissionReturned();}
    private final Context context;private final UsbManager manager;private final Listener listener;
    private final Handler ui=new Handler(Looper.getMainLooper());private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final String permission;
    private final PendingIntent permissionResult;
    private final Set<String> attempted=new HashSet<>();private final AtomicBoolean opening=new AtomicBoolean();
    private volatile boolean closed,ready;private boolean permissionPending;
    private volatile UsbDeviceConnection connection;private volatile ParcelFileDescriptor descriptor;
    private volatile String deviceName;private volatile UsbAccessory accessory;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){
        if(closed)return;String action=intent.getAction();
        if(permission.equals(action)){permissionPending=false;listener.permissionReturned();if(!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)){listener.failure("USB access was declined. Cancel, choose USB again and allow access on both phones.");return;}poll();}
        else if(ready&&UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)){UsbDevice device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);if(device!=null&&device.getDeviceName().equals(deviceName))listener.failure("Cable disconnected. If the group is already saved, it stays saved. Reconnect to retry.");}
        else if(ready&&UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)){UsbAccessory removed=intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);if(removed!=null&&removed.equals(accessory))listener.failure("Cable disconnected. If the group is already saved, it stays saved. Reconnect to retry.");}
    }};
    private final Runnable tick=new Runnable(){public void run(){if(closed||ready)return;poll();ui.postDelayed(this,1000);}};
    UsbJoinLink(Context context,Listener listener){
        this.context=context;this.listener=listener;manager=(UsbManager)context.getSystemService(Context.USB_SERVICE);
        permission=context.getPackageName()+".USB_JOIN_PERMISSION."+UUID.randomUUID();IntentFilter filter=new IntentFilter(permission);
        // UsbManager fills in the grant/device extras: immutable intents discard them.
        // Scope this capability to our package + random action and cancel it on close.
        permissionResult=PendingIntent.getBroadcast(context,0,new Intent(permission).setPackage(context.getPackageName()),Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);else context.registerReceiver(receiver,filter);
    }
    void start(){listener.status("Connect a data cable. Open USB joining on the other phone too. Allow USB access when Android asks.");ui.post(tick);}
    PendingIntent permissionIntent(){return permissionResult;}
    private void poll(){
        if(closed||ready||permissionPending||opening.get())return;
        try{
            UsbAccessory[] accessories=manager.getAccessoryList();if(accessories!=null)for(UsbAccessory a:accessories){
                if(!"Enigma".equals(a.getManufacturer())||!"Enigma Join".equals(a.getModel()))continue;
                if(!manager.hasPermission(a)){permissionPending=true;listener.permissionPrompt();manager.requestPermission(a,permissionIntent());return;}
                if(opening.compareAndSet(false,true))work.execute(()->openAccessory(a));return;
            }
            List<UsbDevice> candidates=new ArrayList<>();for(UsbDevice d:manager.getDeviceList().values())if(d.getDeviceClass()!=UsbConstants.USB_CLASS_HID&&d.getDeviceClass()!=UsbConstants.USB_CLASS_MASS_STORAGE)candidates.add(d);
            if(candidates.size()>1){listener.status("Disconnect other USB devices. Connect only the phone you are joining with.");return;}
            if(candidates.isEmpty())return;UsbDevice d=candidates.get(0);if(attempted.contains(d.getDeviceName()))return;
            if(!manager.hasPermission(d)){permissionPending=true;listener.permissionPrompt();manager.requestPermission(d,permissionIntent());return;}
            attempted.add(d.getDeviceName());if(opening.compareAndSet(false,true))work.execute(()->openHost(d));
        }catch(Exception e){listener.failure("USB could not start. Reconnect, allow USB access, or use Personal hotspot.");}
    }
    private void openAccessory(UsbAccessory a){
        try{ParcelFileDescriptor fd=manager.openAccessory(a);if(fd==null)throw new IOException("Android did not open the accessory");descriptor=fd;accessory=a;
            // Duplicate handles: closing one stream must not silently invalidate the other.
            // AOA reads must accommodate a complete logical USB packet (up to 16 KiB),
            // even when the framing parser asks for a single header byte.
            InputStream in=new BufferedInputStream(new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.getFileDescriptor())),16384);
            OutputStream raw=new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.getFileDescriptor()));
            OutputStream out=new FilterOutputStream(raw){@Override public void write(byte[] data,int off,int count)throws IOException{while(count>0){int n=Math.min(count,16384);raw.write(data,off,n);off+=n;count-=n;}}};
            if(closed){in.close();out.close();fd.close();return;}ready=true;listener.ready(in,out);
        }catch(Exception e){listener.failure("USB accessory connection failed. Reconnect or try Personal hotspot.");}finally{opening.set(false);}
    }
    private void openHost(UsbDevice d){
        UsbDeviceConnection c=null;
        try{
            c=manager.openDevice(d);if(c==null)throw new IOException("USB access unavailable");connection=c;if(closed){c.close();return;}
            if(d.getVendorId()!=0x18d1||(d.getProductId()!=0x2d00&&d.getProductId()!=0x2d01)){
                byte[] protocol=new byte[2];int n=c.controlTransfer(0xc0,51,0,0,protocol,2,2000);
                if(n!=2||((protocol[0]&255)|((protocol[1]&255)<<8))<1)throw new IOException("This connection does not support Android accessory mode");
                String[] identity={"Enigma","Enigma Join","Private group joining","1","","Enigma-local-join"};
                for(int i=0;i<identity.length;i++){byte[] value=(identity[i]+"\0").getBytes(StandardCharsets.UTF_8);if(c.controlTransfer(0x40,52,0,i,value,value.length,2000)!=value.length)throw new IOException("Accessory setup failed");}
                if(c.controlTransfer(0x40,53,0,0,null,0,2000)<0)throw new IOException("Accessory mode could not start");
                c.close();connection=null;listener.status("USB connected. Allow accessory access on the other phone…");return;
            }
            // AOA interface zero is app data. Interface one, when present, is ADB and is never opened.
            UsbInterface iface=d.getInterface(0);UsbEndpoint input=null,output=null;
            for(int i=0;i<iface.getEndpointCount();i++){UsbEndpoint endpoint=iface.getEndpoint(i);if(endpoint.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK){if(endpoint.getDirection()==UsbConstants.USB_DIR_IN)input=endpoint;else output=endpoint;}}
            if(input==null||output==null||!c.claimInterface(iface,true))throw new IOException("No accessory data endpoints");
            deviceName=d.getDeviceName();ready=true;listener.ready(new BulkInput(c,input),new BulkOutput(c,output));
        }catch(Exception e){if(c!=null)c.close();connection=null;listener.failure("USB joining is unavailable on this connection. Use a data cable, let one phone control USB, and retry; or choose Personal hotspot.");}finally{opening.set(false);}
    }
    private final class BulkInput extends InputStream {
        final UsbDeviceConnection c;final UsbEndpoint endpoint;final byte[] buffer=new byte[16384];int position,count;
        BulkInput(UsbDeviceConnection c,UsbEndpoint endpoint){this.c=c;this.endpoint=endpoint;}
        @Override public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
        @Override public int read(byte[] target,int off,int length)throws IOException{
            if(off<0||length<0||off>target.length-length)throw new IndexOutOfBoundsException();if(length==0)return 0;
            while(position==count){if(closed)throw new IOException("USB connection closed");count=c.bulkTransfer(endpoint,buffer,buffer.length,1000);position=0;if(count<=0){count=0;if(deviceName!=null&&!manager.getDeviceList().containsKey(deviceName))throw new IOException("USB device disconnected");continue;}}
            int copied=Math.min(length,count-position);System.arraycopy(buffer,position,target,off,copied);position+=copied;return copied;
        }
    }
    private final class BulkOutput extends OutputStream {
        final UsbDeviceConnection c;final UsbEndpoint endpoint;
        BulkOutput(UsbDeviceConnection c,UsbEndpoint endpoint){this.c=c;this.endpoint=endpoint;}
        @Override public void write(int value)throws IOException{write(new byte[]{(byte)value});}
        @Override public void write(byte[] source,int off,int length)throws IOException{
            if(off<0||length<0||off>source.length-length)throw new IndexOutOfBoundsException();while(length>0){if(closed)throw new IOException("USB connection closed");int n=c.bulkTransfer(endpoint,source,off,Math.min(length,16384),3000);if(n<=0)throw new IOException("USB write failed");off+=n;length-=n;}
        }
    }
    @Override public void close(){if(closed)return;closed=true;permissionResult.cancel();ui.removeCallbacks(tick);try{context.unregisterReceiver(receiver);}catch(Exception ignored){}UsbDeviceConnection c=connection;if(c!=null)c.close();ParcelFileDescriptor fd=descriptor;if(fd!=null)try{fd.close();}catch(IOException ignored){}work.shutdownNow();}
}
