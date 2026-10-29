package app.quietkey;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Cipher;

/**
 * 0.7.0 message package ("ENG5"), see SYSTEM_DESIGN "0.7.0 message security".
 * The content is encrypted once with a random content key. For each recipient that key is wrapped
 * under a hybrid secret: X3DH (sender identity + fresh ephemeral key, against the recipient's
 * identity, signed backup key and, when available, one single-use key) plus an ML-KEM-768
 * encapsulation to the same one-time key (or the backup key), all in one HKDF with the group
 * secret as salt. Reading a message therefore needs X25519 and ML-KEM broken and the group secret.
 * Inside the encryption the sender's hybrid Ed25519 + ML-DSA signature covers the outer header,
 * group, roster revision, sender, every recipient and their key IDs, and the content hash.
 *
 * Group members are read from {@link Protocol.Group#members}: fingerprint → base64 of
 * {@link Identity.Public#encode()}.
 */
public final class SealedProtocol {
    private SealedProtocol(){}
    static final String MAGIC="ENG5";
    public static final int MAX_RECIPIENTS=50,MAX_REFILL=KeyPools.REFILL;
    public static final long MAX_CONTENT=50L*1024*1024;
    static final int MAX_OUTER=256*1024,MAX_INNER=2*1024*1024,MAX_ENTRY=4096;
    private static final byte[] NO_KEY=new byte[8];

    /** One addressee as seen by the sender: verified keys from {@link KeyPools}. */
    public static final class Recipient {
        final Identity.Public identity;final PreKey backup,oneTime;final List<PreKey> refill;
        public Recipient(Identity.Public identity,PreKey backup,PreKey oneTime,List<PreKey> refill){
            this.identity=identity;this.backup=backup;this.oneTime=oneTime;this.refill=refill==null?Collections.emptyList():new ArrayList<>(refill);
        }
        public boolean reducedProtection(){return oneTime==null;}
    }
    public static final class Content {
        final String name,mime;final byte[] body;final long ttlMillis;final boolean viewOnce;
        public Content(String name,String mime,byte[] body,long ttlMillis,boolean viewOnce){this.name=name;this.mime=mime;this.body=body;this.ttlMillis=ttlMillis;this.viewOnce=viewOnce;}
    }
    public static final class Opened {
        public final String sender,messageId,name,mime;public final byte[] body;public final long createdAt,expiresAt;public final boolean viewOnce;
        public final List<String> recipients;public final List<PreKey> refill;public final PreKey senderBackup;public final byte[] carrier;
        /** The one-time key this message used, or null when it used the backup key ("reduced protection"). */
        public final byte[] oneTimeId;
        Opened(String sender,String messageId,String name,String mime,byte[] body,long createdAt,long expiresAt,boolean viewOnce,List<String> recipients,List<PreKey> refill,PreKey senderBackup,byte[] carrier,byte[] oneTimeId){
            this.sender=sender;this.messageId=messageId;this.name=name;this.mime=mime;this.body=body;this.createdAt=createdAt;this.expiresAt=expiresAt;this.viewOnce=viewOnce;
            this.recipients=Collections.unmodifiableList(recipients);this.refill=Collections.unmodifiableList(refill);this.senderBackup=senderBackup;this.carrier=carrier;this.oneTimeId=oneTimeId;
        }
        public boolean reducedProtection(){return oneTimeId==null;}
    }

    // ---- sending ----
    public static byte[] seal(Protocol.Group g,Identity me,PreKey myBackup,List<Recipient> to,Content c,byte[] carrier,Entropy entropy)throws Exception{
        Protocol.live(g);String self=me.fingerprint();
        if(!g.members.containsKey(self))throw new SecurityException("You are not a member of this group");
        if(to==null||to.isEmpty())throw new SecurityException("Choose at least one recipient");
        if(to.size()>MAX_RECIPIENTS)throw new SecurityException("Too many recipients");
        if(c.body.length>MAX_CONTENT)throw new SecurityException("File is too large");
        if(!myBackup.verify(me.publicKey,PreKey.BACKUP))throw new SecurityException("Invalid own backup key");
        Set<String> seen=new HashSet<>();
        for(Recipient r:to){
            String fp=r.identity.fingerprint();
            if(fp.equals(self))throw new SecurityException("You cannot send a message to yourself");
            if(!seen.add(fp))throw new SecurityException("Recipient listed twice");
            if(!r.identity.equals(member(g,fp)))throw new SecurityException("Recipient is not a member of this group");
            if(r.backup==null||!r.backup.verify(r.identity,PreKey.BACKUP))throw new SecurityException("Recipient backup key is not signed by them");
            if(r.oneTime!=null&&!r.oneTime.verify(r.identity,PreKey.ONE_TIME))throw new SecurityException("Recipient one-time key is not signed by them");
            if(r.refill.size()>MAX_REFILL)throw new SecurityException("Too many refill keys");
            for(PreKey k:r.refill)if(!k.verify(me.publicKey,PreKey.ONE_TIME))throw new SecurityException("Refill keys must be your own");
        }
        String messageId=Protocol.b64(Arrays.copyOf(entropy.next32("message-id"),16));
        long created=System.currentTimeMillis(),expires=c.ttlMillis<=0?0:created+c.ttlMillis;
        byte[] contentKey=entropy.next32("content-key"),nonce=Arrays.copyOf(entropy.next32("content-nonce"),12);
        try{
            List<byte[]> entries=new ArrayList<>();
            for(Recipient r:to){
                byte[] ephemeral=entropy.next32("ephemeral"),ephemeralPublic=Identity.x25519Public(ephemeral),secret=null,encapsRandom=entropy.next32("kem-encapsulation");
                byte[][] kem=PostQuantum.encapsulate(r.oneTime==null?r.backup.kemPublic:r.oneTime.kemPublic,encapsRandom);
                try{
                    byte[] oneTimeId=r.oneTime==null?NO_KEY:r.oneTime.id,kemCipher=kem[1];
                    byte[] info=info(g,messageId,self,r.identity.fingerprint(),r.backup.id,oneTimeId,ephemeralPublic,kemCipher);
                    secret=hybrid(g,info,me.agree(r.backup.publicKey),Identity.agree(ephemeral,r.identity.agree),Identity.agree(ephemeral,r.backup.publicKey),r.oneTime==null?null:Identity.agree(ephemeral,r.oneTime.publicKey),kem[0]);
                    byte[] wrapNonce=Arrays.copyOf(entropy.next32("wrap-nonce"),12),wrapped=Protocol.aes(Cipher.ENCRYPT_MODE,secret,wrapNonce,info,contentKey);
                    entries.add(Packets.pack(o->{o.write(r.backup.id);o.write(oneTimeId);o.write(ephemeralPublic);o.write(kemCipher);o.write(wrapNonce);Packets.blob(o,wrapped);}));
                }finally{Arrays.fill(ephemeral,(byte)0);Arrays.fill(encapsRandom,(byte)0);Arrays.fill(kem[0],(byte)0);if(secret!=null)Arrays.fill(secret,(byte)0);}
            }
            byte[] outer=Packets.pack(o->{o.writeUTF(MAGIC);o.writeUTF(g.id);o.writeUTF(messageId);o.writeUTF(self);o.writeInt(entries.size());for(byte[] e:entries)Packets.blob(o,e);});
            byte[] inner=Packets.pack(o->{
                o.writeUTF("Enigma/sealed/v2");o.writeUTF(g.id);o.writeLong(g.rosterRevision);o.writeUTF(messageId);o.writeUTF(self);Packets.blob(o,me.publicKey.encode());
                o.writeLong(created);o.writeLong(expires);o.writeBoolean(c.viewOnce);o.writeUTF(c.name);o.writeUTF(c.mime);Packets.blob(o,sha256(c.body));
                o.writeInt(to.size());for(Recipient r:to){o.writeUTF(r.identity.fingerprint());o.write(r.backup.id);o.write(r.oneTime==null?NO_KEY:r.oneTime.id);o.writeInt(r.refill.size());for(PreKey k:r.refill)k.write(o);}
                myBackup.write(o);Packets.blob(o,carrier==null?new byte[0]:carrier);
            });
            byte[] signature=me.sign(signedPart(outer,inner));
            byte[] plain=Packets.pack(o->{Packets.blob(o,inner);Packets.blob(o,signature);Packets.blob(o,c.body);});
            byte[] cipher;try{cipher=Protocol.aes(Cipher.ENCRYPT_MODE,contentKey,nonce,outer,plain);}finally{Arrays.fill(plain,(byte)0);}
            return Packets.pack(o->{Packets.blob(o,outer);o.write(nonce);Packets.blob(o,cipher);});
        }finally{Arrays.fill(contentKey,(byte)0);}
    }

    /** Send as text: the same package as pasteable base64 behind this prefix. */
    public static final String TEXT_PREFIX="ENG5:";
    public static String toText(byte[] packet){return TEXT_PREFIX+Protocol.b64(packet);}
    public static byte[] fromText(String text){String wire=Packets.clean(text);if(!wire.startsWith(TEXT_PREFIX))throw new SecurityException("That is not an Enigma message.");return Protocol.un64(wire.substring(TEXT_PREFIX.length()));}
    /** The group a package belongs to, for routing before any key is touched. */
    public static String groupOf(byte[] packet)throws IOException{return outer(packet)[0];}
    /** The package's message ID, readable before decryption (to find an already-opened copy). */
    public static String idOf(byte[] packet)throws IOException{return outer(packet)[1];}
    private static String[] outer(byte[] packet)throws IOException{DataInputStream in=Packets.input(Packets.blob(Packets.input(packet),MAX_OUTER));if(!MAGIC.equals(in.readUTF()))throw new IOException("Not an Enigma message");return new String[]{in.readUTF(),in.readUTF()};}

    // ---- receiving ----
    /**
     * Verifies and decrypts without changing any state. After the caller has stored the result it
     * must call {@link #accept}, which deletes the one-time key and stores the sender's new keys.
     */
    public static Opened open(Protocol.Group g,Identity me,KeyPools pools,byte[] packet)throws Exception{
        Protocol.live(g);String self=me.fingerprint();
        DataInputStream p=Packets.input(packet);byte[] outer=Packets.blob(p,MAX_OUTER);byte[] nonce=new byte[12];p.readFully(nonce);byte[] cipher=Packets.blob(p,(int)MAX_CONTENT+1024*1024);Packets.end(p);
        DataInputStream o=Packets.input(outer);
        if(!MAGIC.equals(o.readUTF()))throw new SecurityException("Not an Enigma message");
        String groupId=o.readUTF(),messageId=o.readUTF(),senderId=o.readUTF();
        if(!g.id.equals(groupId))throw new SecurityException("This message belongs to another group");
        if(senderId.equals(self))throw new SecurityException("This is your own message");
        Identity.Public sender=member(g,senderId);if(sender==null)throw new SecurityException("Sender is not a member of this group");
        int count=o.readInt();if(count<1||count>MAX_RECIPIENTS)throw new SecurityException("Invalid recipient count");
        byte[] contentKey=null,usedOneTime=null,usedBackup=null;
        for(int i=0;i<count;i++){
            DataInputStream e=Packets.input(Packets.blob(o,MAX_ENTRY));byte[] backupId=new byte[8],oneTimeId=new byte[8],ephemeral=new byte[32],kemCipher=new byte[PostQuantum.KEM_CIPHERTEXT],wrapNonce=new byte[12];
            e.readFully(backupId);e.readFully(oneTimeId);e.readFully(ephemeral);e.readFully(kemCipher);e.readFully(wrapNonce);byte[] wrapped=Packets.blob(e,64);Packets.end(e);
            if(contentKey!=null||!pools.ownsBackup(backupId))continue;
            boolean reduced=Arrays.equals(oneTimeId,NO_KEY);byte[] oneTimePrivate=reduced?null:pools.oneTimePrivate(oneTimeId);
            if(!reduced&&oneTimePrivate==null)throw new SecurityException("This message was already opened, or its one-time key no longer exists");
            byte[] backupPrivate=pools.backupPrivate(backupId),info=info(g,messageId,senderId,self,backupId,oneTimeId,ephemeral,kemCipher);
            byte[] backupX=Arrays.copyOf(backupPrivate,32),oneTimeX=reduced?null:Arrays.copyOf(oneTimePrivate,32),kemSeed=Arrays.copyOfRange(reduced?backupPrivate:oneTimePrivate,32,KeyPools.PRIVATE),secret;
            try{secret=hybrid(g,info,Identity.agree(backupX,sender.agree),me.agree(ephemeral),Identity.agree(backupX,ephemeral),reduced?null:Identity.agree(oneTimeX,ephemeral),PostQuantum.decapsulate(kemSeed,kemCipher));}
            finally{Arrays.fill(backupX,(byte)0);if(oneTimeX!=null)Arrays.fill(oneTimeX,(byte)0);Arrays.fill(kemSeed,(byte)0);}
            try{contentKey=Protocol.aes(Cipher.DECRYPT_MODE,secret,wrapNonce,info,wrapped);}finally{Arrays.fill(secret,(byte)0);}
            if(contentKey.length!=32)throw new SecurityException("Invalid content key");
            usedOneTime=reduced?null:oneTimeId;usedBackup=backupId;
        }
        Packets.end(o);
        if(contentKey==null)throw new SecurityException("This message is not addressed to you");
        byte[] plain;try{plain=Protocol.aes(Cipher.DECRYPT_MODE,contentKey,nonce,outer,cipher);}finally{Arrays.fill(contentKey,(byte)0);}
        DataInputStream pl=Packets.input(plain);byte[] inner=Packets.blob(pl,MAX_INNER),signature=Packets.blob(pl,Identity.SIGNATURE),body=Packets.blob(pl,(int)MAX_CONTENT);Packets.end(pl);
        if(!sender.verify(signedPart(outer,inner),signature))throw new SecurityException("Sender signature is invalid");
        DataInputStream in=Packets.input(inner);
        if(!"Enigma/sealed/v2".equals(in.readUTF())||!groupId.equals(in.readUTF()))throw new SecurityException("Message header mismatch");
        long revision=in.readLong();
        if(!messageId.equals(in.readUTF())||!senderId.equals(in.readUTF())||!sender.equals(Identity.Public.decode(Packets.blob(in,Identity.PUBLIC))))throw new SecurityException("Message header mismatch");
        long created=in.readLong(),expires=in.readLong();boolean viewOnce=in.readBoolean();String name=in.readUTF(),mime=in.readUTF();
        if(!MessageDigest.isEqual(sha256(body),Packets.blob(in,32)))throw new SecurityException("Content hash mismatch");
        int n=in.readInt();if(n!=count)throw new SecurityException("Recipient list mismatch");
        List<String> recipients=new ArrayList<>();List<PreKey> mine=null;
        for(int i=0;i<n;i++){
            String fp=in.readUTF();byte[] backupId=new byte[8],oneTimeId=new byte[8];in.readFully(backupId);in.readFully(oneTimeId);
            int refills=in.readInt();if(refills<0||refills>MAX_REFILL)throw new SecurityException("Too many refill keys");
            List<PreKey> keys=new ArrayList<>();for(int j=0;j<refills;j++)keys.add(PreKey.read(in));recipients.add(fp);
            if(fp.equals(self)){
                if(mine!=null||!Arrays.equals(backupId,usedBackup)||!Arrays.equals(oneTimeId,usedOneTime==null?NO_KEY:usedOneTime))throw new SecurityException("Recipient list does not match the keys used");
                for(PreKey k:keys)if(!k.verify(sender,PreKey.ONE_TIME))throw new SecurityException("Refill key not signed by the sender");mine=keys;
            }
        }
        if(mine==null)throw new SecurityException("You are not in the signed recipient list");
        PreKey senderBackup=PreKey.read(in);if(!senderBackup.verify(sender,PreKey.BACKUP))throw new SecurityException("Sender backup key is invalid");
        byte[] carrier=Packets.blob(in,JoinProtocol.MAX_CARRIER);Packets.end(in);
        if(revision<0)throw new SecurityException("Invalid roster revision");
        if(expires!=0&&System.currentTimeMillis()>expires)throw new SecurityException("This message has expired");
        return new Opened(senderId,messageId,name,mime,body,created,expires,viewOnce,recipients,mine,senderBackup,carrier,usedOneTime);
    }
    /** After an opened message is stored: delete its one-time key and keep the sender's new keys. */
    public static void accept(Protocol.Group g,KeyPools pools,Opened m){
        if(m.oneTimeId!=null)pools.consume(m.oneTimeId);
        Identity.Public sender=member(g,m.sender);pools.addTheirs(m.sender,sender,m.refill);pools.setTheirBackup(m.sender,sender,m.senderBackup);
    }

    // ---- helpers ----
    static Identity.Public member(Protocol.Group g,String fingerprint){
        String encoded=g.members.get(fingerprint);if(encoded==null||g.blocked.contains(fingerprint))return null;
        Identity.Public key=Identity.Public.decode(Protocol.un64(encoded));if(!key.fingerprint().equals(fingerprint))throw new SecurityException("Member key does not match its fingerprint");return key;
    }
    private static byte[] info(Protocol.Group g,String messageId,String sender,String recipient,byte[] backupId,byte[] oneTimeId,byte[] ephemeral,byte[] kemCipher)throws Exception{
        return Packets.pack(o->{o.writeUTF("Enigma/PQX3DH/v1");o.writeUTF(g.id);o.writeUTF(messageId);o.writeUTF(sender);o.writeUTF(recipient);o.write(backupId);o.write(oneTimeId);o.write(ephemeral);o.write(kemCipher);});
    }
    /**
     * Hybrid X3DH (as in Signal's PQXDH): KDF(F || DH1 || DH2 || DH3 [|| DH4] || ML-KEM secret) with
     * the group secret as salt, so non-members cannot derive it and both algorithm families must fail.
     */
    private static byte[] hybrid(Protocol.Group g,byte[] info,byte[] dh1,byte[] dh2,byte[] dh3,byte[] dh4,byte[] kem)throws Exception{
        ByteArrayOutputStream km=new ByteArrayOutputStream();byte[] f=new byte[32];Arrays.fill(f,(byte)0xff);km.write(f);km.write(dh1);km.write(dh2);km.write(dh3);if(dh4!=null)km.write(dh4);km.write(kem);
        byte[] material=km.toByteArray();
        try{return Protocol.hkdf(material,g.seed,info);}finally{Arrays.fill(material,(byte)0);Arrays.fill(dh1,(byte)0);Arrays.fill(dh2,(byte)0);Arrays.fill(dh3,(byte)0);if(dh4!=null)Arrays.fill(dh4,(byte)0);Arrays.fill(kem,(byte)0);}
    }
    private static byte[] signedPart(byte[] outer,byte[] inner)throws Exception{return Packets.pack(o->{o.writeUTF("Enigma/sealed-signature/v2");Packets.blob(o,sha256(outer));Packets.blob(o,inner);});}
    private static byte[] sha256(byte[] b)throws Exception{return MessageDigest.getInstance("SHA-256").digest(b);}
}
