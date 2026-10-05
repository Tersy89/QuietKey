package app.quietkey;
import org.junit.*;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;

public final class JoiningFlowTest {
    private static KeyPair a,b,c;
    private Protocol.Group group;
    private final Set<String> used=new HashSet<>();
    private final Map<String,String> pending=new LinkedHashMap<>();
    private final Map<String,JoiningFlow.Invitation> cache=new LinkedHashMap<>();
    @BeforeClass public static void keys()throws Exception{KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);a=gen.generateKeyPair();b=gen.generateKeyPair();c=gen.generateKeyPair();}
    @Before public void setup()throws Exception{group=Protocol.create("Retry group",Protocol.random(32),a.getPublic());}
    private String request()throws Exception{return PairingProtocol.request(b.getPublic(),b.getPrivate(),"B");}
    private String issue(String request)throws Exception{return JoiningFlow.invite(group,request,a.getPublic(),a.getPrivate(),used,pending,cache);}
    @Test public void interruptedInvitationResumesWithoutAnotherAdmission()throws Exception{
        String request=request(),first=issue(request);long revision=group.rosterRevision;assertEquals(first,issue(request));assertEquals(revision,group.rosterRevision);assertEquals(2,group.members.size());assertEquals(1,used.size());
        Protocol.Group joined=PairingProtocol.accept(first,request,b.getPublic(),b.getPrivate());assertArrayEquals(group.seed,joined.seed);
    }
    @Test public void freshRestartAfterRemovalWorksButUsedRequestStaysRejected()throws Exception{
        String request=request();issue(request);String member=Protocol.fingerprint(b.getPublic());JoiningFlow.cancel(group.id,member,pending,cache);group.members.remove(member);group.memberNames.remove(member);group.blocked.add(member);PairingProtocol.signRoster(group,a.getPublic(),a.getPrivate());
        assertTrue(used.contains(PairingProtocol.parseRequest(request).id));assertThrows(SecurityException.class,()->issue(request));
        String fresh=JoiningFlow.request(b.getPublic(),b.getPrivate(),"B",request,Collections.emptySet(),true);assertNotEquals(request,fresh);String packet=issue(fresh);assertFalse(group.blocked.contains(member));assertArrayEquals(group.seed,PairingProtocol.accept(packet,fresh,b.getPublic(),b.getPrivate()).seed);
    }
    @Test public void oldVersionUsedRequestRequiresExplicitFreshRestart()throws Exception{
        String old=request();used.add(PairingProtocol.parseRequest(old).id);pending.put(PairingProtocol.parseRequest(old).id,Protocol.fingerprint(b.getPublic()));assertThrows(SecurityException.class,()->issue(old));assertEquals(1,group.members.size());
        assertNotEquals(old,JoiningFlow.request(b.getPublic(),b.getPrivate(),"B",old,used,false));
    }
    @Test public void cacheCannotGrantSameRequestToAnotherGroup()throws Exception{
        String request=request();issue(request);Protocol.Group other=Protocol.create("Other",Protocol.random(32),a.getPublic());assertThrows(SecurityException.class,()->JoiningFlow.invite(other,request,a.getPublic(),a.getPrivate(),used,pending,cache));assertEquals(1,other.members.size());
    }
    @Test public void freshRequestCancelsOldPendingReceiptButKeepsTombstone()throws Exception{
        String old=request(),oldPacket=issue(old),fresh=request();issue(fresh);String oldId=PairingProtocol.parseRequest(old).id;assertFalse(pending.containsKey(oldId));assertFalse(cache.containsKey(oldId));assertTrue(used.contains(oldId));assertThrows(SecurityException.class,()->issue(old));
        Protocol.Group joined=PairingProtocol.accept(oldPacket,old,b.getPublic(),b.getPrivate());assertThrows(SecurityException.class,()->PairingProtocol.receiptMember(group,PairingProtocol.receipt(joined,oldId,b.getPublic(),b.getPrivate()),pending));
    }
    @Test public void resumeRefreshesChangedDirectoryInsteadOfSendingRemovedMemberKey()throws Exception{
        String request=request();issue(request);String member=Protocol.fingerprint(c.getPublic());group.members.put(member,Protocol.b64(c.getPublic().getEncoded()));PairingProtocol.signRoster(group,a.getPublic(),a.getPrivate());
        Protocol.Group joined=PairingProtocol.accept(issue(request),request,b.getPublic(),b.getPrivate());assertEquals(group.members,joined.members);
    }
    @Test public void deletingGroupCancelsOnlyItsPendingState()throws Exception{
        String request=request();issue(request);JoiningFlow.cancel("other",null,pending,cache);assertEquals(1,cache.size());JoiningFlow.cancel(group.id,null,pending,cache);assertTrue(cache.isEmpty());assertTrue(pending.isEmpty());assertEquals(1,used.size());assertThrows(SecurityException.class,()->issue(request));
    }
    @Test public void cachedInvitationCannotBeResumedByNonOwnerOrRemovedMember()throws Exception{
        String request=request();issue(request);assertThrows(SecurityException.class,()->JoiningFlow.invite(group,request,c.getPublic(),c.getPrivate(),used,pending,cache));group.members.remove(Protocol.fingerprint(b.getPublic()));assertThrows(SecurityException.class,()->issue(request));
    }
    @Test public void activeRequestResumesAndConsumedRequestRenews()throws Exception{
        String request=request();assertEquals(request,JoiningFlow.request(b.getPublic(),b.getPrivate(),"B",request,used,false));used.add(PairingProtocol.parseRequest(request).id);assertNotEquals(request,JoiningFlow.request(b.getPublic(),b.getPrivate(),"B",request,used,false));
    }
}
