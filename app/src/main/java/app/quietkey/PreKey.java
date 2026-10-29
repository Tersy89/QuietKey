package app.quietkey;

import java.io.*;
import java.util.Arrays;

/**
 * A signed public key pair handed to contacts: an X25519 key and an ML-KEM-768 key, as a
 * medium-term backup key (X3DH signed prekey) or a single-use one-time key. Signed with the owner's
 * Ed25519 key; it only ever travels inside packets that carry the owner's hybrid signature
 * (message, joining request, invitation, receipt) or the owner's signed roster.
 */
public final class PreKey {
    public static final int BACKUP=1,ONE_TIME=2;
    public final int kind;public final byte[] id,publicKey,kemPublic,signature;public final long created;

    PreKey(int kind,byte[] id,byte[] publicKey,byte[] kemPublic,long created,byte[] signature){
        if((kind!=BACKUP&&kind!=ONE_TIME)||id.length!=8||publicKey.length!=32||kemPublic.length!=PostQuantum.KEM_PUBLIC||signature.length!=64)throw new SecurityException("Invalid key");
        this.kind=kind;this.id=id.clone();this.publicKey=publicKey.clone();this.kemPublic=kemPublic.clone();this.created=created;this.signature=signature.clone();
    }
    static PreKey sign(Identity owner,int kind,byte[] id,byte[] publicKey,byte[] kemPublic,long created){
        return new PreKey(kind,id,publicKey,kemPublic,created,owner.signClassic(signed(owner.fingerprint(),kind,id,publicKey,kemPublic,created)));
    }
    public boolean verify(Identity.Public owner,int expectedKind){
        return kind==expectedKind&&owner.verifyClassic(signed(owner.fingerprint(),kind,id,publicKey,kemPublic,created),signature);
    }
    public String idText(){return Protocol.b64(id);}
    void write(DataOutputStream o)throws IOException{o.writeByte(kind);o.write(id);o.write(publicKey);o.write(kemPublic);o.writeLong(created);o.write(signature);}
    static PreKey read(DataInputStream in)throws IOException{
        int kind=in.readUnsignedByte();byte[] id=new byte[8],pub=new byte[32],kem=new byte[PostQuantum.KEM_PUBLIC],sig=new byte[64];in.readFully(id);in.readFully(pub);in.readFully(kem);long created=in.readLong();in.readFully(sig);
        return new PreKey(kind,id,pub,kem,created,sig);
    }
    private static byte[] signed(String owner,int kind,byte[] id,byte[] publicKey,byte[] kemPublic,long created){
        try{return Packets.pack(o->{o.writeUTF("Enigma/prekey/v2");o.writeUTF(owner);o.writeByte(kind);o.write(id);o.write(publicKey);o.write(kemPublic);o.writeLong(created);});}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    @Override public boolean equals(Object o){return o instanceof PreKey&&Arrays.equals(id,((PreKey)o).id);}
    @Override public int hashCode(){return Arrays.hashCode(id);}
}
