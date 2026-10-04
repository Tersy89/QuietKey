package app.quietkey;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;

/** RSA carries a random secret; the final content key also requires the group seed. */
final class GroupKey {
    static byte[] derive(Protocol.Group g,byte[] secret,String purpose,byte[] header)throws Exception{
        if(secret.length!=32)throw new SecurityException("Invalid content secret");
        byte[] context=Packets.pack(out->{out.writeUTF("Enigma/group-and-endpoint/v1");out.writeUTF(purpose);out.writeUTF(g.id);Packets.blob(out,MessageDigest.getInstance("SHA-256").digest(header));});
        return Protocol.hkdf(secret,g.seed,context);
    }
    static byte[] unwrap(Protocol.Group g,PrivateKey key,byte[] wrapped,String purpose,byte[] header,boolean layered)throws Exception{
        byte[] secret=Protocol.rsa(Cipher.DECRYPT_MODE,key,wrapped);
        if(!layered)return secret;
        try{return derive(g,secret,purpose,header);}finally{Arrays.fill(secret,(byte)0);}
    }
}
