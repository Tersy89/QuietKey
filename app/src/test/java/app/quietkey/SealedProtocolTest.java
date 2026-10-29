package app.quietkey;

import org.junit.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** 0.7.0 ENG5 packages: X3DH one-time keys, multiple recipients, group binding and key pools. */
public final class SealedProtocolTest {
    private static final class Member {
        final Identity id;final KeyPools pools=new KeyPools();final String fp;
        Member(Entropy e){id=Identity.generate(e);fp=id.fingerprint();}
    }
    private Entropy entropy;private Protocol.Group group;private Member a,b,c,d;private long now;

    @Before public void setup(){
        entropy=new Entropy();entropy.addCamera(new byte[32]);entropy.addMotion(1,123L,new float[]{0.1f,9.8f,0.2f});now=System.currentTimeMillis();
        a=new Member(entropy);b=new Member(entropy);c=new Member(entropy);d=new Member(entropy);
        group=new Protocol.Group(UUID.randomUUID().toString(),"Family",Protocol.random(32),now+KeyPools.DAY*3650,a.fp);
        for(Member m:new Member[]{a,b,c,d})group.members.put(m.fp,Protocol.b64(m.id.publicKey.encode()));
        for(Member x:new Member[]{a,b,c,d})for(Member y:new Member[]{a,b,c,d})if(x!=y)exchange(x,y);
    }
    /** What joining will hand over: x's signed backup key and a pool of x's one-time keys for y. */
    private void exchange(Member x,Member y){
        y.pools.setTheirBackup(x.fp,x.id.publicKey,x.pools.backup(x.id,entropy,now));
        y.pools.addTheirs(x.fp,x.id.publicKey,x.pools.issue(x.id,entropy,y.fp,KeyPools.POOL,now));
    }
    private byte[] send(Member from,String text,Member... to)throws Exception{
        List<SealedProtocol.Recipient> list=new ArrayList<>();
        for(Member m:to)list.add(new SealedProtocol.Recipient(m.id.publicKey,from.pools.theirBackup(m.fp),from.pools.take(m.fp),from.pools.issue(from.id,entropy,m.fp,KeyPools.REFILL,now)));
        return SealedProtocol.seal(group,from.id,from.pools.backup(from.id,entropy,now),list,new SealedProtocol.Content(Inbox.TEXT_NAME,Inbox.TEXT_MIME,text.getBytes(StandardCharsets.UTF_8),0,false),new byte[0],entropy);
    }
    private SealedProtocol.Opened receive(Member m,byte[] packet)throws Exception{
        SealedProtocol.Opened o=SealedProtocol.open(group,m.id,m.pools,packet);SealedProtocol.accept(group,m.pools,o);return o;
    }

    private byte[] sendWith(Member from,Member to,PreKey oneTime)throws Exception{
        return SealedProtocol.seal(group,from.id,from.pools.backup(from.id,entropy,now),Collections.singletonList(new SealedProtocol.Recipient(to.id.publicKey,from.pools.theirBackup(to.fp),oneTime,null)),
            new SealedProtocol.Content(Inbox.TEXT_NAME,Inbox.TEXT_MIME,"x".getBytes(StandardCharsets.UTF_8),0,false),new byte[0],entropy);
    }
    /** Decision 11: the message key needs both the X25519 and the ML-KEM private halves of the one-time key. */
    @Test public void readingNeedsBothX25519AndMlKem()throws Exception{
        PreKey k1=a.pools.take(b.fp),k2=a.pools.take(b.fp),k3=a.pools.take(b.fp);
        b.pools.oneTimePrivate(k1.id)[5]^=1;   // X25519 half damaged, ML-KEM intact
        assertThrows(Exception.class,()->SealedProtocol.open(group,b.id,b.pools,sendWith(a,b,k1)));
        b.pools.oneTimePrivate(k2.id)[40]^=1;  // ML-KEM half damaged, X25519 intact
        assertThrows(Exception.class,()->SealedProtocol.open(group,b.id,b.pools,sendWith(a,b,k2)));
        assertEquals("x",new String(SealedProtocol.open(group,b.id,b.pools,sendWith(a,b,k3)).body,StandardCharsets.UTF_8));
        PreKey backup=a.pools.theirBackup(b.fp);b.pools.backupPrivate(backup.id)[40]^=1; // reduced protection also uses ML-KEM
        assertThrows(Exception.class,()->SealedProtocol.open(group,b.id,b.pools,sendWith(a,b,null)));
    }
    /** Decision 11: a signature is valid only when the Ed25519 half and the ML-DSA half both verify. */
    @Test public void signaturesNeedBothEd25519AndMlDsa(){
        byte[] text="roster".getBytes(StandardCharsets.UTF_8),sig=a.id.sign(text);
        assertEquals(Identity.SIGNATURE,sig.length);assertTrue(a.id.publicKey.verify(text,sig));
        byte[] classic=sig.clone();classic[3]^=1;assertFalse(a.id.publicKey.verify(text,classic));
        byte[] pq=sig.clone();pq[200]^=1;assertFalse(a.id.publicKey.verify(text,pq));
        byte[] mixed=b.id.sign(text);System.arraycopy(sig,0,mixed,0,64);assertFalse(a.id.publicKey.verify(text,mixed));
        assertFalse(a.id.publicKey.verify(text,Arrays.copyOf(sig,64)));
        assertEquals(Identity.PUBLIC,Identity.Public.decode(a.id.publicKey.encode()).encode().length);
    }
    @Test public void recipientReadsOnceThenTheKeyIsGone()throws Exception{
        byte[] packet=send(a,"Gate code 4471",b);
        SealedProtocol.Opened o=receive(b,packet);
        assertEquals("Gate code 4471",new String(o.body,StandardCharsets.UTF_8));assertEquals(a.fp,o.sender);assertFalse(o.reducedProtection());
        assertEquals(Collections.singletonList(b.fp),o.recipients);
        SecurityException again=assertThrows(SecurityException.class,()->SealedProtocol.open(group,b.id,b.pools,packet));
        assertTrue(again.getMessage().contains("already opened"));
    }
    @Test public void multipleRecipientsEachReadAndOthersCannot()throws Exception{
        byte[] packet=send(a,"Family dinner at 6",b,c);
        assertEquals(Arrays.asList(b.fp,c.fp),receive(b,packet).recipients);
        assertEquals("Family dinner at 6",new String(receive(c,packet).body,StandardCharsets.UTF_8));
        SecurityException notMine=assertThrows(SecurityException.class,()->SealedProtocol.open(group,d.id,d.pools,packet));
        assertTrue(notMine.getMessage().contains("not addressed"));
    }
    @Test public void groupSecretIsRequired()throws Exception{
        byte[] packet=send(a,"Secret",b);
        Protocol.Group other=new Protocol.Group(group.id,group.name,Protocol.random(32),group.expires,group.owner);other.members.putAll(group.members);
        assertThrows(Exception.class,()->SealedProtocol.open(other,b.id,b.pools,packet));
        assertEquals("Secret",new String(receive(b,packet).body,StandardCharsets.UTF_8));
    }
    @Test public void emptyPoolFallsBackToBackupKeyWithReducedProtection()throws Exception{
        while(a.pools.take(b.fp)!=null){}
        byte[] packet=send(a,"Still works",b);
        SealedProtocol.Opened o=receive(b,packet);assertTrue(o.reducedProtection());assertEquals("Still works",new String(o.body,StandardCharsets.UTF_8));
    }
    @Test public void replyUsesRefilledKeysAndConsumesThem()throws Exception{
        while(b.pools.take(a.fp)!=null){}
        receive(b,send(a,"Hi",b));
        assertEquals(KeyPools.REFILL,b.pools.available(a.fp));
        int before=a.pools.outstanding(b.fp);
        SealedProtocol.Opened reply=receive(a,send(b,"Hi back",a));
        assertFalse(reply.reducedProtection());assertEquals(before-1,a.pools.outstanding(b.fp));
    }
    @Test public void tamperingFails()throws Exception{
        byte[] packet=send(a,"Untouched",b);
        for(int at:new int[]{10,packet.length/2,packet.length-1}){byte[] bad=packet.clone();bad[at]^=1;assertThrows(Exception.class,()->SealedProtocol.open(group,b.id,b.pools,bad));}
        assertEquals("Untouched",new String(receive(b,packet).body,StandardCharsets.UTF_8));
    }
    @Test public void invalidRecipientsAreRefused()throws Exception{
        Member outsider=new Member(entropy);
        SealedProtocol.Content text=new SealedProtocol.Content("m","text/plain",new byte[1],0,false);PreKey backup=a.pools.backup(a.id,entropy,now);
        assertThrows(SecurityException.class,()->SealedProtocol.seal(group,a.id,backup,Collections.emptyList(),text,null,entropy));
        assertThrows(SecurityException.class,()->SealedProtocol.seal(group,a.id,backup,Collections.singletonList(new SealedProtocol.Recipient(a.id.publicKey,backup,null,null)),text,null,entropy));
        PreKey ob=outsider.pools.backup(outsider.id,entropy,now);
        assertThrows(SecurityException.class,()->SealedProtocol.seal(group,a.id,backup,Collections.singletonList(new SealedProtocol.Recipient(outsider.id.publicKey,ob,null,null)),text,null,entropy));
        SealedProtocol.Recipient r=new SealedProtocol.Recipient(b.id.publicKey,a.pools.theirBackup(b.fp),null,null);
        assertThrows(SecurityException.class,()->SealedProtocol.seal(group,a.id,backup,Arrays.asList(r,r),text,null,entropy));
    }
    @Test public void keysSignedBySomeoneElseAreRejected(){
        PreKey cBackup=c.pools.backup(c.id,entropy,now);
        assertThrows(SecurityException.class,()->a.pools.setTheirBackup(b.fp,b.id.publicKey,cBackup));
        assertThrows(SecurityException.class,()->a.pools.addTheirs(b.fp,b.id.publicKey,c.pools.issue(c.id,entropy,b.fp,1,now)));
        SealedProtocol.Content text=new SealedProtocol.Content("m","text/plain",new byte[1],0,false);
        assertThrows(SecurityException.class,()->SealedProtocol.seal(group,a.id,a.pools.backup(a.id,entropy,now),Collections.singletonList(new SealedProtocol.Recipient(b.id.publicKey,cBackup,null,null)),text,null,entropy));
    }
    @Test public void blockedSenderIsRejected()throws Exception{
        byte[] packet=send(c,"From C",b);group.blocked.add(c.fp);
        assertThrows(SecurityException.class,()->SealedProtocol.open(group,b.id,b.pools,packet));
    }
    @Test public void expiredMessageCannotOpen()throws Exception{
        List<SealedProtocol.Recipient> to=Collections.singletonList(new SealedProtocol.Recipient(b.id.publicKey,a.pools.theirBackup(b.fp),a.pools.take(b.fp),null));
        byte[] packet=SealedProtocol.seal(group,a.id,a.pools.backup(a.id,entropy,now),to,new SealedProtocol.Content("m","text/plain",new byte[1],1,true),null,entropy);
        Thread.sleep(10);SecurityException e=assertThrows(SecurityException.class,()->SealedProtocol.open(group,b.id,b.pools,packet));assertTrue(e.getMessage().contains("expired"));
    }
    @Test public void viewOnceAndRoutingAreCarried()throws Exception{
        List<SealedProtocol.Recipient> to=Collections.singletonList(new SealedProtocol.Recipient(b.id.publicKey,a.pools.theirBackup(b.fp),a.pools.take(b.fp),null));
        byte[] packet=SealedProtocol.seal(group,a.id,a.pools.backup(a.id,entropy,now),to,new SealedProtocol.Content("beach.jpg","image/jpeg",new byte[]{1,2,3},0,true),new byte[]{9},entropy);
        assertEquals(group.id,SealedProtocol.groupOf(packet));
        SealedProtocol.Opened o=receive(b,packet);assertTrue(o.viewOnce);assertEquals("beach.jpg",o.name);assertEquals("image/jpeg",o.mime);assertArrayEquals(new byte[]{9},o.carrier);
    }
    @Test public void keyPoolsSurviveVaultStorage()throws Exception{
        byte[] packet=send(a,"After restart",b);
        KeyPools restored=KeyPools.decode(b.pools.encode());
        assertEquals("After restart",new String(SealedProtocol.open(group,b.id,restored,packet).body,StandardCharsets.UTF_8));
        Identity again=Identity.restore(b.id.encodePrivate());assertEquals(b.fp,again.fingerprint());
    }
    @Test public void backupKeyRotatesAndOldOneExpiresAfterGrace()throws Exception{
        while(a.pools.take(b.fp)!=null){}
        byte[] old=send(a,"Old backup",b);
        PreKey first=b.pools.backup(b.id,entropy,now),second=b.pools.backup(b.id,entropy,now+KeyPools.BACKUP_LIFE);
        assertFalse(Arrays.equals(first.id,second.id));
        assertEquals("Old backup",new String(SealedProtocol.open(group,b.id,b.pools,old).body,StandardCharsets.UTF_8));
        b.pools.backup(b.id,entropy,now+KeyPools.BACKUP_LIFE+KeyPools.BACKUP_GRACE);
        assertThrows(SecurityException.class,()->SealedProtocol.open(group,b.id,b.pools,old));
    }
    @Test public void fullPoolResendsOutstandingKeysInsteadOfGrowing(){
        assertEquals(KeyPools.POOL,a.pools.outstanding(b.fp));
        List<PreKey> again=a.pools.issue(a.id,entropy,b.fp,KeyPools.REFILL,now);
        assertEquals(KeyPools.REFILL,again.size());assertEquals(KeyPools.POOL,a.pools.outstanding(b.fp));
        assertEquals(0,b.pools.addTheirs(a.fp,a.id.publicKey,again));
        assertEquals(KeyPools.RESERVE,a.pools.issueReserve(a.id,entropy,now).size());assertEquals(KeyPools.RESERVE,a.pools.issueReserve(a.id,entropy,now).size());
        assertEquals(KeyPools.RESERVE,a.pools.outstanding(KeyPools.RESERVE_CONTACT));
    }
    @Test public void forgettingAMemberDeletesTheirKeys(){
        a.pools.forget(b.fp);assertEquals(0,a.pools.outstanding(b.fp));assertEquals(0,a.pools.available(b.fp));assertNull(a.pools.theirBackup(b.fp));
    }
    @Test public void entropyMixesSourcesAndNeverRepeats(){
        Entropy e=new Entropy();assertFalse(e.hasCamera());byte[] x=e.next32("t");e.addMotion(4,1L,new float[]{0f,0f,0f});e.addCamera(new byte[32]);
        assertTrue(e.hasCamera());assertEquals(1,e.motionSamples());assertFalse(Arrays.equals(x,e.next32("t")));
        assertThrows(IllegalArgumentException.class,()->e.addCamera(new byte[3]));
    }
}
