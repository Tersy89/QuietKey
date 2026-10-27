package app.quietkey;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Bounded, signed, chunk-authenticated file packages. No Android dependencies. */
public final class FileProtocol {
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final int MAGIC = 0x454e4631, CHUNK = 65536;
    private FileProtocol() {}

    public static final class Verified implements AutoCloseable {
        public final String name, mime, sender, recipient, messageId;
        public final long size,createdAt,expiresAt;
        private final long groupExpiry;
        public final boolean previouslyOpened;
        private final File file;
        private final byte[] header, key, nonce;
        private boolean closed;
        private Verified(File file, Header h, byte[] key, String name, String mime, long size, boolean previouslyOpened,long groupExpiry) {
            this.file=file; this.header=h.bytes; this.key=key; this.nonce=h.nonce;
            this.name=name; this.mime=mime; this.size=size; sender=h.sender; recipient=h.recipient; messageId=Protocol.b64(h.id);
            this.previouslyOpened=previouslyOpened;
            createdAt=h.createdAt;expiresAt=h.expiresAt;this.groupExpiry=groupExpiry;
        }
        @Override public void close() { Arrays.fill(key,(byte)0); closed=true; }
    }
    private static final class Header {
        final byte[] bytes, id, nonce, forRecipient, forSender;
        final String group, sender, recipient;
        final long createdAt,expiresAt;
        final boolean layered;final byte[] roster;
        Header(byte[] bytes) throws Exception {
            this.bytes=bytes;
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
            String version=in.readUTF();layered=version.equals("file-v3");boolean v2=layered || version.equals("file-v2");
            if (!v2 && !version.equals("file-v1")) throw new SecurityException("Unsupported encrypted file");
            group=in.readUTF(); sender=in.readUTF(); recipient=in.readUTF();createdAt=v2?in.readLong():0;expiresAt=v2?in.readLong():0; id=blob(in,16); nonce=blob(in,8);
            forRecipient=blob(in,256);forSender=blob(in,256);roster=layered?blob(in,17000):new byte[0];
            if (id.length!=16 || nonce.length!=8 || forRecipient.length!=256 || forSender.length!=256 || in.available()!=0) throw new SecurityException("Invalid file header");
        }
    }
    private static void live(Protocol.Group group) {
        if (System.currentTimeMillis()>=group.expires) throw new SecurityException("Group expired. Create a new group");
    }
    private static byte[] crypt(int mode, byte[] key, byte[] base, byte[] header, int index, int kind, byte[] clear) throws Exception {
        ByteArrayOutputStream nonceBytes=new ByteArrayOutputStream(); DataOutputStream n=new DataOutputStream(nonceBytes);
        n.write(base); n.writeInt(index);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonceBytes.toByteArray()));
        cipher.updateAAD(header);
        cipher.updateAAD(new byte[]{(byte)(index>>>24),(byte)(index>>>16),(byte)(index>>>8),(byte)index,(byte)kind});
        return cipher.doFinal(clear);
    }
    public static String safeName(String name) {
        if (name==null) return "restored-file";
        name=name.replace('\\','/'); name=name.substring(name.lastIndexOf('/')+1);
        name=name.replaceAll("[\\p{Cntrl}:]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return "restored-file";
        return name.substring(0,Math.min(120,name.length()));
    }
    private static String safeMime(String mime) {
        return mime!=null && mime.length()<=120 && mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+") ? mime : "application/octet-stream";
    }

    /** Only ciphertext is written. Output may be partial on failure and must be discarded. */
    public static void encrypt(Protocol.Group group, String recipientId, InputStream source, OutputStream target, String name, String mime,
                               PublicKey sender, PrivateKey privateKey) throws Exception {
        encrypt(group,recipientId,source,target,name,mime,sender,privateKey,0);
    }
    public static void encrypt(Protocol.Group group,String recipientId,InputStream source,OutputStream target,String name,String mime,PublicKey sender,PrivateKey privateKey,long ttl)throws Exception {
        live(group); String fingerprint=Protocol.fingerprint(sender);
        DirectProtocol.recipient(group,fingerprint);
        if(fingerprint.equals(recipientId))throw new SecurityException("Choose another verified group member as recipient");
        PublicKey recipient=DirectProtocol.recipient(group,recipientId);
        long created=System.currentTimeMillis(),expires=Expiry.deadline(created,ttl,group.expires);
        byte[] id=Protocol.random(16), nonce=Protocol.random(8), secret=Protocol.random(32);
        ByteArrayOutputStream headerBytes=new ByteArrayOutputStream(); DataOutputStream h=new DataOutputStream(headerBytes);
        h.writeUTF("file-v3"); h.writeUTF(group.id); h.writeUTF(fingerprint); h.writeUTF(recipientId);h.writeLong(created);h.writeLong(expires); blob(h,id); blob(h,nonce);
        blob(h,Protocol.rsa(Cipher.ENCRYPT_MODE,recipient,secret));blob(h,Protocol.rsa(Cipher.ENCRYPT_MODE,sender,secret));blob(h,PairingProtocol.carrier(group));
        byte[] header=headerBytes.toByteArray(), buffer=new byte[CHUNK],key=GroupKey.derive(group,secret,"file",header);Arrays.fill(secret,(byte)0);
        Signature signature=Signature.getInstance("SHA256withRSA"); signature.initSign(privateKey);
        DataOutputStream out=new DataOutputStream(new FilterOutputStream(target) {
            @Override public void write(int value) throws IOException { out.write(value); try { signature.update((byte)value); } catch(SignatureException e) { throw new IOException(e); } }
            @Override public void write(byte[] bytes,int offset,int count) throws IOException { out.write(bytes,offset,count); try { signature.update(bytes,offset,count); } catch(SignatureException e) { throw new IOException(e); } }
        });
        try {
            out.writeInt(MAGIC); blob(out,header);
            ByteArrayOutputStream metadata=new ByteArrayOutputStream(); DataOutputStream m=new DataOutputStream(metadata);
            m.writeUTF(safeName(name)); m.writeUTF(safeMime(mime));
            writeChunk(out,key,nonce,header,0,0,metadata.toByteArray());
            int index=1; long total=0;
            while (true) {
                int count=0;
                while (count<CHUNK) { int read=source.read(buffer,count,CHUNK-count); if (read<0) break; if (read==0) throw new IOException("File provider stopped responding"); count+=read; }
                if (count==0) break;
                total+=count; if (total>MAX_FILE_BYTES) throw new IOException("Files must be 50 MB or smaller");
                byte[] clear=Arrays.copyOf(buffer,count);
                try { writeChunk(out,key,nonce,header,index++,1,clear); } finally { Arrays.fill(clear,(byte)0); }
            }
            writeChunk(out,key,nonce,header,index,2,new byte[0]); out.flush();
            // The fixed-length RSA signature follows the complete signed body.
            byte[] signed=signature.sign(); if(signed.length!=256) throw new SecurityException("Unsupported signing key");
            target.write(signed); target.flush();
        } finally { Arrays.fill(key,(byte)0); Arrays.fill(buffer,(byte)0); }
    }
    private static void writeChunk(DataOutputStream out,byte[] key,byte[] nonce,byte[] header,int index,int kind,byte[] clear) throws Exception {
        out.writeByte(kind); blob(out,crypt(Cipher.ENCRYPT_MODE,key,nonce,header,index,kind,clear));
    }
    private static Header readHeader(DataInputStream in) throws Exception {
        if (in.readInt()!=MAGIC) throw new SecurityException("Choose an Enigma .enigma encrypted file");
        return new Header(blob(in,20000));
    }

    /** Unauthenticated routing hint only: inspect() still verifies everything. */
    public static String groupId(File file) throws Exception {
        try (DataInputStream raw=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) { return readHeader(raw).group; }
    }

    /** Verifies the entire signature, every GCM tag, limits and final marker before any export. */
    public static Verified inspect(Protocol.Group group, File file, PublicKey ownKey, PrivateKey privateKey) throws Exception {
        live(group);
        if (file.length()>MAX_FILE_BYTES+100000 || file.length()<300) throw new SecurityException("Invalid encrypted file size");
        byte[] key=null; boolean success=false;
        try (DataInputStream raw=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            Header header=readHeader(raw);
            if (!header.group.equals(group.id)) throw new SecurityException("File belongs to a different group");
            Expiry.check(header.createdAt,header.expiresAt,group.expires);
            Protocol.Group candidate=PairingProtocol.carrierView(group,header.roster);
            PublicKey trusted=DirectProtocol.recipient(candidate,header.sender);
            DirectProtocol.recipient(candidate,header.recipient);
            String own=Protocol.fingerprint(ownKey);
            if(!own.equals(header.recipient) && !own.equals(header.sender))throw new SecurityException("This file is private to another member");
            boolean previouslyOpened=group.seen.contains(Protocol.b64(header.id));
            if (!previouslyOpened && group.seen.size()>=100000) throw new SecurityException("Replay log full. Create a new group");
            Signature signature=Signature.getInstance("SHA256withRSA"); signature.initVerify(trusted);
            ByteArrayOutputStream prefix=new ByteArrayOutputStream(); DataOutputStream p=new DataOutputStream(prefix); p.writeInt(MAGIC); blob(p,header.bytes);
            signature.update(prefix.toByteArray());
            DataInputStream in=new DataInputStream(new FilterInputStream(raw) {
                @Override public int read() throws IOException { int value=in.read(); if(value>=0)try{signature.update((byte)value);}catch(SignatureException e){throw new IOException(e);} return value; }
                @Override public int read(byte[] bytes,int offset,int count) throws IOException { int read=in.read(bytes,offset,count); if(read>0)try{signature.update(bytes,offset,read);}catch(SignatureException e){throw new IOException(e);} return read; }
            });
            key=GroupKey.unwrap(group,privateKey,own.equals(header.recipient)?header.forRecipient:header.forSender,"file",header.bytes,header.layered);
            if(key.length!=32)throw new SecurityException("Invalid file key");
            String name=null,mime=null; long total=0; int index=0;
            while (true) {
                int kind=in.readUnsignedByte(); byte[] encrypted=blob(in,CHUNK+16);
                if (encrypted.length<16 || (index==0 ? kind!=0 : kind!=1 && kind!=2)) throw new SecurityException("Invalid file chunk order");
                byte[] clear=crypt(Cipher.DECRYPT_MODE,key,header.nonce,header.bytes,index++,kind,encrypted);
                try {
                    if (kind==0) {
                        if (clear.length>1000) throw new SecurityException("Invalid file metadata");
                        DataInputStream metadata=new DataInputStream(new ByteArrayInputStream(clear));
                        name=metadata.readUTF(); mime=metadata.readUTF();
                        if (metadata.available()!=0 || !name.equals(safeName(name)) || !mime.equals(safeMime(mime))) throw new SecurityException("Invalid file metadata");
                    } else if (kind==1) {
                        if (clear.length==0) throw new SecurityException("Empty data chunk");
                        total+=clear.length; if (total>MAX_FILE_BYTES) throw new SecurityException("File exceeds 50 MB limit");
                    } else { if (clear.length!=0) throw new SecurityException("Invalid file end marker"); break; }
                } finally { Arrays.fill(clear,(byte)0); }
                if (index>MAX_FILE_BYTES/CHUNK+2) throw new SecurityException("Too many file chunks");
            }
            byte[] sig=new byte[256]; raw.readFully(sig);
            if (raw.read()!=-1 || !signature.verify(sig)) throw new SecurityException("File signature invalid or file was changed");
            Expiry.check(header.createdAt,header.expiresAt,group.expires);
            PairingProtocol.applyRoster(group,candidate);Verified verified=new Verified(file,header,key,name,mime,total,previouslyOpened,group.expires); success=true; return verified;
        } finally { if (!success && key!=null) Arrays.fill(key,(byte)0); }
    }

    /** Call only after saving Verified.messageId durably; never writes plaintext to app cache. */
    public static void restore(Verified verified, OutputStream target) throws Exception {
        if (verified.closed) throw new IllegalStateException("File verification expired. Choose the file again");
        Expiry.check(verified.createdAt,verified.expiresAt,verified.groupExpiry);
        try (DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(verified.file)))) {
            Header header=readHeader(in);
            if (!Arrays.equals(header.bytes,verified.header)) throw new SecurityException("File changed after verification");
            int index=0; long total=0;
            while (true) {
                int kind=in.readUnsignedByte(); byte[] encrypted=blob(in,CHUNK+16);
                if (index==0 ? kind!=0 : kind!=1 && kind!=2) throw new SecurityException("File changed after verification");
                byte[] clear=crypt(Cipher.DECRYPT_MODE,verified.key,verified.nonce,verified.header,index++,kind,encrypted);
                try {
                    if (kind==1) { total+=clear.length; if(total>verified.size)throw new SecurityException("File changed after verification"); target.write(clear); }
                    else if (kind==2) { if(clear.length!=0 || total!=verified.size)throw new SecurityException("Incomplete file"); break; }
                } finally { Arrays.fill(clear,(byte)0); }
            }
            target.flush();
        }
    }
    private static void blob(DataOutputStream out,byte[] value) throws IOException { out.writeInt(value.length); out.write(value); }
    private static byte[] blob(DataInputStream in,int max) throws IOException {
        int length=in.readInt(); if(length<0 || length>max)throw new IOException("Invalid encrypted file structure");
        byte[] value=new byte[length]; in.readFully(value); return value;
    }
}
