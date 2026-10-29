package app.quietkey;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import static app.quietkey.Packets.*;

/**
 * Second unlock factor (decision 12). The vault master key is encrypted under a key derived from
 * both the first-factor secret (released by the passphrase or by biometrics through the Keystore)
 * and the 4-digit PIN, so neither factor alone opens the vault and a copied file cannot be
 * PIN-guessed without the first-factor secret. Pure Java; {@link PinLock} stores the state.
 */
public final class PinBox {
    private PinBox(){}
    public static final int MAX_ATTEMPTS=5;
    public static final String DEFAULT_PANIC="9999";
    private static final byte[] AAD="Enigma/pin-box/v1".getBytes(StandardCharsets.UTF_8);

    /** Stored state: wrong-PIN count, panic PIN and the PIN-encrypted master key. */
    public static final class State {
        public int failures;public final String panic;final byte[] check,salt,nonce,cipher;
        State(int failures,String panic,byte[] check,byte[] salt,byte[] nonce,byte[] cipher){this.failures=failures;this.panic=panic;this.check=check;this.salt=salt;this.nonce=nonce;this.cipher=cipher;}
        public int remaining(){return Math.max(0,MAX_ATTEMPTS-failures);}
        /** Whether this state belongs to the given first-factor secret (an interrupted PIN setup leaves a stale state). */
        public boolean belongsTo(byte[] secret){return MessageDigest.isEqual(check,check(secret));}
        public byte[] encode(){try{return pack(o->{o.writeInt(1);o.writeInt(failures);o.writeUTF(panic);blob(o,check);blob(o,salt);blob(o,nonce);blob(o,cipher);});}catch(Exception e){throw new IllegalStateException(e);}}
        public static State decode(byte[] b)throws IOException{
            DataInputStream in=input(b);if(in.readInt()!=1)throw new IOException("Unsupported PIN state");int failures=in.readInt();String panic=in.readUTF();
            byte[] check=blob(in,32),salt=blob(in,16),nonce=blob(in,12),cipher=blob(in,48);end(in);
            if(failures<0||failures>MAX_ATTEMPTS||!valid(panic)||check.length!=32||salt.length!=16||nonce.length!=12||cipher.length!=48)throw new IOException("Invalid PIN state");
            return new State(failures,panic,check,salt,nonce,cipher);
        }
    }
    public static boolean valid(String pin){return pin!=null&&pin.matches("[0-9]{4}");}
    /** New state for a PIN, a different panic PIN and the master key. */
    public static State seal(byte[] secret,String pin,String panic,byte[] master)throws Exception{
        if(!valid(pin)||!valid(panic))throw new IllegalArgumentException("PINs are 4 digits");
        if(pin.equals(panic))throw new IllegalArgumentException("The panic PIN must differ from your PIN");
        if(secret.length!=32||master.length!=32)throw new IllegalArgumentException("Invalid key");
        byte[] salt=Protocol.random(16),nonce=Protocol.random(12),key=key(secret,pin,salt);
        try{return new State(0,panic,check(secret),salt,nonce,Protocol.aes(Cipher.ENCRYPT_MODE,key,nonce,AAD,master));}finally{Arrays.fill(key,(byte)0);}
    }
    /** The master key, or an exception when the PIN is wrong. Does not count attempts; {@link PinLock} does. */
    public static byte[] open(State s,byte[] secret,String pin)throws Exception{
        if(!valid(pin))throw new SecurityException("Enter your 4-digit PIN");
        byte[] key=key(secret,pin,s.salt);try{return Protocol.aes(Cipher.DECRYPT_MODE,key,s.nonce,AAD,s.cipher);}finally{Arrays.fill(key,(byte)0);}
    }
    public static boolean isPanic(State s,String pin){return valid(pin)&&MessageDigest.isEqual(pin.getBytes(StandardCharsets.US_ASCII),s.panic.getBytes(StandardCharsets.US_ASCII));}
    private static byte[] key(byte[] secret,String pin,byte[] salt)throws Exception{
        char[] chars=pin.toCharArray();byte[] stretched=PasswordCipher.derive(chars,salt);
        try{return Protocol.hkdf(secret,stretched,"Enigma/pin-key/v1".getBytes(StandardCharsets.UTF_8));}finally{Arrays.fill(chars,'\0');Arrays.fill(stretched,(byte)0);}
    }
    private static byte[] check(byte[] secret){try{return Protocol.hkdf(secret,new byte[32],"Enigma/pin-secret-check/v1".getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
}
