package app.quietkey;

import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

/** Remote joining by file: passphrase-tagged QKJ4 request and QKG4 invitation, seven-day window, one use. */
public final class JoinPhraseTest {
    private static final String PHRASE="Orange tiger-harbour  nine";
    private TestDevice owner,friend,mallory;private Protocol.Group group;
    private final Set<String> used=new HashSet<>();private final Map<String,String> pending=new HashMap<>();private final Map<String,JoiningFlow.Invitation> cache=new HashMap<>();

    @Before public void setup()throws Exception{
        owner=new TestDevice();friend=new TestDevice();mallory=new TestDevice();
        group=JoinProtocol.create("Friends","Chris",owner.id,owner.pools,TestDevice.E);
    }
    private String fileRequest(TestDevice d,String name)throws Exception{return JoinProtocol.request(d.id,d.pools,TestDevice.E,name,JoinProtocol.FILE_WINDOW);}

    @Test public void fullRemoteExchangeJoinsWithMatchingPassphrase()throws Exception{
        String request=fileRequest(friend,"Sam");byte[] friendKey=JoinPhrase.key(PHRASE,request);String sent=JoinPhrase.seal(friendKey,JoinPhrase.REQUEST,request);
        // Owner: types the phrase in a different spelling, verifies, invites, tags the invitation.
        String received=JoinPhrase.inner(sent);byte[] ownerKey=JoinPhrase.key("orange tiger harbour nine",received);
        assertEquals(request,JoinPhrase.open(ownerKey,JoinPhrase.REQUEST,sent));
        String grant=JoinPhrase.seal(ownerKey,JoinPhrase.INVITATION,JoiningFlow.invite(group,received,owner.id,owner.pools,TestDevice.E,used,pending,cache));
        // Friend: opens with the stored key.
        Protocol.Group joined=JoinProtocol.accept(JoinPhrase.open(friendKey,JoinPhrase.INVITATION,grant),request,friend.id,friend.pools);
        assertEquals(group.id,joined.id);assertEquals(2,joined.members.size());assertTrue(group.members.containsKey(friend.fp));
    }
    @Test public void wrongPassphraseIsRefusedBothWays()throws Exception{
        String request=fileRequest(friend,"Sam");String sent=JoinPhrase.seal(JoinPhrase.key(PHRASE,request),JoinPhrase.REQUEST,request);
        assertThrows(SecurityException.class,()->JoinPhrase.open(JoinPhrase.key("orange tiger harbour ten",request),JoinPhrase.REQUEST,sent));
        String grant=JoinPhrase.seal(JoinPhrase.key("purple tiger harbour nine",request),JoinPhrase.INVITATION,JoiningFlow.invite(group,request,owner.id,owner.pools,TestDevice.E,used,pending,cache));
        assertThrows(SecurityException.class,()->JoinPhrase.open(JoinPhrase.key(PHRASE,request),JoinPhrase.INVITATION,grant));
    }
    @Test public void swappedRequestOrReusedTagIsRefused()throws Exception{
        String request=fileRequest(friend,"Sam");byte[] key=JoinPhrase.key(PHRASE,request);String sent=JoinPhrase.seal(key,JoinPhrase.REQUEST,request);
        String forged=sent.substring(0,sent.indexOf('.')+1)+fileRequest(mallory,"Sam");
        assertThrows(SecurityException.class,()->JoinPhrase.open(JoinPhrase.key(PHRASE,JoinPhrase.inner(forged)),JoinPhrase.REQUEST,forged));
        // A request tag cannot stand in for an invitation tag.
        String asInvitation=JoinPhrase.PREFIX+sent.substring(JoinPhrase.PREFIX.length(),sent.indexOf('.'))+"."+request;
        assertThrows(SecurityException.class,()->JoinPhrase.open(key,JoinPhrase.INVITATION,asInvitation));
    }
    @Test public void untaggedFilesAndWeakPhrasesAreRefused(){
        assertThrows(SecurityException.class,()->JoinPhrase.inner("QKJ4:abc"));
        assertThrows(IllegalArgumentException.class,()->JoinPhrase.requireStrong("tiger nine"));
        assertThrows(IllegalArgumentException.class,()->JoinPhrase.requireStrong("a b c d"));
        JoinPhrase.requireStrong(PHRASE);
    }
    @Test public void fileRequestLastsSevenDaysAndWorksOnce()throws Exception{
        String request=fileRequest(friend,"Sam");JoinProtocol.Request r=JoinProtocol.parseRequest(request);
        assertEquals(JoinProtocol.FILE_WINDOW,r.expires-r.created);
        JoiningFlow.invite(group,request,owner.id,owner.pools,TestDevice.E,used,pending,cache);
        Protocol.Group other=JoinProtocol.create("Other","Chris",owner.id,owner.pools,TestDevice.E);
        assertThrows(SecurityException.class,()->JoiningFlow.invite(other,request,owner.id,owner.pools,TestDevice.E,used,pending,cache));
        assertThrows(IllegalArgumentException.class,()->JoinProtocol.request(friend.id,friend.pools,TestDevice.E,"Sam",JoinProtocol.FILE_WINDOW+1));
    }
}
