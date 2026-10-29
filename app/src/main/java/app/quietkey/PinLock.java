package app.quietkey;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the {@link PinBox} state encrypted under the device Keystore key, counts wrong PINs and
 * wipes Enigma after {@link PinBox#MAX_ATTEMPTS} wrong PINs or on the panic PIN (decision 12).
 * Each attempt is counted on disk before the PIN is checked, so closing the app does not reset it.
 */
public final class PinLock {
    /** A wrong PIN; {@link #remaining} attempts are left before the wipe. */
    public static final class WrongPin extends SecurityException{public final int remaining;WrongPin(int remaining){super(remaining==1?"Wrong PIN. 1 attempt left before Enigma erases itself.":"Wrong PIN. "+remaining+" attempts left before Enigma erases itself.");this.remaining=remaining;}}
    /** Enigma's keys and data were erased (panic PIN or too many wrong PINs). */
    public static final class Wiped extends SecurityException{Wiped(){super("Enigma was erased.");}}

    private static final String DEVICE="quietkey.vault.v1";
    private static final byte[] AAD="Enigma/pin-state/v1".getBytes(StandardCharsets.UTF_8);
    private final Context context;private final AtomicFile file;
    public PinLock(Context c){context=c;file=new AtomicFile(new File(c.getFilesDir(),"pin.qkp"));}
    public boolean configured(){return file.getBaseFile().exists()||new File(file.getBaseFile()+".bak").exists();}
    private SecretKey deviceKey()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);SecretKey k=(SecretKey)ks.getKey(DEVICE,null);if(k==null)throw new SecurityException("Device key unavailable");return k;}
    public PinBox.State state()throws Exception{
        byte[] bytes=file.readFully();if(bytes.length>1000)throw new SecurityException("Invalid PIN file");DataInputStream in=Packets.input(bytes);byte[] nonce=Packets.blob(in,12),cipher=Packets.blob(in,900);Packets.end(in);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,deviceKey(),new GCMParameterSpec(128,nonce));c.updateAAD(AAD);
        return PinBox.State.decode(c.doFinal(cipher));
    }
    private void write(PinBox.State s)throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,deviceKey());c.updateAAD(AAD);byte[] cipher=c.doFinal(s.encode()),bytes=Packets.pack(o->{Packets.blob(o,c.getIV());Packets.blob(o,cipher);});
        FileOutputStream out=null;try{out=file.startWrite();out.write(bytes);out.getFD().sync();file.finishWrite(out);if(!Arrays.equals(bytes,file.readFully()))throw new IOException("PIN commit failed");}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    /** Sets or replaces the PIN and panic PIN for this first-factor secret; resets the attempt count. */
    public void set(byte[] secret,String pin,String panic,byte[] master)throws Exception{write(PinBox.seal(secret,pin,panic,master));}
    public void delete(){file.delete();}
    /** The vault master key for a correct PIN. Throws {@link WrongPin} or, after wiping, {@link Wiped}. */
    public byte[] unlock(byte[] secret,String pin)throws Exception{
        if(!PinBox.valid(pin))throw new SecurityException("Enter your 4-digit PIN");
        PinBox.State s=state();
        if(PinBox.isPanic(s,pin)){wipe(context);throw new Wiped();}
        s.failures++;write(s);
        byte[] master;
        try{master=PinBox.open(s,secret,pin);}catch(Exception e){if(s.failures>=PinBox.MAX_ATTEMPTS){wipe(context);throw new Wiped();}throw new WrongPin(s.remaining());}
        s.failures=0;write(s);return master;
    }
    /**
     * Crypto-erase: deletes this app's Keystore keys first (every encrypted file becomes unreadable
     * at once), then its files, cache and preferences. Android does not let an app uninstall itself;
     * the caller then asks Android to clear the app's data as well.
     */
    public static void wipe(Context c){
        try{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);for(String alias:Collections.list(ks.aliases()))try{ks.deleteEntry(alias);}catch(Exception ignored){}}catch(Exception ignored){}
        File data=new File(c.getApplicationInfo().dataDir);
        for(File dir:new File[]{c.getFilesDir(),c.getCacheDir(),new File(data,"shared_prefs"),new File(data,"databases"),new File(data,"no_backup")})delete(dir);
    }
    private static void delete(File f){File[] children=f.listFiles();if(children!=null)for(File child:children)delete(child);f.delete();}
}
