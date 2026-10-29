package app.quietkey;

import java.util.Arrays;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.crypto.kems.*;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.crypto.signers.MLDSASigner;

/**
 * The second algorithm family next to X25519/Ed25519 (SYSTEM_DESIGN decision 11): ML-KEM-768
 * (FIPS 203) for key agreement and ML-DSA-65 (FIPS 204) for signatures. Keys are derived from
 * seeds drawn from {@link Entropy}, so camera and motion randomness reach these keys too.
 */
final class PostQuantum {
    private PostQuantum(){}
    static final int KEM_SEED=64,KEM_PUBLIC=1184,KEM_CIPHERTEXT=1088,DSA_SEED=32,DSA_PUBLIC=1952,DSA_SIGNATURE=3309;

    static byte[] kemSeed(Entropy e,String purpose){byte[] a=e.next32(purpose+"/kem-d"),b=e.next32(purpose+"/kem-z"),s=new byte[KEM_SEED];System.arraycopy(a,0,s,0,32);System.arraycopy(b,0,s,32,32);Arrays.fill(a,(byte)0);Arrays.fill(b,(byte)0);return s;}
    static byte[] kemPublic(byte[] seed){return kemPrivate(seed).getPublicKey();}
    /** Encapsulates to a public key with caller-supplied randomness; returns {secret, ciphertext}. */
    static byte[][] encapsulate(byte[] publicKey,byte[] randomness){
        if(publicKey==null||publicKey.length!=KEM_PUBLIC||randomness.length!=32)throw new SecurityException("Invalid ML-KEM key");
        SecretWithEncapsulation s;try{s=MLKEMGenerator.internalGenerateEncapsulated(new MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768,publicKey),randomness);}catch(RuntimeException e){throw new SecurityException("Invalid ML-KEM key");}
        try{return new byte[][]{s.getSecret(),s.getEncapsulation()};}finally{try{s.destroy();}catch(Exception ignored){}}
    }
    /** ML-KEM decapsulation; a wrong or altered ciphertext yields an unrelated secret (implicit rejection). */
    static byte[] decapsulate(byte[] seed,byte[] ciphertext){
        if(ciphertext==null||ciphertext.length!=KEM_CIPHERTEXT)throw new SecurityException("Invalid ML-KEM ciphertext");
        MLKEMPrivateKeyParameters k=kemPrivate(seed);try{return new MLKEMExtractor(k).extractSecret(ciphertext);}finally{k.destroy();}
    }
    /** Bouncy Castle keeps and later wipes the seed array it is given, so it always gets a copy. */
    private static MLKEMPrivateKeyParameters kemPrivate(byte[] seed){if(seed==null||seed.length!=KEM_SEED)throw new SecurityException("Invalid ML-KEM seed");return new MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768,seed.clone());}

    static byte[] dsaPublic(byte[] seed){return dsaPrivate(seed).getPublicKey();}
    static byte[] dsaSign(byte[] seed,byte[] message){
        MLDSAPrivateKeyParameters k=dsaPrivate(seed);
        try{MLDSASigner s=new MLDSASigner();s.init(true,k);s.update(message,0,message.length);return s.generateSignature();}catch(Exception e){throw new IllegalStateException(e);}finally{k.destroy();}
    }
    static boolean dsaVerify(byte[] publicKey,byte[] message,byte[] signature){
        if(publicKey==null||publicKey.length!=DSA_PUBLIC||signature==null||signature.length!=DSA_SIGNATURE)return false;
        try{MLDSASigner v=new MLDSASigner();v.init(false,new MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65,publicKey));v.update(message,0,message.length);return v.verifySignature(signature);}catch(RuntimeException e){return false;}
    }
    private static MLDSAPrivateKeyParameters dsaPrivate(byte[] seed){if(seed==null||seed.length!=DSA_SEED)throw new SecurityException("Invalid ML-DSA seed");return new MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65,seed.clone());}
}
