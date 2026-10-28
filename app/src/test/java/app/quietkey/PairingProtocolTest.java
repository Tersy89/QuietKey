package app.quietkey;
import org.junit.*;
import java.security.*;
import java.util.*;
import java.io.*;
import static org.junit.Assert.*;

public final class PairingProtocolTest {
    private static KeyPair a,b,c;private Protocol.Group owner;
    @BeforeClass public static void keys()throws Exception{KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);a=gen.generateKeyPair();b=gen.generateKeyPair();c=gen.generateKeyPair();}
    @Before public void group()throws Exception{owner=Protocol.create("Family",Protocol.random(32),a.getPublic());owner.memberNames.put(owner.owner,"A");PairingProtocol.signRoster(owner,a.getPublic(),a.getPrivate());}
    private String request(KeyPair p)throws Exception{return PairingProtocol.request(p.getPublic(),p.getPrivate(),"B");}
    private Protocol.Group join(KeyPair p)throws Exception{String req=request(p);return PairingProtocol.accept(PairingProtocol.invite(owner,PairingProtocol.parseRequest(req),a.getPublic(),a.getPrivate()),req,p.getPublic(),p.getPrivate());}
    @Test public void twoStepTransferImportsSecretAndAllPublicKeys()throws Exception{owner.members.put(Protocol.fingerprint(c.getPublic()),Protocol.b64(c.getPublic().getEncoded()));Protocol.Group joined=join(b);assertArrayEquals(owner.seed,joined.seed);assertEquals(owner.members,joined.members);assertEquals(owner.roster,joined.roster);assertEquals(3,joined.members.size());}
    @Test public void photographedInvitationCannotBeUsedByAnotherDevice()throws Exception{String req=request(b),wire=PairingProtocol.invite(owner,PairingProtocol.parseRequest(req),a.getPublic(),a.getPrivate());assertThrows(SecurityException.class,()->PairingProtocol.accept(wire,request(c),c.getPublic(),c.getPrivate()));assertThrows(Exception.class,()->PairingProtocol.accept(wire,req,b.getPublic(),c.getPrivate()));}
    @Test public void wrongJoiningSessionIsRejected()throws Exception{String req=request(b),wire=PairingProtocol.invite(owner,PairingProtocol.parseRequest(req),a.getPublic(),a.getPrivate());assertThrows(SecurityException.class,()->PairingProtocol.accept(wire,request(b),b.getPublic(),b.getPrivate()));}
    @Test public void invitationTamperingIsRejected()throws Exception{String req=request(b),wire=PairingProtocol.invite(owner,PairingProtocol.parseRequest(req),a.getPublic(),a.getPrivate());byte[] bytes=Protocol.un64(wire.substring(5));bytes[bytes.length-1]^=1;assertThrows(SecurityException.class,()->PairingProtocol.accept("QKG2:"+Protocol.b64(bytes),req,b.getPublic(),b.getPrivate()));}
    @Test public void nonOwnerAndForgedOwnerCannotChangeOriginalMembership()throws Exception{String req=request(b);assertThrows(SecurityException.class,()->PairingProtocol.invite(owner,PairingProtocol.parseRequest(req),c.getPublic(),c.getPrivate()));assertEquals(1,owner.members.size());assertThrows(SecurityException.class,()->PairingProtocol.invite(owner,PairingProtocol.parseRequest(req),a.getPublic(),c.getPrivate()));assertEquals(1,owner.members.size());}
    @Test public void newMemberLearnsExistingAndExistingMemberAutomaticallyLearnsNewMember()throws Exception{Protocol.Group gb=join(b),gc=join(c);assertFalse(gb.members.containsKey(Protocol.fingerprint(c.getPublic())));String msg=DirectProtocol.encrypt(gc,Protocol.fingerprint(b.getPublic()),"C to B",c.getPublic(),c.getPrivate());assertEquals("C to B",DirectProtocol.decrypt(gb,msg,b.getPublic(),b.getPrivate()).text);assertEquals(owner.members,gb.members);}
    @Test public void directoryCannotBeForgedByCompromisedMember()throws Exception{Protocol.Group gb=join(b);byte[] bytes=Protocol.un64(owner.roster);bytes[bytes.length-1]^=1;assertThrows(SecurityException.class,()->PairingProtocol.rosterView(gb,bytes));assertThrows(SecurityException.class,()->PairingProtocol.signRoster(gb,b.getPublic(),b.getPrivate()));}
    @Test public void olderSignedDirectoryNeverRollsBackMembership()throws Exception{Protocol.Group gb=join(b);byte[] old=Protocol.un64(owner.roster);join(c);PairingProtocol.applyRoster(gb,PairingProtocol.rosterView(gb,Protocol.un64(owner.roster)));long revision=gb.rosterRevision;assertSame(gb,PairingProtocol.rosterView(gb,old));assertEquals(revision,gb.rosterRevision);assertEquals(3,gb.members.size());}
    @Test public void localBlockingSurvivesDirectoryUpdate()throws Exception{Protocol.Group gb=join(b);join(c);PairingProtocol.applyRoster(gb,PairingProtocol.rosterView(gb,Protocol.un64(owner.roster)));String id=Protocol.fingerprint(c.getPublic());gb.blocked.add(id);gb.members.remove(id);PairingProtocol.signRoster(owner,a.getPublic(),a.getPrivate());PairingProtocol.applyRoster(gb,PairingProtocol.rosterView(gb,Protocol.un64(owner.roster)));assertFalse(gb.members.containsKey(id));}
    @Test public void validJoinConfirmationNeedsTheJoiningPrivateKeyAndPendingRequest()throws Exception{String req=request(b);PairingProtocol.Request r=PairingProtocol.parseRequest(req);Protocol.Group gb=PairingProtocol.accept(PairingProtocol.invite(owner,r,a.getPublic(),a.getPrivate()),req,b.getPublic(),b.getPrivate());Map<String,String> pending=new HashMap<>();pending.put(r.id,Protocol.fingerprint(b.getPublic()));String forged=PairingProtocol.receipt(gb,r.id,b.getPublic(),c.getPrivate());assertThrows(SecurityException.class,()->PairingProtocol.receiptMember(owner,forged,pending));assertEquals(1,pending.size());String receipt=PairingProtocol.receipt(gb,r.id,b.getPublic(),b.getPrivate());assertEquals(Protocol.fingerprint(b.getPublic()),PairingProtocol.receiptMember(owner,receipt,pending));assertTrue(pending.isEmpty());assertThrows(SecurityException.class,()->PairingProtocol.receiptMember(owner,receipt,pending));}
    @Test public void changedOrOversizedRequestCannotBeAccepted()throws Exception{byte[] bytes=Protocol.un64(request(b).substring(5));bytes[bytes.length-1]^=1;assertThrows(SecurityException.class,()->PairingProtocol.parseRequest("QKJ1:"+Protocol.b64(bytes)));assertThrows(SecurityException.class,()->PairingProtocol.parseRequest("QKJ1:"+"a".repeat(2000)));}
    @Test public void correctedGroupLayerRequiresBothSecretsForTextAndFiles()throws Exception{Protocol.Group gb=join(b),gc=join(c);String wire=DirectProtocol.encrypt(owner,Protocol.fingerprint(b.getPublic()),"Private",a.getPublic(),a.getPrivate());assertThrows(SecurityException.class,()->DirectProtocol.decrypt(gc,wire,c.getPublic(),c.getPrivate()));Arrays.fill(gb.seed,(byte)0);assertThrows(Exception.class,()->DirectProtocol.decrypt(gb,wire,b.getPublic(),b.getPrivate()));}
    @Test public void fileFromNewMemberImportsDirectoryOnlyAfterCompleteVerification()throws Exception {
        Protocol.Group gb=join(b),gc=join(c);byte[] source=Protocol.random(100000);File file=File.createTempFile("enigma-directory-",".enigma");
        try {
            try(OutputStream out=new FileOutputStream(file)){FileProtocol.encrypt(gc,Protocol.fingerprint(b.getPublic()),new ByteArrayInputStream(source),out,"image.png","image/png",c.getPublic(),c.getPrivate());}
            byte[] packet=java.nio.file.Files.readAllBytes(file.toPath()),bad=packet.clone();bad[bad.length-1]^=1;java.nio.file.Files.write(file.toPath(),bad);
            assertThrows(Exception.class,()->FileProtocol.inspect(gb,file,b.getPublic(),b.getPrivate()));assertFalse(gb.members.containsKey(Protocol.fingerprint(c.getPublic())));
            java.nio.file.Files.write(file.toPath(),packet);
            try(FileProtocol.Verified verified=FileProtocol.inspect(gb,file,b.getPublic(),b.getPrivate())){ByteArrayOutputStream restored=new ByteArrayOutputStream();FileProtocol.restore(verified,restored);assertArrayEquals(source,restored.toByteArray());}
            assertEquals(owner.members,gb.members);
        } finally {assertTrue(file.delete());}
    }
    @Test public void expiredSignedRequestCannotCreateAnInvitation()throws Exception {
        long created=System.currentTimeMillis()-PairingProtocol.WINDOW-1000;byte[] body=Packets.pack(o->{o.writeUTF("join-request-v1");Packets.blob(o,b.getPublic().getEncoded());o.writeUTF("B");Packets.blob(o,Protocol.random(16));o.writeLong(created);o.writeLong(created+PairingProtocol.WINDOW);});
        String wire="QKJ1:"+Protocol.b64(Packets.signed(body,b.getPrivate()));assertThrows(SecurityException.class,()->PairingProtocol.parseRequest(wire));assertEquals(1,owner.members.size());
    }
    @Test public void explicitOwnerReinvitationClearsOnlyThatLocalBlock()throws Exception {
        join(b);String id=Protocol.fingerprint(b.getPublic());owner.members.remove(id);owner.memberNames.remove(id);owner.blocked.add(id);String other=Protocol.fingerprint(c.getPublic());owner.blocked.add(other);PairingProtocol.signRoster(owner,a.getPublic(),a.getPrivate());
        join(b);assertFalse(owner.blocked.contains(id));assertTrue(owner.blocked.contains(other));assertTrue(owner.members.containsKey(id));
    }
}
