package app.quietkey;

import java.security.MessageDigest;
import java.util.Arrays;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/**
 * Member identity: a long-lived Ed25519 + ML-DSA-65 signing pair and an X25519 agreement key
 * (X3DH's IK). Exchanged only in person. The fingerprint covers all three public keys.
 * {@link #sign} produces both signatures and {@link Public#verify} requires both (decision 11),
 * so forging a member's signature needs Ed25519 and ML-DSA broken.
 */
public final class Identity {
    public static final int PUBLIC=32+32+PostQuantum.DSA_PUBLIC,SIGNATURE=64+PostQuantum.DSA_SIGNATURE,PRIVATE=32+32+PostQuantum.DSA_SEED;

    public static final class Public {
        public final byte[] sign,agree,pqSign;
        public Public(byte[] sign,byte[] agree,byte[] pqSign){
            if(sign==null||sign.length!=32||agree==null||agree.length!=32||pqSign==null||pqSign.length!=PostQuantum.DSA_PUBLIC)throw new SecurityException("Invalid identity key");
            this.sign=sign.clone();this.agree=agree.clone();this.pqSign=pqSign.clone();
        }
        public static Public decode(byte[] b){if(b==null||b.length!=PUBLIC)throw new SecurityException("Invalid identity key");return new Public(Arrays.copyOfRange(b,0,32),Arrays.copyOfRange(b,32,64),Arrays.copyOfRange(b,64,PUBLIC));}
        public byte[] encode(){byte[] b=new byte[PUBLIC];System.arraycopy(sign,0,b,0,32);System.arraycopy(agree,0,b,32,32);System.arraycopy(pqSign,0,b,64,pqSign.length);return b;}
        public String fingerprint(){try{return Protocol.b64(MessageDigest.getInstance("SHA-256").digest(encode()));}catch(Exception e){throw new IllegalStateException(e);}}
        /** Hybrid check: the Ed25519 and the ML-DSA signature must both be valid. */
        public boolean verify(byte[] message,byte[] signature){
            if(signature==null||signature.length!=SIGNATURE)return false;
            boolean classic=verifyClassic(message,Arrays.copyOf(signature,64));
            boolean pq=PostQuantum.dsaVerify(pqSign,message,Arrays.copyOfRange(signature,64,SIGNATURE));
            return classic&pq;
        }
        /** Ed25519 only: used for one-time and backup keys, which only travel inside hybrid-signed packets. */
        boolean verifyClassic(byte[] message,byte[] signature){
            if(signature==null||signature.length!=64)return false;
            Ed25519Signer v=new Ed25519Signer();v.init(false,new Ed25519PublicKeyParameters(sign,0));v.update(message,0,message.length);return v.verifySignature(signature);
        }
        @Override public boolean equals(Object o){return o instanceof Public&&Arrays.equals(sign,((Public)o).sign)&&Arrays.equals(agree,((Public)o).agree)&&Arrays.equals(pqSign,((Public)o).pqSign);}
        @Override public int hashCode(){return Arrays.hashCode(sign);}
    }

    private final byte[] signPrivate,agreePrivate,pqSeed;
    public final Public publicKey;

    private Identity(byte[] signPrivate,byte[] agreePrivate,byte[] pqSeed){
        this.signPrivate=signPrivate;this.agreePrivate=agreePrivate;this.pqSeed=pqSeed;
        publicKey=new Public(new Ed25519PrivateKeyParameters(signPrivate,0).generatePublicKey().getEncoded(),x25519Public(agreePrivate),PostQuantum.dsaPublic(pqSeed));
    }
    public static Identity generate(Entropy entropy){return new Identity(entropy.next32("identity-sign"),entropy.next32("identity-agree"),entropy.next32("identity-pq-sign"));}
    /** Private halves for the passphrase vault; callers must clear the result. */
    public byte[] encodePrivate(){byte[] b=new byte[PRIVATE];System.arraycopy(signPrivate,0,b,0,32);System.arraycopy(agreePrivate,0,b,32,32);System.arraycopy(pqSeed,0,b,64,32);return b;}
    public static Identity restore(byte[] b){if(b==null||b.length!=PRIVATE)throw new SecurityException("Invalid identity");return new Identity(Arrays.copyOfRange(b,0,32),Arrays.copyOfRange(b,32,64),Arrays.copyOfRange(b,64,96));}
    public String fingerprint(){return publicKey.fingerprint();}
    /** Ed25519 signature followed by an ML-DSA-65 signature over the same message. */
    public byte[] sign(byte[] message){
        byte[] classic=signClassic(message),pq=PostQuantum.dsaSign(pqSeed,message),out=new byte[SIGNATURE];
        System.arraycopy(classic,0,out,0,64);System.arraycopy(pq,0,out,64,pq.length);return out;
    }
    byte[] signClassic(byte[] message){Ed25519Signer s=new Ed25519Signer();s.init(true,new Ed25519PrivateKeyParameters(signPrivate,0));s.update(message,0,message.length);return s.generateSignature();}
    byte[] agree(byte[] theirPublic){return agree(agreePrivate,theirPublic);}
    public void destroy(){Arrays.fill(signPrivate,(byte)0);Arrays.fill(agreePrivate,(byte)0);Arrays.fill(pqSeed,(byte)0);}

    static byte[] x25519Public(byte[] privateKey){return new X25519PrivateKeyParameters(privateKey,0).generatePublicKey().getEncoded();}
    /** X25519 with the all-zero (low-order point) result rejected. */
    static byte[] agree(byte[] privateKey,byte[] theirPublic){
        if(theirPublic==null||theirPublic.length!=32)throw new SecurityException("Invalid public key");
        X25519Agreement a=new X25519Agreement();a.init(new X25519PrivateKeyParameters(privateKey,0));byte[] out=new byte[32];
        try{a.calculateAgreement(new X25519PublicKeyParameters(theirPublic,0),out,0);}catch(IllegalStateException e){throw new SecurityException("Invalid public key");}
        int any=0;for(byte b:out)any|=b;if(any==0)throw new SecurityException("Invalid public key");return out;
    }
}
