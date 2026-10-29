package app.quietkey;
import org.junit.*;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;

public final class JoiningFlowTest {
    private static TestDevice a,b,c;
    private Protocol.Group group;
    private final Set<String> used=new HashSet<>();
    private final Map<String,String> pending=new LinkedHashMap<>();
    private final Map<String,JoiningFlow.Invitation> cache=new LinkedHashMap<>();
    @BeforeClass public static void keys()throws Exception{a=new TestDevice();b=new TestDevice();c=new TestDevice();}
    @Before public void setup()throws Exception{group=JoinProtocol.create("Retry group","Owner",a.id,a.pools,TestDevice.E);}
    private String request()throws Exception{return JoinProtocol.request(b.id,b.pools,TestDevice.E,"B");}
    private String issue(String request)throws Exception{return JoiningFlow.invite(group,request,a.id,a.pools,TestDevice.E,used,pending,cache);}
    @Test public void interruptedInvitationResumesWithoutAnotherAdmission()throws Exception{
        String request=request(),first=issue(request);long revision=group.rosterRevision;assertEquals(first,issue(request));assertEquals(revision,group.rosterRevision);assertEquals(2,group.members.size());assertEquals(1,used.size());
        Protocol.Group joined=JoinProtocol.accept(first,request,b.id,b.pools);assertArrayEquals(group.seed,joined.seed);
    }
    @Test public void freshRestartAfterRemovalWorksButUsedRequestStaysRejected()throws Exception{
        String request=request();issue(request);String member=b.fp;JoiningFlow.cancel(group.id,member,pending,cache);group.members.remove(member);group.memberNames.remove(member);group.blocked.add(member);JoinProtocol.signRoster(group,a.id,a.pools,TestDevice.E,Collections.emptyMap());
        assertTrue(used.contains(JoinProtocol.parseRequest(request).id));assertThrows(SecurityException.class,()->issue(request));
        String fresh=JoiningFlow.request(b.id,b.pools,TestDevice.E,"B",request,Collections.emptySet(),true);assertNotEquals(request,fresh);String packet=issue(fresh);assertFalse(group.blocked.contains(member));assertArrayEquals(group.seed,JoinProtocol.accept(packet,fresh,b.id,b.pools).seed);
    }
    @Test public void oldVersionUsedRequestRequiresExplicitFreshRestart()throws Exception{
        String old=request();used.add(JoinProtocol.parseRequest(old).id);pending.put(JoinProtocol.parseRequest(old).id,b.fp);assertThrows(SecurityException.class,()->issue(old));assertEquals(1,group.members.size());
        assertNotEquals(old,JoiningFlow.request(b.id,b.pools,TestDevice.E,"B",old,used,false));
    }
    @Test public void cacheCannotGrantSameRequestToAnotherGroup()throws Exception{
        String request=request();issue(request);Protocol.Group other=JoinProtocol.create("Other","Owner",a.id,a.pools,TestDevice.E);assertThrows(SecurityException.class,()->JoiningFlow.invite(other,request,a.id,a.pools,TestDevice.E,used,pending,cache));assertEquals(1,other.members.size());
    }
    @Test public void freshRequestCancelsOldPendingReceiptButKeepsTombstone()throws Exception{
        String old=request(),oldPacket=issue(old),fresh=request();issue(fresh);String oldId=JoinProtocol.parseRequest(old).id;assertFalse(pending.containsKey(oldId));assertFalse(cache.containsKey(oldId));assertTrue(used.contains(oldId));assertThrows(SecurityException.class,()->issue(old));
        Protocol.Group joined=JoinProtocol.accept(oldPacket,old,b.id,b.pools);assertThrows(SecurityException.class,()->JoinProtocol.receiptMember(group,JoinProtocol.receipt(joined,oldId,b.id,b.pools,TestDevice.E),pending,a.pools));
    }
    @Test public void resumeRefreshesChangedDirectoryInsteadOfSendingRemovedMemberKey()throws Exception{
        String request=request();issue(request);String member=c.fp;a.pools.setTheirBackup(member,c.id.publicKey,c.pools.backup(c.id,TestDevice.E,System.currentTimeMillis()));group.members.put(member,Protocol.b64(c.id.publicKey.encode()));JoinProtocol.signRoster(group,a.id,a.pools,TestDevice.E,Collections.emptyMap());
        Protocol.Group joined=JoinProtocol.accept(issue(request),request,b.id,b.pools);assertEquals(group.members,joined.members);
    }
    @Test public void deletingGroupCancelsOnlyItsPendingState()throws Exception{
        String request=request();issue(request);JoiningFlow.cancel("other",null,pending,cache);assertEquals(1,cache.size());JoiningFlow.cancel(group.id,null,pending,cache);assertTrue(cache.isEmpty());assertTrue(pending.isEmpty());assertEquals(1,used.size());assertThrows(SecurityException.class,()->issue(request));
    }
    @Test public void cachedInvitationCannotBeResumedByNonOwnerOrRemovedMember()throws Exception{
        String request=request();issue(request);assertThrows(SecurityException.class,()->JoiningFlow.invite(group,request,c.id,c.pools,TestDevice.E,used,pending,cache));group.members.remove(b.fp);assertThrows(SecurityException.class,()->issue(request));
    }
    @Test public void activeRequestResumesAndConsumedRequestRenews()throws Exception{
        String request=request();assertEquals(request,JoiningFlow.request(b.id,b.pools,TestDevice.E,"B",request,used,false));used.add(JoinProtocol.parseRequest(request).id);assertNotEquals(request,JoiningFlow.request(b.id,b.pools,TestDevice.E,"B",request,used,false));
    }
}
