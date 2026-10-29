package app.quietkey;

import java.io.*;
import java.util.*;

/**
 * Per-device key state for 0.7.0 forward secrecy (SYSTEM_DESIGN decision 8):
 * - own one-time keys, issued per contact (or as owner reserve) and deleted after one use;
 * - own backup keys, replaced every 30 days; old private halves kept 365 days, because a roster
 *   can hand out a member's backup key long after it was replaced;
 * - contacts' signed public one-time keys and backup keys, used when sending to them.
 * Every key is an X25519 + ML-KEM-768 pair (decision 11).
 * Pure Java; stored inside the passphrase vault.
 */
public final class KeyPools {
    public static final int POOL=100,RESERVE=20,REFILL=10,MAX_THEIRS=2*POOL;
    public static final long DAY=24L*60*60*1000,BACKUP_LIFE=30*DAY,BACKUP_GRACE=365*DAY;
    static final String RESERVE_CONTACT="";
    /** Own private halves: X25519 private key (32 bytes) followed by the ML-KEM-768 seed (64 bytes). */
    static final int PRIVATE=32+PostQuantum.KEM_SEED;

    private static final class Owned {
        final PreKey key;final byte[] privateKey;final String contact;
        Owned(PreKey key,byte[] privateKey,String contact){this.key=key;this.privateKey=privateKey;this.contact=contact;}
    }
    private final LinkedHashMap<String,Owned> ownOneTime=new LinkedHashMap<>();
    private final List<Owned> ownBackups=new ArrayList<>();
    private final HashMap<String,ArrayDeque<PreKey>> theirOneTime=new HashMap<>();
    private final HashMap<String,PreKey> theirBackup=new HashMap<>();
    /** Owner only: members' reserve keys, each handed to exactly one later member. */
    private final HashMap<String,ArrayDeque<PreKey>> heldReserve=new HashMap<>();

    // ---- own keys ----
    /** The current signed backup key, creating or replacing it when missing or older than 30 days. */
    public PreKey backup(Identity me,Entropy entropy,long now){
        Owned current=ownBackups.isEmpty()?null:ownBackups.get(ownBackups.size()-1);
        if(current==null||now-current.key.created>=BACKUP_LIFE){current=create(me,entropy,PreKey.BACKUP,now,null);ownBackups.add(current);}
        for(Iterator<Owned> i=ownBackups.iterator();i.hasNext();){Owned o=i.next();if(o!=current&&now-o.key.created>=BACKUP_LIFE+BACKUP_GRACE){Arrays.fill(o.privateKey,(byte)0);i.remove();}}
        return current.key;
    }
    /**
     * Refill keys to send to a contact with a message: new keys while fewer than {@link #POOL} are
     * outstanding, otherwise the oldest outstanding ones again (the contact ignores duplicates), so
     * keys lost with an undelivered message are eventually re-sent.
     */
    public List<PreKey> issue(Identity me,Entropy entropy,String contact,int max,long now){
        if(contact==null||contact.isEmpty())throw new IllegalArgumentException("Missing contact");
        return issueFor(me,entropy,contact,max,POOL,now);
    }
    /** Public keys the owner holds for future members (decision 8: 20 per member). */
    public List<PreKey> issueReserve(Identity me,Entropy entropy,long now){return issueFor(me,entropy,RESERVE_CONTACT,RESERVE,RESERVE,now);}
    private List<PreKey> issueFor(Identity me,Entropy entropy,String contact,int max,int limit,long now){
        List<PreKey> out=new ArrayList<>();int outstanding=0;
        for(Owned o:ownOneTime.values())if(o.contact.equals(contact)){outstanding++;}
        int fresh=Math.max(0,Math.min(max,limit-outstanding));
        for(int i=0;i<fresh;i++){Owned o=create(me,entropy,PreKey.ONE_TIME,now,contact);ownOneTime.put(o.key.idText(),o);out.add(o.key);}
        if(out.isEmpty())for(Owned o:ownOneTime.values()){if(out.size()>=max)break;if(o.contact.equals(contact))out.add(o.key);}
        return out;
    }
    public int outstanding(String contact){int n=0;for(Owned o:ownOneTime.values())if(o.contact.equals(contact))n++;return n;}
    byte[] oneTimePrivate(byte[] id){Owned o=ownOneTime.get(Protocol.b64(id));return o==null?null:o.privateKey;}
    byte[] backupPrivate(byte[] id){for(Owned o:ownBackups)if(Arrays.equals(o.key.id,id))return o.privateKey;return null;}
    boolean ownsBackup(byte[] id){return backupPrivate(id)!=null;}
    /** Deletes a used one-time private key: from now on that message can never be decrypted again. */
    public void consume(byte[] id){Owned o=ownOneTime.remove(Protocol.b64(id));if(o!=null)Arrays.fill(o.privateKey,(byte)0);}

    // ---- contacts' keys ----
    /** Verified, de-duplicated one-time keys from a contact; the oldest are dropped past {@link #MAX_THEIRS}. */
    public int addTheirs(String contact,Identity.Public owner,List<PreKey> keys){
        requireOwner(contact,owner);ArrayDeque<PreKey> q=theirOneTime.computeIfAbsent(contact,k->new ArrayDeque<>());int added=0;
        for(PreKey k:keys){if(!k.verify(owner,PreKey.ONE_TIME))throw new SecurityException("A one-time key was not signed by its owner");if(!q.contains(k)){q.addLast(k);added++;}}
        while(q.size()>MAX_THEIRS)q.removeFirst();return added;
    }
    /** Keeps the newest verified backup key from a contact. */
    public void setTheirBackup(String contact,Identity.Public owner,PreKey key){
        requireOwner(contact,owner);if(!key.verify(owner,PreKey.BACKUP))throw new SecurityException("The backup key was not signed by its owner");
        PreKey old=theirBackup.get(contact);if(old==null||key.created>old.created)theirBackup.put(contact,key);
    }
    public PreKey theirBackup(String contact){return theirBackup.get(contact);}
    /** Takes one of a contact's one-time keys for a message, or null when none are left (backup-key fallback). */
    public PreKey take(String contact){ArrayDeque<PreKey> q=theirOneTime.get(contact);return q==null?null:q.pollFirst();}
    public int available(String contact){ArrayDeque<PreKey> q=theirOneTime.get(contact);return q==null?0:q.size();}
    /** Owner side: keeps a member's verified reserve keys (public halves only) for later members. */
    public void holdReserve(String member,Identity.Public owner,List<PreKey> keys){
        requireOwner(member,owner);if(keys.size()>RESERVE)throw new SecurityException("Too many reserve keys");ArrayDeque<PreKey> q=heldReserve.computeIfAbsent(member,k->new ArrayDeque<>());
        for(PreKey k:keys){if(!k.verify(owner,PreKey.ONE_TIME))throw new SecurityException("A reserve key was not signed by its owner");if(!q.contains(k))q.addLast(k);}
        while(q.size()>RESERVE)q.removeFirst();
    }
    /** Removes and returns one held reserve key of a member, or null when none are left. */
    public PreKey takeReserve(String member){ArrayDeque<PreKey> q=heldReserve.get(member);return q==null?null:q.pollFirst();}
    public int heldReserve(String member){ArrayDeque<PreKey> q=heldReserve.get(member);return q==null?0:q.size();}
    /** A removed member: drop their keys and delete every own key issued to them. */
    public void forget(String contact){
        theirOneTime.remove(contact);theirBackup.remove(contact);heldReserve.remove(contact);
        for(Iterator<Owned> i=ownOneTime.values().iterator();i.hasNext();){Owned o=i.next();if(o.contact.equals(contact)){Arrays.fill(o.privateKey,(byte)0);i.remove();}}
    }
    /**
     * Keys are kept per person, not per group, so a contact is forgotten only when they are no
     * longer a trusted member of any group on this device (removed, blocked, or their last group
     * deleted). Own owner-reserve keys are kept. Returns how many contacts were forgotten.
     */
    public int forgetAllExcept(Set<String> keep){
        Set<String> contacts=new HashSet<>(theirOneTime.keySet());contacts.addAll(theirBackup.keySet());contacts.addAll(heldReserve.keySet());
        for(Owned o:ownOneTime.values())if(!o.contact.equals(RESERVE_CONTACT))contacts.add(o.contact);
        int n=0;for(String c:contacts)if(!keep.contains(c)){forget(c);n++;}return n;
    }
    /** Every trusted member of every group except this device: the contacts whose keys are kept. */
    public static Set<String> activeContacts(Collection<Protocol.Group> groups,String self){
        Set<String> out=new HashSet<>();for(Protocol.Group g:groups)for(String m:g.members.keySet())if(!m.equals(self)&&!g.blocked.contains(m))out.add(m);return out;
    }
    public void destroy(){for(Owned o:ownOneTime.values())Arrays.fill(o.privateKey,(byte)0);for(Owned o:ownBackups)Arrays.fill(o.privateKey,(byte)0);ownOneTime.clear();ownBackups.clear();theirOneTime.clear();theirBackup.clear();heldReserve.clear();}

    private static Owned create(Identity me,Entropy entropy,int kind,long now,String contact){
        String purpose=kind==PreKey.BACKUP?"backup-key":"one-time-key";
        byte[] x=entropy.next32(purpose),kem=PostQuantum.kemSeed(entropy,purpose),id=Arrays.copyOf(entropy.next32("key-id"),8),privateKey=new byte[PRIVATE];
        System.arraycopy(x,0,privateKey,0,32);System.arraycopy(kem,0,privateKey,32,PostQuantum.KEM_SEED);
        try{return new Owned(PreKey.sign(me,kind,id,Identity.x25519Public(x),PostQuantum.kemPublic(kem),now),privateKey,contact==null?RESERVE_CONTACT:contact);}
        finally{Arrays.fill(x,(byte)0);Arrays.fill(kem,(byte)0);}
    }
    private static void requireOwner(String contact,Identity.Public owner){if(!owner.fingerprint().equals(contact))throw new SecurityException("Key owner does not match contact");}

    // ---- vault storage ----
    public byte[] encode(){
        try{return Packets.pack(o->{
            o.writeUTF("Enigma/key-pools/v2");
            o.writeInt(ownOneTime.size());for(Owned x:ownOneTime.values()){x.key.write(o);o.write(x.privateKey);o.writeUTF(x.contact);}
            o.writeInt(ownBackups.size());for(Owned x:ownBackups){x.key.write(o);o.write(x.privateKey);}
            o.writeInt(theirOneTime.size());for(Map.Entry<String,ArrayDeque<PreKey>> e:theirOneTime.entrySet()){o.writeUTF(e.getKey());o.writeInt(e.getValue().size());for(PreKey k:e.getValue())k.write(o);}
            o.writeInt(theirBackup.size());for(Map.Entry<String,PreKey> e:theirBackup.entrySet()){o.writeUTF(e.getKey());e.getValue().write(o);}
            o.writeInt(heldReserve.size());for(Map.Entry<String,ArrayDeque<PreKey>> e:heldReserve.entrySet()){o.writeUTF(e.getKey());o.writeInt(e.getValue().size());for(PreKey k:e.getValue())k.write(o);}
        });}catch(Exception e){throw new IllegalStateException(e);}
    }
    public static KeyPools decode(byte[] bytes)throws IOException{
        DataInputStream in=Packets.input(bytes);if(!"Enigma/key-pools/v2".equals(in.readUTF()))throw new IOException("Unknown key pool format");KeyPools p=new KeyPools();
        int n=count(in,100000);for(int i=0;i<n;i++){PreKey k=PreKey.read(in);byte[] pk=new byte[PRIVATE];in.readFully(pk);p.ownOneTime.put(k.idText(),new Owned(k,pk,in.readUTF()));}
        n=count(in,1000);for(int i=0;i<n;i++){PreKey k=PreKey.read(in);byte[] pk=new byte[PRIVATE];in.readFully(pk);p.ownBackups.add(new Owned(k,pk,RESERVE_CONTACT));}
        n=count(in,100000);for(int i=0;i<n;i++){String c=in.readUTF();int m=count(in,MAX_THEIRS);ArrayDeque<PreKey> q=new ArrayDeque<>();for(int j=0;j<m;j++)q.addLast(PreKey.read(in));p.theirOneTime.put(c,q);}
        n=count(in,100000);for(int i=0;i<n;i++){String c=in.readUTF();p.theirBackup.put(c,PreKey.read(in));}
        n=count(in,100000);for(int i=0;i<n;i++){String c=in.readUTF();int m=count(in,RESERVE);ArrayDeque<PreKey> q=new ArrayDeque<>();for(int j=0;j<m;j++)q.addLast(PreKey.read(in));p.heldReserve.put(c,q);}
        Packets.end(in);return p;
    }
    private static int count(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max)throw new IOException("Invalid key pool size");return n;}
}
