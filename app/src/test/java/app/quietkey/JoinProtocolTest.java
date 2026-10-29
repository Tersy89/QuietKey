package app.quietkey;

import org.junit.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** 0.7.0 joining: QKJ4/QKG4/QKA4, owner-signed v4 rosters and introductions for later members. */
public final class JoinProtocolTest {
    private static final class Device {
        final Identity id;final KeyPools pools=new KeyPools();final String fp;Protocol.Group group;
        Device(Entropy e){id=Identity.generate(e);fp=id.fingerprint();}
    }
    private Entropy entropy;private Device a,b,c;private final Map<String,String> pending=new HashMap<>();

    @Before public void setup()throws Exception{
        entropy=new Entropy();entropy.addMotion(1,42L,new float[]{0.3f,9.7f,0.1f});
        a=new Device(entropy);b=new Device(entropy);c=new Device(entropy);
        a.group=JoinProtocol.create("Family","Alex",a.id,a.pools,entropy);
    }
    /** The full exchange the nearby/manual transports carry: request → invitation → receipt. */
    private void join(Device d,String name)throws Exception{
        String request=JoinProtocol.request(d.id,d.pools,entropy,name);JoinProtocol.Request parsed=JoinProtocol.parseRequest(request);
        String invitation=JoinProtocol.invite(a.group,parsed,a.id,a.pools,entropy);pending.put(parsed.id,d.fp);
        d.group=JoinProtocol.accept(invitation,request,d.id,d.pools);
        assertEquals(d.fp,JoinProtocol.receiptMember(a.group,JoinProtocol.receipt(d.group,parsed.id,d.id,d.pools,entropy),pending,a.pools));
    }
    private byte[] send(Device from,String text,Device to)throws Exception{
        List<SealedProtocol.Recipient> list=Collections.singletonList(new SealedProtocol.Recipient(to.id.publicKey,from.pools.theirBackup(to.fp),from.pools.take(to.fp),from.pools.issue(from.id,entropy,to.fp,KeyPools.REFILL,System.currentTimeMillis())));
        return SealedProtocol.seal(from.group,from.id,from.pools.backup(from.id,entropy,System.currentTimeMillis()),list,new SealedProtocol.Content(Inbox.TEXT_NAME,Inbox.TEXT_MIME,text.getBytes(StandardCharsets.UTF_8),0,false),JoinProtocol.carrier(from.group),entropy);
    }
    private SealedProtocol.Opened receive(Device to,byte[] packet)throws Exception{
        SealedProtocol.Opened o=SealedProtocol.open(to.group,to.id,to.pools,packet);
        JoinProtocol.Roster r=JoinProtocol.openCarrier(to.group,o.carrier);if(r!=null)JoinProtocol.adopt(to.group,r,to.id,to.pools);
        SealedProtocol.accept(to.group,to.pools,o);return o;
    }

    @Test public void ownerAndJoinerHaveFullProtectionBothWays()throws Exception{
        join(b,"Sam");
        assertEquals(KeyPools.POOL,b.pools.available(a.fp));assertEquals(KeyPools.POOL,a.pools.available(b.fp));
        assertEquals(a.group.roster,b.group.roster);assertEquals("Sam",b.group.memberNames.get(b.fp));assertEquals("Alex",b.group.memberNames.get(a.fp));
        assertFalse(receive(b,send(a,"Welcome",b)).reducedProtection());
        assertFalse(receive(a,send(b,"Thanks",a)).reducedProtection());
    }
    @Test public void laterMembersMeetThroughOwnerIntroductions()throws Exception{
        join(b,"Sam");join(c,"Jordan");
        assertTrue(c.group.members.containsKey(b.fp));assertFalse(b.group.members.containsKey(c.fp));
        // B learns about C from the roster carried in any message from the owner.
        receive(b,send(a,"Jordan joined",b));
        assertTrue(b.group.members.containsKey(c.fp));assertEquals(1,b.pools.available(c.fp));assertEquals(1,c.pools.available(b.fp));
        SealedProtocol.Opened first=receive(c,send(b,"Hi Jordan",c));
        assertFalse("first message between later members is fully protected",first.reducedProtection());
        assertFalse(receive(b,send(c,"Hi Sam",b)).reducedProtection());
        assertEquals(KeyPools.RESERVE-1,a.pools.heldReserve(b.fp));assertEquals(KeyPools.RESERVE-1,a.pools.heldReserve(c.fp));
    }
    @Test public void reapplyingTheSameRosterDoesNotReuseIntroductions()throws Exception{
        join(b,"Sam");join(c,"Jordan");receive(b,send(a,"one",b));
        JoinProtocol.Roster same=JoinProtocol.view(b.group,Protocol.un64(a.group.roster));
        assertFalse(JoinProtocol.adopt(b.group,same,b.id,b.pools));assertEquals(1,b.pools.available(c.fp));
    }
    @Test public void invitationOnlyOpensForItsRequest()throws Exception{
        String request=JoinProtocol.request(b.id,b.pools,entropy,"Sam"),other=JoinProtocol.request(c.id,c.pools,entropy,"Jordan");
        String invitation=JoinProtocol.invite(a.group,JoinProtocol.parseRequest(request),a.id,a.pools,entropy);
        assertThrows(SecurityException.class,()->JoinProtocol.accept(invitation,other,c.id,c.pools));
        assertThrows(SecurityException.class,()->JoinProtocol.accept(invitation,request,c.id,c.pools));
        byte[] raw=Protocol.un64(invitation.substring(5));raw[raw.length/2]^=1;String tampered=JoinProtocol.INVITATION+Protocol.b64(raw);
        assertThrows(Exception.class,()->JoinProtocol.accept(tampered,request,b.id,b.pools));
        assertNotNull(JoinProtocol.accept(invitation,request,b.id,b.pools));
    }
    @Test public void onlyTheOwnerInvitesAndSignsRosters()throws Exception{
        join(b,"Sam");JoinProtocol.Request req=JoinProtocol.parseRequest(JoinProtocol.request(c.id,c.pools,entropy,"Jordan"));
        assertThrows(SecurityException.class,()->JoinProtocol.invite(b.group,req,b.id,b.pools,entropy));
        assertThrows(SecurityException.class,()->JoinProtocol.signRoster(b.group,b.id,b.pools,entropy,Collections.emptyMap()));
    }
    @Test public void forgedRequestAndReceiptAreRejected()throws Exception{
        String request=JoinProtocol.request(b.id,b.pools,entropy,"Sam");byte[] raw=Protocol.un64(request.substring(5));raw[20]^=1;
        assertThrows(Exception.class,()->JoinProtocol.parseRequest(JoinProtocol.REQUEST+Protocol.b64(raw)));
        JoinProtocol.Request parsed=JoinProtocol.parseRequest(request);String invitation=JoinProtocol.invite(a.group,parsed,a.id,a.pools,entropy);b.group=JoinProtocol.accept(invitation,request,b.id,b.pools);
        String receipt=JoinProtocol.receipt(b.group,parsed.id,b.id,b.pools,entropy);
        assertThrows(SecurityException.class,()->JoinProtocol.receiptMember(a.group,receipt,pending,a.pools));
        pending.put(parsed.id,b.fp);String forged=JoinProtocol.receipt(b.group,parsed.id,c.id,c.pools,entropy);
        assertThrows(SecurityException.class,()->JoinProtocol.receiptMember(a.group,forged,pending,a.pools));
        assertEquals(b.fp,JoinProtocol.receiptMember(a.group,receipt,pending,a.pools));assertEquals("Joined",a.group.memberStatus.get(b.fp));
    }
    @Test public void olderRosterIsIgnoredAndBlockedMembersStayOut()throws Exception{
        join(b,"Sam");String old=a.group.roster;join(c,"Jordan");receive(b,send(a,"update",b));long revision=b.group.rosterRevision;
        assertFalse(JoinProtocol.adopt(b.group,JoinProtocol.view(b.group,Protocol.un64(old)),b.id,b.pools));assertEquals(revision,b.group.rosterRevision);
        Device d=new Device(entropy);b.group.blocked.add(c.fp);join(d,"Riley");receive(b,send(a,"another",b));
        assertFalse(b.group.members.containsKey(c.fp));assertTrue(b.group.members.containsKey(d.fp));
    }
    @Test public void groupCreationNeedsExtraRandomness(){
        Entropy bare=new Entropy();assertThrows(IllegalArgumentException.class,()->JoinProtocol.create("Family","Alex",a.id,a.pools,bare));
        bare.addCamera(new byte[32]);
    }
    @Test public void keyPoolsKeepHeldReserveAcrossStorage()throws Exception{
        join(b,"Sam");KeyPools restored=KeyPools.decode(a.pools.encode());assertEquals(KeyPools.RESERVE,restored.heldReserve(b.fp));
    }
}
