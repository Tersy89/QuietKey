package app.quietkey;

import org.junit.*;
import java.security.*;
import java.util.*;
import java.io.*;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public final class DirectProtocolTest {
    private static KeyPair a,b,c;
    private Protocol.Group group,receiver,third;
    @BeforeClass public static void identities() throws Exception {
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);a=gen.generateKeyPair();b=gen.generateKeyPair();c=gen.generateKeyPair();
    }
    @Before public void setup() throws Exception {
        group=Protocol.create("Private",Protocol.random(32),a.getPublic());
        for(KeyPair key:new KeyPair[]{b,c})group.members.put(Protocol.fingerprint(key.getPublic()),Protocol.b64(key.getPublic().getEncoded()));
        receiver=Protocol.accept(Protocol.invite(group,b.getPublic(),a.getPublic(),a.getPrivate()),b.getPublic(),b.getPrivate());
        third=Protocol.accept(Protocol.invite(group,c.getPublic(),a.getPublic(),a.getPrivate()),c.getPublic(),c.getPrivate());
        third.members.put(Protocol.fingerprint(b.getPublic()),Protocol.b64(b.getPublic().getEncoded()));
    }
    private String wire() throws Exception {return DirectProtocol.encrypt(group,Protocol.fingerprint(b.getPublic()),"Private 👋",a.getPublic(),a.getPrivate());}
    @Test public void missingAndSelfRecipientCannotSend()throws Exception{
        assertThrows(SecurityException.class,()->DirectProtocol.encrypt(group,null,"No",a.getPublic(),a.getPrivate()));
        assertThrows(SecurityException.class,()->DirectProtocol.encrypt(group,group.owner,"No",a.getPublic(),a.getPrivate()));
    }
    @Test public void ttlIsReturnedAndExpiredTextCannotOpen()throws Exception{
        String wire=DirectProtocol.encrypt(group,Protocol.fingerprint(b.getPublic()),"Short lived",a.getPublic(),a.getPrivate(),1000);
        DirectProtocol.Opened opened=DirectProtocol.decrypt(receiver,wire,b.getPublic(),b.getPrivate());assertEquals(1000,opened.expiresAt-opened.createdAt);
        Thread.sleep(Math.max(0,opened.expiresAt-System.currentTimeMillis()+5));assertThrows(SecurityException.class,()->DirectProtocol.decrypt(receiver,wire,b.getPublic(),b.getPrivate()));
    }
    @Test public void changingExpiryBreaksSignatureAndGcmBinding()throws Exception{
        byte[] packet=Protocol.un64(wire().substring(5));DataInputStream outer=new DataInputStream(new ByteArrayInputStream(packet));int bodySize=outer.readInt();int headerSize=outer.readInt();byte[] header=new byte[headerSize];outer.readFully(header);
        DataInputStream h=new DataInputStream(new ByteArrayInputStream(header));for(int i=0;i<4;i++)h.readUTF();long created=h.readLong();int expiryOffset=headerSize-h.available();ByteBuffer.wrap(packet).putLong(8+expiryOffset,created+3600000);
        String changed="QKD3:"+Protocol.b64(packet);assertThrows(SecurityException.class,()->DirectProtocol.decrypt(receiver,changed,b.getPublic(),b.getPrivate()));
        Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(a.getPrivate());signer.update(packet,4,bodySize);System.arraycopy(signer.sign(),0,packet,packet.length-256,256);
        assertThrows(javax.crypto.AEADBadTagException.class,()->DirectProtocol.decrypt(receiver,"QKD3:"+Protocol.b64(packet),b.getPublic(),b.getPrivate()));
    }
    @Test public void recipientCanReadAndSenderCanReadOwnCopy() throws Exception {
        String wire=wire();assertEquals("Private 👋",DirectProtocol.decrypt(receiver,wire,b.getPublic(),b.getPrivate()).text);
        assertEquals("Private 👋",DirectProtocol.decrypt(group,wire,a.getPublic(),a.getPrivate()).text);
    }
    @Test public void anotherGroupMemberWithSameSeedCannotRead() throws Exception {
        String wire=wire();assertArrayEquals(group.seed,third.seed);
        assertThrows(SecurityException.class,()->DirectProtocol.decrypt(third,wire,c.getPublic(),c.getPrivate()));
        assertThrows(Exception.class,()->DirectProtocol.decrypt(third,wire,b.getPublic(),c.getPrivate()));
    }
    @Test public void groupSeedDoesNotDecryptPrivateContent() throws Exception {
        String wire=wire();Arrays.fill(receiver.seed,(byte)0);assertThrows(javax.crypto.AEADBadTagException.class,()->DirectProtocol.decrypt(receiver,wire,b.getPublic(),b.getPrivate()));
    }
    @Test public void nonMembersCannotBeRecipients() throws Exception {
        assertThrows(SecurityException.class,()->DirectProtocol.encrypt(group,Protocol.b64(Protocol.random(32)),"No",a.getPublic(),a.getPrivate()));
    }
    @Test public void replayAndUntrustedSenderRejected() throws Exception {
        String wire=wire();DirectProtocol.Opened opened=DirectProtocol.decrypt(receiver,wire,b.getPublic(),b.getPrivate());receiver.seen.add(opened.messageId);
        assertThrows(SecurityException.class,()->DirectProtocol.decrypt(receiver,wire,b.getPublic(),b.getPrivate()));
        receiver.seen.clear();receiver.members.remove(group.owner);assertThrows(SecurityException.class,()->DirectProtocol.decrypt(receiver,wire,b.getPublic(),b.getPrivate()));
    }
    @Test public void tamperingTruncationAndOversizeRejected() throws Exception {
        String wire=wire();byte[] bytes=Protocol.un64(wire.substring(5));bytes[bytes.length-1]^=1;
        assertThrows(Exception.class,()->DirectProtocol.decrypt(receiver,"QKD3:"+Protocol.b64(bytes),b.getPublic(),b.getPrivate()));
        assertThrows(Exception.class,()->DirectProtocol.decrypt(receiver,"QKD3:AAAA",b.getPublic(),b.getPrivate()));
        assertThrows(IllegalArgumentException.class,()->DirectProtocol.encrypt(group,Protocol.fingerprint(b.getPublic()),"x".repeat(16001),a.getPublic(),a.getPrivate()));
    }
    @Test public void wrongGroupExpiredGroupAndTrailingDataRejected() throws Exception {
        String wire=wire();Protocol.Group other=Protocol.create("Other",Protocol.random(32),b.getPublic());
        assertThrows(SecurityException.class,()->DirectProtocol.decrypt(other,wire,b.getPublic(),b.getPrivate()));
        Protocol.Group expired=new Protocol.Group(group.id,group.name,group.seed,1,group.owner);expired.members.putAll(group.members);
        assertThrows(SecurityException.class,()->DirectProtocol.encrypt(expired,Protocol.fingerprint(b.getPublic()),"No",a.getPublic(),a.getPrivate()));
        byte[] bytes=Protocol.un64(wire.substring(5));assertThrows(Exception.class,()->DirectProtocol.decrypt(receiver,"QKD3:"+Protocol.b64(Arrays.copyOf(bytes,bytes.length+1)),b.getPublic(),b.getPrivate()));
    }
}
