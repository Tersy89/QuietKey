package app.quietkey;

import org.junit.*;
import java.io.*;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import static org.junit.Assert.*;

/** Evidence for the 0.3 security review, including the documented missing group-key layer. */
public final class SecurityReviewTest {
    private static KeyPair a,b,c;
    private Protocol.Group group;
    @BeforeClass public static void identities()throws Exception{
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);
        a=gen.generateKeyPair();b=gen.generateKeyPair();c=gen.generateKeyPair();
    }
    @Before public void setup()throws Exception{
        group=Protocol.create("Review",Protocol.random(32),a.getPublic());
        for(KeyPair p:new KeyPair[]{b,c})group.members.put(Protocol.fingerprint(p.getPublic()),Protocol.b64(p.getPublic().getEncoded()));
    }
    private byte[][] wrappedKeys(String wire)throws Exception{
        DataInputStream outer=new DataInputStream(new ByteArrayInputStream(Protocol.un64(wire.substring(5))));
        DataInputStream body=new DataInputStream(new ByteArrayInputStream(blob(outer)));
        DataInputStream h=new DataInputStream(new ByteArrayInputStream(blob(body)));
        for(int i=0;i<4;i++)h.readUTF();h.readLong();h.readLong();blob(h);blob(h);
        return new byte[][]{blob(h),blob(h)};
    }
    private static byte[] blob(DataInputStream in)throws Exception{byte[] bytes=new byte[in.readInt()];in.readFully(bytes);return bytes;}
    @Test public void compromisedCPrivateKeyCannotUnwrapEitherABKeyWithoutClientChecks()throws Exception{
        String wire=DirectProtocol.encrypt(group,Protocol.fingerprint(b.getPublic()),"A to B",a.getPublic(),a.getPrivate());
        for(byte[] wrapped:wrappedKeys(wire))assertThrows(GeneralSecurityException.class,()->Protocol.rsa(Cipher.DECRYPT_MODE,c.getPrivate(),wrapped));
    }
    @Test public void compromisedCCannotSignAsA()throws Exception{
        String forged=DirectProtocol.encrypt(group,Protocol.fingerprint(b.getPublic()),"Forged A",a.getPublic(),c.getPrivate());
        assertThrows(SecurityException.class,()->DirectProtocol.decrypt(group,forged,b.getPublic(),b.getPrivate()));
    }
    @Test public void new04RecipientRequiresGroupSecret()throws Exception{
        String wire=DirectProtocol.encrypt(group,Protocol.fingerprint(b.getPublic()),"Group layer missing",a.getPublic(),a.getPrivate());
        Protocol.Group missing=new Protocol.Group(group.id,group.name,new byte[32],group.expires,group.owner);missing.members.putAll(group.members);
        assertThrows(javax.crypto.AEADBadTagException.class,()->DirectProtocol.decrypt(missing,wire,b.getPublic(),b.getPrivate()));
    }
    @Test public void deviceIdentitiesCanBeReusedWithIndependentGroupSecrets()throws Exception{
        Protocol.Group other=Protocol.create("Other",Protocol.random(32),a.getPublic());
        assertEquals(group.owner,other.owner);assertNotEquals(group.id,other.id);assertFalse(Arrays.equals(group.seed,other.seed));
    }
    @Test public void compromisedNonOwnerCannotIssueOwnerSignedInvitation()throws Exception{
        assertThrows(SecurityException.class,()->Protocol.invite(group,b.getPublic(),c.getPublic(),c.getPrivate()));
        String forged=Protocol.invite(group,b.getPublic(),a.getPublic(),c.getPrivate());
        assertThrows(SecurityException.class,()->Protocol.accept(forged,b.getPublic(),b.getPrivate()));
    }
}
