package app.quietkey;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Cipher;
import static app.quietkey.Packets.*;

/**
 * 0.7.0 joining with Ed25519/X25519 identities (replaces PairingProtocol once the app switches over).
 * Same flow and transport as 0.6.0: request (QKJ3) → owner-signed, recipient-encrypted invitation
 * (QKG3) → signed receipt (QKA3). New: every packet also carries the key pools that give
 * forward secrecy from the first message:
 * - the request carries the joiner's signed backup key and 20 reserve keys for the owner to hold;
 * - the invitation carries the owner's one-time pool for the joiner and the signed roster;
 * - the receipt carries the joiner's one-time pool for the owner;
 * - the roster revision that admits a member carries "introductions": one reserve key of each
 *   existing member for the newcomer, and one of the newcomer's for each existing member.
 * Group members map fingerprint → base64 of {@link Identity.Public#encode()}.
 */
public final class JoinProtocol {
    private JoinProtocol(){}
    public static final String REQUEST="QKJ3:",INVITATION="QKG3:",RECEIPT="QKA3:";
    public static final int MAX_REQUEST=6000,MAX_INVITATION=60000,MAX_RECEIPT=20000,MAX_ROSTER=40000,MAX_MEMBERS=30;
    public static final long WINDOW=300000;

    public static final class Request {
        public final Identity.Public key;public final String name,id;public final long created,expires;public final PreKey backup;public final List<PreKey> reserve;
        Request(Identity.Public key,String name,String id,long created,long expires,PreKey backup,List<PreKey> reserve){this.key=key;this.name=name;this.id=id;this.created=created;this.expires=expires;this.backup=backup;this.reserve=reserve;}
    }
    /** One reserve key of the entry's member, for {@link #forMember} to use when first messaging them. */
    public static final class Intro {
        public final String forMember;public final PreKey key;
        public Intro(String forMember,PreKey key){this.forMember=forMember;this.key=key;}
    }
    /** A verified owner-signed member list: members, each member's backup key and introductions. */
    public static final class Roster {
        public final Protocol.Group group;public final long revision;public final Map<String,PreKey> backups;public final Map<String,List<Intro>> intros;final byte[] packet;
        Roster(Protocol.Group group,long revision,Map<String,PreKey> backups,Map<String,List<Intro>> intros,byte[] packet){this.group=group;this.revision=revision;this.backups=backups;this.intros=intros;this.packet=packet;}
    }

    // ---- group creation and roster ----
    public static Protocol.Group create(String name,String ownerName,Identity owner,KeyPools pools,Entropy entropy)throws Exception{
        if(!entropy.hasCamera()&&entropy.motionSamples()==0)throw new IllegalArgumentException("Capture camera frames first");
        if(ownerName.trim().isEmpty()||ownerName.length()>40)throw new IllegalArgumentException("Name this device first");
        byte[] seed=entropy.next32("group-secret");long expires=Instant.now().atZone(ZoneOffset.UTC).plusYears(10).toInstant().toEpochMilli();
        Protocol.Group g;try{g=new Protocol.Group(UUID.randomUUID().toString(),name.trim(),seed,expires,owner.fingerprint());}finally{Arrays.fill(seed,(byte)0);}
        g.members.put(owner.fingerprint(),Protocol.b64(owner.publicKey.encode()));g.memberNames.put(owner.fingerprint(),ownerName.trim());
        signRoster(g,owner,pools,entropy,Collections.emptyMap());return g;
    }
    /** Owner: signs the next roster revision. Every member's backup key must be known; intros apply to this revision only. */
    public static void signRoster(Protocol.Group g,Identity owner,KeyPools pools,Entropy entropy,Map<String,List<Intro>> intros)throws Exception{
        String self=owner.fingerprint();if(!g.owner.equals(self))throw new SecurityException("Only the owner can approve members");
        if(g.members.size()>MAX_MEMBERS)throw new IllegalArgumentException("Maximum 30 devices per group");
        long revision=Math.addExact(g.rosterRevision,1);PreKey ownBackup=pools.backup(owner,entropy,System.currentTimeMillis());
        byte[] body=pack(o->{
            o.writeUTF("roster-v3");o.writeUTF(g.id);blob(o,owner.publicKey.encode());o.writeLong(g.expires);o.writeLong(revision);o.writeUTF(g.name);o.writeInt(g.members.size());
            for(Map.Entry<String,String> e:g.members.entrySet()){
                Identity.Public m=Identity.Public.decode(Protocol.un64(e.getValue()));if(!m.fingerprint().equals(e.getKey()))throw new SecurityException("Invalid member key");
                String alias=g.memberNames.getOrDefault(e.getKey(),"Member");if(alias.length()>40)throw new SecurityException("Member name too long");
                PreKey backup=e.getKey().equals(self)?ownBackup:pools.theirBackup(e.getKey());if(backup==null)throw new SecurityException("Missing backup key for "+alias);
                List<Intro> list=intros.getOrDefault(e.getKey(),Collections.emptyList());
                blob(o,m.encode());o.writeUTF(alias);backup.write(o);o.writeInt(list.size());for(Intro i:list){o.writeUTF(i.forMember);i.key.write(o);}
            }
        });
        byte[] packet=signed(body,owner);if(packet.length>MAX_ROSTER)throw new SecurityException("Directory too large");
        Protocol.Group check=new Protocol.Group(g.id,g.name,g.seed,g.expires,g.owner);check.members.put(self,g.members.get(self));
        view(check,packet); // verify our own signature before replacing persisted trust
        g.roster=Protocol.b64(packet);g.rosterRevision=revision;
    }
    /** Verifies a roster against the owner pinned in {@code base}; does not change anything. */
    public static Roster view(Protocol.Group base,byte[] packet)throws Exception{
        Identity.Public owner=Identity.Public.decode(Protocol.un64(base.members.get(base.owner)));byte[] body=verified(packet,owner,MAX_ROSTER);
        DataInputStream in=input(body);if(!in.readUTF().equals("roster-v3")||!in.readUTF().equals(base.id))throw new SecurityException("Directory belongs to another group");
        Identity.Public claimed=Identity.Public.decode(blob(in,64));long expiry=in.readLong(),revision=in.readLong();String name=in.readUTF();int count=in.readInt();
        if(!claimed.equals(owner)||expiry!=base.expires||revision<=0||count<1||count>MAX_MEMBERS||name.trim().isEmpty()||name.length()>60)throw new SecurityException("Invalid member directory");
        Protocol.Group candidate=new Protocol.Group(base.id,name,base.seed,base.expires,base.owner);Map<String,PreKey> backups=new LinkedHashMap<>();Map<String,List<Intro>> intros=new LinkedHashMap<>();
        for(int i=0;i<count;i++){
            Identity.Public m=Identity.Public.decode(blob(in,64));String id=m.fingerprint(),alias=in.readUTF();
            if(alias.length()>40||candidate.members.put(id,Protocol.b64(m.encode()))!=null)throw new SecurityException("Duplicate or invalid member");candidate.memberNames.put(id,alias);
            PreKey backup=PreKey.read(in);if(!backup.verify(m,PreKey.BACKUP))throw new SecurityException("Member backup key is not signed by them");backups.put(id,backup);
            int n=in.readInt();if(n<0||n>MAX_MEMBERS)throw new SecurityException("Too many introductions");List<Intro> list=new ArrayList<>();
            for(int j=0;j<n;j++){String forMember=in.readUTF();PreKey k=PreKey.read(in);if(!k.verify(m,PreKey.ONE_TIME))throw new SecurityException("Introduction key is not signed by its member");list.add(new Intro(forMember,k));}
            if(!list.isEmpty())intros.put(id,list);
        }
        end(in);if(!candidate.members.containsKey(base.owner))throw new SecurityException("Directory has no owner");
        for(List<Intro> list:intros.values())for(Intro i:list)if(!candidate.members.containsKey(i.forMember))throw new SecurityException("Introduction for a non-member");
        candidate.roster=Protocol.b64(packet);candidate.rosterRevision=revision;
        return new Roster(candidate,revision,backups,intros,packet);
    }
    /**
     * Member: adopts a strictly newer roster. Stores every other member's backup key and the
     * introductions addressed to this device. Each revision is adopted once, so each introduction
     * key is used at most once. Locally blocked members stay removed. Returns false if not newer.
     */
    public static boolean adopt(Protocol.Group target,Roster r,Identity me,KeyPools pools){
        if(r.revision<target.rosterRevision)return false;
        if(r.revision==target.rosterRevision){if(!target.roster.equals(Protocol.b64(r.packet)))throw new SecurityException("Conflicting directory revision");return false;}
        String self=me.fingerprint();
        if(!r.group.members.containsKey(self)||!Protocol.b64(me.publicKey.encode()).equals(r.group.members.get(self)))throw new SecurityException("This device is not in the group's member list");
        target.members.clear();target.memberNames.clear();
        for(Map.Entry<String,String> e:r.group.members.entrySet())if(!target.blocked.contains(e.getKey())){target.members.put(e.getKey(),e.getValue());target.memberNames.put(e.getKey(),r.group.memberNames.get(e.getKey()));}
        target.name=r.group.name;target.roster=r.group.roster;target.rosterRevision=r.revision;
        for(String id:target.members.keySet()){
            if(id.equals(self))continue;Identity.Public m=Identity.Public.decode(Protocol.un64(target.members.get(id)));
            pools.setTheirBackup(id,m,r.backups.get(id));
            List<PreKey> mine=new ArrayList<>();for(Intro i:r.intros.getOrDefault(id,Collections.emptyList()))if(i.forMember.equals(self))mine.add(i.key);
            if(!mine.isEmpty())pools.addTheirs(id,m,mine);
        }
        return true;
    }
    /** The current roster, encrypted under the group secret, for carriage inside messages and group updates. */
    public static byte[] carrier(Protocol.Group g)throws Exception{
        if(g.roster.isEmpty())return new byte[0];byte[] nonce=Protocol.random(12),key=rosterKey(g);
        try{return pack(o->{blob(o,nonce);blob(o,Protocol.aes(Cipher.ENCRYPT_MODE,key,nonce,g.id.getBytes(StandardCharsets.UTF_8),Protocol.un64(g.roster)));});}finally{Arrays.fill(key,(byte)0);}
    }
    /** Verifies a carried roster; null when the carrier is empty. */
    public static Roster openCarrier(Protocol.Group g,byte[] carrier)throws Exception{
        if(carrier==null||carrier.length==0)return null;DataInputStream in=input(carrier);byte[] nonce=blob(in,12),cipher=blob(in,MAX_ROSTER+16);end(in);if(nonce.length!=12)throw new SecurityException("Invalid directory nonce");
        byte[] key=rosterKey(g);try{return view(g,Protocol.aes(Cipher.DECRYPT_MODE,key,nonce,g.id.getBytes(StandardCharsets.UTF_8),cipher));}finally{Arrays.fill(key,(byte)0);}
    }
    private static byte[] rosterKey(Protocol.Group g)throws Exception{return Protocol.hkdf(g.seed,g.id.getBytes(StandardCharsets.UTF_8),"Enigma/directory-carrier/v3".getBytes(StandardCharsets.UTF_8));}

    // ---- joining ----
    public static String request(Identity me,KeyPools pools,Entropy entropy,String name)throws Exception{
        if(name.trim().isEmpty()||name.length()>40)throw new IllegalArgumentException("Name this device first");
        long now=System.currentTimeMillis();byte[] id=Arrays.copyOf(entropy.next32("request-id"),16);
        PreKey backup=pools.backup(me,entropy,now);List<PreKey> reserve=pools.issueReserve(me,entropy,now);
        byte[] body=pack(o->{o.writeUTF("join-request-v3");blob(o,me.publicKey.encode());o.writeUTF(name.trim());blob(o,id);o.writeLong(now);o.writeLong(now+WINDOW);backup.write(o);o.writeInt(reserve.size());for(PreKey k:reserve)k.write(o);});
        return REQUEST+Protocol.b64(signed(body,me));
    }
    public static Request parseRequest(String wire)throws Exception{
        DataInputStream env=input(decode(wire,REQUEST,MAX_REQUEST));byte[] body=blob(env,MAX_REQUEST),signature=blob(env,64);end(env);
        DataInputStream in=input(body);if(!in.readUTF().equals("join-request-v3"))throw new SecurityException("Update Enigma on both phones");
        Identity.Public key=Identity.Public.decode(blob(in,64));String name=in.readUTF();byte[] id=blob(in,16);long created=in.readLong(),expires=in.readLong();PreKey backup=PreKey.read(in);
        int n=in.readInt();if(n<0||n>KeyPools.RESERVE)throw new SecurityException("Invalid joining request");List<PreKey> reserve=new ArrayList<>();for(int i=0;i<n;i++)reserve.add(PreKey.read(in));end(in);
        if(!key.verify(body,signature))throw new SecurityException("Invalid signature");
        if(name.trim().isEmpty()||name.length()>40||id.length!=16||expires-created!=WINDOW||!backup.verify(key,PreKey.BACKUP))throw new SecurityException("Invalid joining request");
        for(PreKey k:reserve)if(!k.verify(key,PreKey.ONE_TIME))throw new SecurityException("Invalid joining request");
        Expiry.check(created,expires,expires);return new Request(key,name,Protocol.b64(id),created,expires,backup,reserve);
    }
    /**
     * Owner: admits the requesting device. Builds the next roster with introductions (one held
     * reserve key of each existing member for the newcomer and vice versa), then encrypts the group
     * secret, roster and the owner's one-time pool to the newcomer with X25519 (ephemeral + owner
     * identity) and signs the whole invitation. Membership changes only after everything succeeds.
     */
    public static String invite(Protocol.Group g,Request request,Identity owner,KeyPools pools,Entropy entropy)throws Exception{
        Expiry.check(request.created,request.expires,request.expires);Protocol.live(g);
        String self=owner.fingerprint(),joiner=request.key.fingerprint();
        if(!g.owner.equals(self))throw new SecurityException("Only the group owner can invite");
        if(joiner.equals(self))throw new SecurityException("Choose another device");
        String existing=g.members.get(joiner);if(existing!=null&&!existing.equals(Protocol.b64(request.key.encode())))throw new SecurityException("Fingerprint collision");
        Protocol.Group draft=new Protocol.Group(g.id,g.name,g.seed,g.expires,g.owner);draft.members.putAll(g.members);draft.memberNames.putAll(g.memberNames);draft.rosterRevision=g.rosterRevision;draft.roster=g.roster;
        draft.members.put(joiner,Protocol.b64(request.key.encode()));draft.memberNames.put(joiner,request.name);
        pools.setTheirBackup(joiner,request.key,request.backup);pools.holdReserve(joiner,request.key,request.reserve);
        Map<String,List<Intro>> intros=new LinkedHashMap<>();
        for(String m:g.members.keySet()){
            if(m.equals(self)||m.equals(joiner)||g.blocked.contains(m))continue;
            PreKey theirs=pools.takeReserve(m);if(theirs!=null)intros.computeIfAbsent(m,k->new ArrayList<>()).add(new Intro(joiner,theirs));
            PreKey newcomer=pools.takeReserve(joiner);if(newcomer!=null)intros.computeIfAbsent(joiner,k->new ArrayList<>()).add(new Intro(m,newcomer));
        }
        signRoster(draft,owner,pools,entropy,intros);
        long now=System.currentTimeMillis(),expires=Math.min(request.expires,g.expires);
        List<PreKey> pool=pools.issue(owner,entropy,joiner,KeyPools.POOL,now);
        byte[] ephemeral=entropy.next32("invite-ephemeral"),ephemeralPublic=Identity.x25519Public(ephemeral),nonce=Arrays.copyOf(entropy.next32("invite-nonce"),12),key=null,clear=null;
        try{
            byte[] header=pack(o->{o.writeUTF("invite-v3");blob(o,owner.publicKey.encode());o.writeUTF(joiner);o.writeUTF(request.id);o.writeLong(now);o.writeLong(expires);blob(o,ephemeralPublic);blob(o,nonce);});
            key=inviteKey(header,Identity.agree(ephemeral,request.key.agree),owner.agree(request.key.agree));
            clear=pack(o->{o.writeUTF(draft.id);o.writeUTF(draft.name);o.writeLong(draft.expires);blob(o,draft.seed);blob(o,Protocol.un64(draft.roster));o.writeInt(pool.size());for(PreKey k:pool)k.write(o);});
            byte[] cipher=Protocol.aes(Cipher.ENCRYPT_MODE,key,nonce,header,clear);
            String wire=INVITATION+Protocol.b64(signed(pack(o->{blob(o,header);blob(o,cipher);}),owner));
            g.members.clear();g.members.putAll(draft.members);g.memberNames.clear();g.memberNames.putAll(draft.memberNames);g.roster=draft.roster;g.rosterRevision=draft.rosterRevision;g.blocked.remove(joiner);
            return wire;
        }finally{Arrays.fill(ephemeral,(byte)0);if(key!=null)Arrays.fill(key,(byte)0);if(clear!=null)Arrays.fill(clear,(byte)0);Arrays.fill(draft.seed,(byte)0);}
    }
    /** Joiner: opens the invitation for its own pending request and returns the new group. */
    public static Protocol.Group accept(String wire,String requestWire,Identity me,KeyPools pools)throws Exception{
        Request request=parseRequest(requestWire);if(!request.key.equals(me.publicKey))throw new SecurityException("Joining request belongs to another device");
        DataInputStream env=input(decode(wire,INVITATION,MAX_INVITATION));byte[] body=blob(env,MAX_INVITATION),signature=blob(env,64);end(env);
        DataInputStream b=input(body);byte[] header=blob(b,2000),cipher=blob(b,MAX_INVITATION);end(b);DataInputStream h=input(header);
        if(!h.readUTF().equals("invite-v3"))throw new SecurityException("Update Enigma on both phones");Identity.Public owner=Identity.Public.decode(blob(h,64));
        if(!h.readUTF().equals(me.fingerprint())||!h.readUTF().equals(request.id))throw new SecurityException("This invitation is for another device or joining session");
        long created=h.readLong(),expires=h.readLong();byte[] ephemeral=blob(h,32),nonce=blob(h,12);end(h);
        if(nonce.length!=12||ephemeral.length!=32||expires>request.expires||expires-created>WINDOW)throw new SecurityException("Invalid invitation lifetime");
        if(!owner.verify(body,signature))throw new SecurityException("Invalid signature");Expiry.check(created,expires,expires);
        byte[] key=inviteKey(header,me.agree(ephemeral),me.agree(owner.agree)),clear=null;
        try{
            clear=Protocol.aes(Cipher.DECRYPT_MODE,key,nonce,header,cipher);DataInputStream in=input(clear);
            String id=in.readUTF(),name=in.readUTF();long groupExpiry=in.readLong();byte[] seed=blob(in,32),roster=blob(in,MAX_ROSTER);
            int n=in.readInt();if(n<0||n>KeyPools.POOL)throw new SecurityException("Invalid invitation");List<PreKey> pool=new ArrayList<>();for(int i=0;i<n;i++)pool.add(PreKey.read(in));end(in);
            if(groupExpiry<=System.currentTimeMillis()||groupExpiry>Instant.now().plus(Duration.ofDays(3660)).toEpochMilli())throw new SecurityException("Invalid group");
            Protocol.Group g;try{g=new Protocol.Group(id,name,seed,groupExpiry,owner.fingerprint());}finally{Arrays.fill(seed,(byte)0);}
            g.members.put(g.owner,Protocol.b64(owner.encode()));
            if(!adopt(g,view(g,roster),me,pools))throw new SecurityException("Invalid member directory");
            pools.addTheirs(g.owner,owner,pool);
            return g;
        }finally{Arrays.fill(key,(byte)0);if(clear!=null)Arrays.fill(clear,(byte)0);}
    }
    /** Joiner → owner: signed confirmation carrying the joiner's one-time pool for the owner. */
    public static String receipt(Protocol.Group g,String requestId,Identity me,KeyPools pools,Entropy entropy)throws Exception{
        List<PreKey> pool=pools.issue(me,entropy,g.owner,KeyPools.POOL,System.currentTimeMillis());
        byte[] body=pack(o->{o.writeUTF("joined-v3");o.writeUTF(g.id);o.writeUTF(requestId);o.writeUTF(me.fingerprint());o.writeInt(pool.size());for(PreKey k:pool)k.write(o);});
        return RECEIPT+Protocol.b64(signed(body,me));
    }
    /** Owner: verifies a receipt for a pending invitation, stores the joiner's pool and marks them joined. */
    public static String receiptMember(Protocol.Group g,String wire,Map<String,String> pending,KeyPools pools)throws Exception{
        DataInputStream env=input(decode(wire,RECEIPT,MAX_RECEIPT));byte[] body=blob(env,MAX_RECEIPT),signature=blob(env,64);end(env);DataInputStream in=input(body);
        if(!in.readUTF().equals("joined-v3")||!in.readUTF().equals(g.id))throw new SecurityException("Confirmation belongs to another group");
        String request=in.readUTF(),member=in.readUTF();int n=in.readInt();if(n<0||n>KeyPools.POOL)throw new SecurityException("Invalid confirmation");
        List<PreKey> pool=new ArrayList<>();for(int i=0;i<n;i++)pool.add(PreKey.read(in));end(in);
        if(!member.equals(pending.get(request)))throw new SecurityException("Unknown joining confirmation");
        Identity.Public key=SealedProtocol.member(g,member);if(key==null||!key.verify(body,signature))throw new SecurityException("Invalid signature");
        pools.addTheirs(member,key,pool);pending.remove(request);g.memberStatus.put(member,"Joined");return member;
    }

    private static byte[] inviteKey(byte[] header,byte[] ephemeralShared,byte[] staticShared)throws Exception{
        byte[] material=new byte[64];System.arraycopy(ephemeralShared,0,material,0,32);System.arraycopy(staticShared,0,material,32,32);
        try{return Protocol.hkdf(material,MessageDigest.getInstance("SHA-256").digest(header),"Enigma/invite/v3".getBytes(StandardCharsets.UTF_8));}
        finally{Arrays.fill(material,(byte)0);Arrays.fill(ephemeralShared,(byte)0);Arrays.fill(staticShared,(byte)0);}
    }
    private static byte[] signed(byte[] body,Identity key)throws Exception{byte[] signature=key.sign(body);return pack(o->{blob(o,body);blob(o,signature);});}
    private static byte[] verified(byte[] envelope,Identity.Public key,int max)throws Exception{DataInputStream in=input(envelope);byte[] body=blob(in,max),signature=blob(in,64);end(in);if(!key.verify(body,signature))throw new SecurityException("Invalid signature");return body;}
}
