package app.quietkey;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Cipher;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import static app.quietkey.Packets.*;

/** Fixed, bounded password envelope. Parameters cannot be inflated by an input file. */
public final class PasswordCipher {
    public static final int MEMORY_KIB=65536,ITERATIONS=3,LANES=4;
    private static final byte[] AAD="Enigma/password-envelope/v1".getBytes(StandardCharsets.UTF_8);
    public static byte[] derive(char[] password,byte[] salt){
        if(password.length>128||salt.length!=16)throw new IllegalArgumentException("Invalid password parameters");
        Argon2BytesGenerator gen=new Argon2BytesGenerator();Argon2Parameters params=new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt).withMemoryAsKB(MEMORY_KIB).withIterations(ITERATIONS).withParallelism(LANES).build();
        byte[] key=new byte[32];try{gen.init(params);gen.generateBytes(password,key);return key;}finally{params.clear();}
    }
    public static byte[] seal(char[] password,byte[] master)throws Exception{
        if(password.length<12||password.length>128||master.length!=32)throw new IllegalArgumentException("Use a passphrase of 12–128 characters");byte[] salt=Protocol.random(16),nonce=Protocol.random(12),key=derive(password,salt);
        try{return pack(o->{o.writeInt(1);blob(o,salt);blob(o,nonce);blob(o,Protocol.aes(Cipher.ENCRYPT_MODE,key,nonce,AAD,master));});}finally{Arrays.fill(key,(byte)0);}
    }
    public static byte[] open(char[] password,byte[] envelope)throws Exception{
        DataInputStream in=input(envelope);if(in.readInt()!=1)throw new SecurityException("Unsupported password envelope");byte[] salt=blob(in,16),nonce=blob(in,12),cipher=blob(in,48);end(in);
        if(salt.length!=16||nonce.length!=12||cipher.length!=48)throw new SecurityException("Invalid password envelope");byte[] key=derive(password,salt);
        try{return Protocol.aes(Cipher.DECRYPT_MODE,key,nonce,AAD,cipher);}finally{Arrays.fill(key,(byte)0);}
    }
}
