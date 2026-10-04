package app.quietkey;
import android.content.*;
import android.security.keystore.*;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Device Keystore encryption outside password encryption: both are needed to unlock. */
public final class PasswordLock {
    private final AtomicFile file;private final Context context;
    private static final byte[] AAD="Enigma/device-password-box/v1".getBytes(StandardCharsets.UTF_8);
    private static final String DEVICE="quietkey.vault.v1",BIO="enigma.biometric.v1";
    public PasswordLock(Context c){context=c;file=new AtomicFile(new File(c.getFilesDir(),"password.qkl"));}
    public boolean configured(){return file.getBaseFile().exists()||new File(file.getBaseFile()+".bak").exists();}
    private KeyStore store()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);return ks;}
    private SecretKey deviceKey()throws Exception{SecretKey k=(SecretKey)store().getKey(DEVICE,null);if(k==null)throw new SecurityException("Device key unavailable. No password recovery is available");return k;}
    private void write(byte[] bytes)throws Exception{FileOutputStream out=null;try{out=file.startWrite();out.write(bytes);out.getFD().sync();file.finishWrite(out);if(!Arrays.equals(bytes,file.readFully()))throw new IOException("Password commit failed");}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}}
    public byte[] create(char[] password)throws Exception{
        if(configured())throw new SecurityException("A password already protects this installation");
        // Initialize retained device keys and validate legacy data before committing a lock.
        Vault previous=new Vault(context);previous.close();byte[] master=Protocol.random(32);boolean success=false;
        try{replace(password,master);success=true;return master;}finally{if(!success)Arrays.fill(master,(byte)0);}
    }
    public void replace(char[] password,byte[] master)throws Exception{
        byte[] inner=PasswordCipher.seal(password,master);
        try{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,deviceKey());c.updateAAD(AAD);byte[] cipher=c.doFinal(inner);write(Packets.pack(o->{o.writeInt(1);Packets.blob(o,c.getIV());Packets.blob(o,cipher);}));disableBiometric();}finally{Arrays.fill(inner,(byte)0);}
    }
    public byte[] unlock(char[] password)throws Exception{
        if(file.getBaseFile().length()>1000)throw new SecurityException("Invalid password file");DataInputStream in=Packets.input(file.readFully());if(in.readInt()!=1)throw new SecurityException("Unsupported password file");byte[] nonce=Packets.blob(in,12),cipher=Packets.blob(in,500);Packets.end(in);if(nonce.length!=12)throw new SecurityException("Invalid password nonce");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,deviceKey(),new GCMParameterSpec(128,nonce));c.updateAAD(AAD);byte[] inner=c.doFinal(cipher);
        try{return PasswordCipher.open(password,inner);}finally{Arrays.fill(inner,(byte)0);}
    }
    public boolean biometricConfigured(){return new File(context.getFilesDir(),"biometric.qkb").exists();}
    public Cipher enableBiometricCipher()throws Exception{
        KeyStore ks=store();if(ks.containsAlias(BIO))ks.deleteEntry(BIO);
        KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(BIO,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true).build());generator.generateKey();
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,(SecretKey)store().getKey(BIO,null));c.updateAAD("Enigma/biometric/v1".getBytes(StandardCharsets.UTF_8));return c;
    }
    public void finishBiometricEnable(Cipher c,byte[] master)throws Exception{
        byte[] cipher=c.doFinal(master);AtomicFile bio=new AtomicFile(new File(context.getFilesDir(),"biometric.qkb"));FileOutputStream out=null;try{out=bio.startWrite();out.write(Packets.pack(o->{Packets.blob(o,c.getIV());Packets.blob(o,cipher);}));out.getFD().sync();bio.finishWrite(out);}catch(Exception e){if(out!=null)bio.failWrite(out);throw e;}
    }
    public Cipher biometricUnlockCipher()throws Exception{
        DataInputStream in=Packets.input(new AtomicFile(new File(context.getFilesDir(),"biometric.qkb")).readFully());byte[] nonce=Packets.blob(in,12);Packets.blob(in,48);Packets.end(in);if(nonce.length!=12)throw new SecurityException("Invalid biometric nonce");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,(SecretKey)store().getKey(BIO,null),new GCMParameterSpec(128,nonce));c.updateAAD("Enigma/biometric/v1".getBytes(StandardCharsets.UTF_8));return c;
    }
    public byte[] finishBiometricUnlock(Cipher c)throws Exception{DataInputStream in=Packets.input(new AtomicFile(new File(context.getFilesDir(),"biometric.qkb")).readFully());Packets.blob(in,12);byte[] cipher=Packets.blob(in,48);Packets.end(in);return c.doFinal(cipher);}
    public void disableBiometric()throws Exception{new AtomicFile(new File(context.getFilesDir(),"biometric.qkb")).delete();KeyStore ks=store();if(ks.containsAlias(BIO))ks.deleteEntry(BIO);}
}
