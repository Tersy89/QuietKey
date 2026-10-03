package app.quietkey;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;

/** Recipient-private messages; the group seed cannot decrypt these packets. */
public final class DirectProtocol {
    private DirectProtocol() {}
    public static final class Opened {
        public final String text,sender,recipient,messageId;
        public final long createdAt,expiresAt;
        Opened(String text,String sender,String recipient,String id,long created,long expires) { this.text=text;this.sender=sender;this.recipient=recipient;messageId=id;createdAt=created;expiresAt=expires; }
    }
    static PublicKey recipient(Protocol.Group group,String id) throws Exception {
        String encoded=group.members.get(id);
        if(encoded==null)throw new SecurityException("Choose a verified member of this group");
        PublicKey key=Protocol.publicKey(encoded);
        if(!id.equals(Protocol.fingerprint(key)))throw new SecurityException("Invalid member identity");
        return key;
    }
    static byte[] freshContentKey(Protocol.Group group,String purpose)throws Exception {
        byte[] random=Protocol.random(32);
        try{return Protocol.hkdf(random,group.seed,("Enigma/private-"+purpose+"-key/v1").getBytes(StandardCharsets.UTF_8));}
        finally{Arrays.fill(random,(byte)0);}
    }
    public static String encrypt(Protocol.Group group,String recipientId,String text,PublicKey sender,PrivateKey privateKey) throws Exception {
        return encrypt(group,recipientId,text,sender,privateKey,0);
    }
    public static String encrypt(Protocol.Group group,String recipientId,String text,PublicKey sender,PrivateKey privateKey,long ttl) throws Exception {
        live(group); String senderId=Protocol.fingerprint(sender); recipient(group,senderId);
        if(senderId.equals(recipientId))throw new SecurityException("Choose another verified group member as recipient");
        PublicKey recipient=recipient(group,recipientId);
        long created=System.currentTimeMillis(),expires=Expiry.deadline(created,ttl,group.expires);
        byte[] clear=text.getBytes(StandardCharsets.UTF_8);
        if(clear.length==0 || clear.length>Protocol.MAX_TEXT_BYTES)throw new IllegalArgumentException("Use 1–16000 UTF-8 bytes");
        byte[] key=freshContentKey(group,"message"),id=Protocol.random(16),nonce=Protocol.random(12);
        try {
            byte[] header=pack(out->{out.writeUTF("direct-v2");out.writeUTF(group.id);out.writeUTF(senderId);out.writeUTF(recipientId);out.writeLong(created);out.writeLong(expires);blob(out,id);blob(out,nonce);
                blob(out,Protocol.rsa(Cipher.ENCRYPT_MODE,recipient,key));blob(out,Protocol.rsa(Cipher.ENCRYPT_MODE,sender,key));});
            byte[] cipher=Protocol.aes(Cipher.ENCRYPT_MODE,key,nonce,header,clear);
            byte[] body=pack(out->{blob(out,header);blob(out,cipher);});
            Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(privateKey);signature.update(body);
            byte[] sig=signature.sign();return "QKD2:"+Protocol.b64(pack(out->{blob(out,body);blob(out,sig);}));
        } finally {Arrays.fill(key,(byte)0);Arrays.fill(clear,(byte)0);}
    }
    public static Opened decrypt(Protocol.Group group,String wire,PublicKey ownKey,PrivateKey privateKey) throws Exception {
        live(group);wire=wire.trim();
        boolean v2=wire.startsWith("QKD2:");
        if((!v2 && !wire.startsWith("QKD1:")) || wire.length()>30000)throw new SecurityException("Invalid private message");
        DataInputStream outer=input(Protocol.un64(wire.substring(5)));byte[] body=blob(outer,18000),sig=blob(outer,256);end(outer);
        DataInputStream in=input(body);byte[] header=blob(in,900),cipher=blob(in,Protocol.MAX_TEXT_BYTES+16);end(in);
        DataInputStream h=input(header);
        if(!h.readUTF().equals(v2?"direct-v2":"direct-v1") || !h.readUTF().equals(group.id))throw new SecurityException("Message belongs to a different group");
        String sender=h.readUTF(),recipient=h.readUTF();long created=v2?h.readLong():0,expires=v2?h.readLong():0;
        byte[] id=blob(h,16),nonce=blob(h,12),forRecipient=blob(h,256),forSender=blob(h,256);end(h);
        if(id.length!=16 || nonce.length!=12 || forRecipient.length!=256 || forSender.length!=256)throw new SecurityException("Invalid private message fields");
        PublicKey trusted=recipient(group,sender);recipient(group,recipient);
        Signature signature=Signature.getInstance("SHA256withRSA");signature.initVerify(trusted);signature.update(body);
        if(!signature.verify(sig))throw new SecurityException("Message signature invalid");
        Expiry.check(created,expires,group.expires);
        String own=Protocol.fingerprint(ownKey);
        if(!own.equals(recipient) && !own.equals(sender))throw new SecurityException("This message is private to another member");
        String messageId=Protocol.b64(id);
        if(group.seen.contains(messageId))throw new SecurityException("This message has already been decrypted");
        if(group.seen.size()>=100000)throw new SecurityException("Replay log full. Create a new group");
        byte[] key=Protocol.rsa(Cipher.DECRYPT_MODE,privateKey,own.equals(recipient)?forRecipient:forSender);
        try {
            if(key.length!=32)throw new SecurityException("Invalid message key");
            byte[] clear=Protocol.aes(Cipher.DECRYPT_MODE,key,nonce,header,cipher);
            try {return new Opened(new String(clear,StandardCharsets.UTF_8),sender,recipient,messageId,created,expires);}
            finally {Arrays.fill(clear,(byte)0);}
        } finally {Arrays.fill(key,(byte)0);}
    }
    private static void live(Protocol.Group group) {if(System.currentTimeMillis()>=group.expires)throw new SecurityException("Group expired. Create a new group");}
    private interface Writer {void write(DataOutputStream out)throws Exception;}
    private static byte[] pack(Writer writer)throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();writer.write(new DataOutputStream(bytes));return bytes.toByteArray();}
    private static void blob(DataOutputStream out,byte[] bytes)throws IOException {out.writeInt(bytes.length);out.write(bytes);}
    private static byte[] blob(DataInputStream in,int max)throws IOException {int length=in.readInt();if(length<0 || length>max || length>in.available())throw new IOException("Invalid message size");byte[] bytes=new byte[length];in.readFully(bytes);return bytes;}
    private static DataInputStream input(byte[] bytes){return new DataInputStream(new ByteArrayInputStream(bytes));}
    private static void end(DataInputStream in)throws Exception {if(in.available()!=0)throw new SecurityException("Unexpected trailing data");}
}
