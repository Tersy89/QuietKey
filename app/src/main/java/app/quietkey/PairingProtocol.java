package app.quietkey;
import java.io.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Cipher;
import static app.quietkey.Packets.*;

/** Recipient-bound joining and owner-signed directories. No private identity key is transferred. */
public final class PairingProtocol {
    public static final int MAX_MEMBERS=30,MAX_ROSTER=16000;
    public static final long WINDOW=300000;
    public static final class Request {
        public final PublicKey key;public final String name,id;public final long created,expires;
        Request(PublicKey k,String n,String i,long c,long e){key=k;name=n;id=i;created=c;expires=e;}
    }
    public static String request(PublicKey key,PrivateKey privateKey,String name)throws Exception{
        if(name.trim().isEmpty()||name.length()>40)throw new IllegalArgumentException("Name this device first");
        long now=System.currentTimeMillis();byte[] id=Protocol.random(16);
        byte[] body=pack(o->{o.writeUTF("join-request-v1");blob(o,key.getEncoded());o.writeUTF(name);blob(o,id);o.writeLong(now);o.writeLong(now+WINDOW);});
        return "QKJ1:"+Protocol.b64(signed(body,privateKey));
    }
    public static Request parseRequest(String wire)throws Exception{
        byte[] envelope=decode(wire,"QKJ1:",1600);DataInputStream out=input(envelope);byte[] body=blob(out,800);blob(out,256);end(out);
        DataInputStream in=input(body);if(!in.readUTF().equals("join-request-v1"))throw new SecurityException("Unsupported joining request");
        PublicKey key=Protocol.publicKey(Protocol.b64(blob(in,400)));String name=in.readUTF();byte[] id=blob(in,16);long created=in.readLong(),expires=in.readLong();end(in);
        verified(envelope,key,800);if(name.trim().isEmpty()||name.length()>40||id.length!=16||expires-created!=WINDOW)throw new SecurityException("Invalid joining request");
        Expiry.check(created,expires,expires);return new Request(key,name,Protocol.b64(id),created,expires);
    }
    public static void signRoster(Protocol.Group g,PublicKey owner,PrivateKey key)throws Exception{
        if(!g.owner.equals(Protocol.fingerprint(owner)))throw new SecurityException("Only the owner can approve members");
        if(g.members.size()>MAX_MEMBERS)throw new IllegalArgumentException("Maximum 30 devices per group");
        long revision=Math.addExact(g.rosterRevision,1);
        byte[] body=pack(o->{o.writeUTF("roster-v1");o.writeUTF(g.id);blob(o,owner.getEncoded());o.writeLong(g.expires);o.writeLong(revision);o.writeUTF(g.name);o.writeInt(g.members.size());
            for(Map.Entry<String,String> e:g.members.entrySet()){PublicKey member=Protocol.publicKey(e.getValue());if(!e.getKey().equals(Protocol.fingerprint(member)))throw new SecurityException("Invalid member key");blob(o,member.getEncoded());String alias=g.memberNames.getOrDefault(e.getKey(),"Member");if(alias.length()>40)throw new SecurityException("Member name too long");o.writeUTF(alias);}});
        byte[] packet=signed(body,key);if(packet.length>MAX_ROSTER)throw new SecurityException("Directory too large");
        // Verify our own signature before replacing persisted trust.
        verified(packet,owner,MAX_ROSTER);g.roster=Protocol.b64(packet);g.rosterRevision=revision;
    }
    public static Protocol.Group rosterView(Protocol.Group base,byte[] packet)throws Exception{
        if(packet.length==0)return base;
        PublicKey owner=DirectProtocol.recipient(base,base.owner);byte[] body=verified(packet,owner,MAX_ROSTER);
        DataInputStream in=input(body);if(!in.readUTF().equals("roster-v1")||!in.readUTF().equals(base.id))throw new SecurityException("Directory belongs to another group");
        PublicKey claimed=Protocol.publicKey(Protocol.b64(blob(in,400)));long expiry=in.readLong(),revision=in.readLong();String name=in.readUTF();int count=in.readInt();
        if(!claimed.equals(owner)||expiry!=base.expires||revision<=0||count<1||count>MAX_MEMBERS)throw new SecurityException("Invalid member directory");
        Protocol.Group candidate=new Protocol.Group(base.id,name,base.seed,base.expires,base.owner);
        for(int i=0;i<count;i++){PublicKey member=Protocol.publicKey(Protocol.b64(blob(in,400)));String id=Protocol.fingerprint(member),alias=in.readUTF();if(alias.length()>40||candidate.members.put(id,Protocol.b64(member.getEncoded()))!=null)throw new SecurityException("Duplicate or invalid member");candidate.memberNames.put(id,alias);}
        end(in);if(!candidate.members.containsKey(base.owner))throw new SecurityException("Directory has no owner");
        if(revision<base.rosterRevision)return base; // Validate old signatures without rolling back current trust.
        if(revision==base.rosterRevision&&!base.roster.equals(Protocol.b64(packet)))throw new SecurityException("Conflicting directory revision");
        candidate.roster=Protocol.b64(packet);candidate.rosterRevision=revision;candidate.blocked.addAll(base.blocked);candidate.memberStatus.putAll(base.memberStatus);
        for(String blocked:base.blocked){candidate.members.remove(blocked);candidate.memberNames.remove(blocked);}
        return candidate;
    }
    public static void applyRoster(Protocol.Group target,Protocol.Group candidate){
        if(target==candidate)return;target.members.clear();target.members.putAll(candidate.members);target.memberNames.clear();target.memberNames.putAll(candidate.memberNames);target.roster=candidate.roster;target.rosterRevision=candidate.rosterRevision;
    }
    public static byte[] carrier(Protocol.Group g)throws Exception{
        if(g.roster.isEmpty())return new byte[0];byte[] nonce=Protocol.random(12),key=rosterKey(g);
        try{return pack(o->{blob(o,nonce);blob(o,Protocol.aes(Cipher.ENCRYPT_MODE,key,nonce,g.id.getBytes(StandardCharsets.UTF_8),Protocol.un64(g.roster)));});}finally{Arrays.fill(key,(byte)0);}
    }
    public static Protocol.Group carrierView(Protocol.Group g,byte[] carrier)throws Exception{
        if(carrier.length==0)return g;DataInputStream in=input(carrier);byte[] nonce=blob(in,12),cipher=blob(in,MAX_ROSTER+16);end(in);if(nonce.length!=12)throw new SecurityException("Invalid directory nonce");byte[] key=rosterKey(g),clear=null;
        try{clear=Protocol.aes(Cipher.DECRYPT_MODE,key,nonce,g.id.getBytes(StandardCharsets.UTF_8),cipher);return rosterView(g,clear);}finally{Arrays.fill(key,(byte)0);if(clear!=null)Arrays.fill(clear,(byte)0);}
    }
    private static byte[] rosterKey(Protocol.Group g)throws Exception{return Protocol.hkdf(g.seed,g.id.getBytes(StandardCharsets.UTF_8),"Enigma/directory-carrier/v1".getBytes(StandardCharsets.UTF_8));}
    public static String invite(Protocol.Group g,Request request,PublicKey owner,PrivateKey privateKey)throws Exception{
        Expiry.check(request.created,request.expires,request.expires);if(System.currentTimeMillis()>=g.expires)throw new SecurityException("Group expired");
        if(request.key.equals(owner))throw new SecurityException("Choose another device");
        // Build transactionally: failed invitations must not mutate membership.
        Protocol.Group target=g;g=new Protocol.Group(target.id,target.name,target.seed,target.expires,target.owner);g.members.putAll(target.members);g.memberNames.putAll(target.memberNames);g.rosterRevision=target.rosterRevision;
        g.members.put(Protocol.fingerprint(request.key),Protocol.b64(request.key.getEncoded()));g.memberNames.put(Protocol.fingerprint(request.key),request.name);signRoster(g,owner,privateKey);
        final Protocol.Group draft=g;
        long created=System.currentTimeMillis(),expires=Math.min(request.expires,g.expires);byte[] key=Protocol.random(32),nonce=Protocol.random(12);
        byte[] header=pack(o->{o.writeUTF("invite-v2");blob(o,owner.getEncoded());o.writeUTF(Protocol.fingerprint(request.key));o.writeUTF(request.id);o.writeLong(created);o.writeLong(expires);blob(o,Protocol.rsa(Cipher.ENCRYPT_MODE,request.key,key));blob(o,nonce);});
        byte[] clear=pack(o->{o.writeUTF(draft.id);o.writeUTF(draft.name);o.writeLong(draft.expires);blob(o,draft.seed);blob(o,Protocol.un64(draft.roster));});
        try{byte[] cipher=Protocol.aes(Cipher.ENCRYPT_MODE,key,nonce,header,clear);String wire="QKG2:"+Protocol.b64(signed(pack(o->{blob(o,header);blob(o,cipher);}),privateKey));applyRoster(target,draft);target.blocked.remove(Protocol.fingerprint(request.key));return wire;}finally{Arrays.fill(key,(byte)0);Arrays.fill(clear,(byte)0);Arrays.fill(draft.seed,(byte)0);}
    }
    public static Protocol.Group accept(String wire,String requestWire,PublicKey own,PrivateKey privateKey)throws Exception{
        Request request=parseRequest(requestWire);if(!request.key.equals(own))throw new SecurityException("Joining request belongs to another device");
        byte[] envelope=decode(wire,"QKG2:",30000);DataInputStream outer=input(envelope);byte[] body=blob(outer,22000);blob(outer,256);end(outer);
        DataInputStream b=input(body);byte[] header=blob(b,1000),cipher=blob(b,18000);end(b);DataInputStream h=input(header);
        if(!h.readUTF().equals("invite-v2"))throw new SecurityException("Unsupported invitation");PublicKey owner=Protocol.publicKey(Protocol.b64(blob(h,400)));
        if(!h.readUTF().equals(Protocol.fingerprint(own))||!h.readUTF().equals(request.id))throw new SecurityException("This invitation is for another device or joining session");long created=h.readLong(),expires=h.readLong();
        byte[] wrapped=blob(h,256),nonce=blob(h,12);end(h);if(nonce.length!=12||expires>request.expires||expires-created>WINDOW)throw new SecurityException("Invalid invitation lifetime");verified(envelope,owner,22000);Expiry.check(created,expires,expires);
        byte[] key=Protocol.rsa(Cipher.DECRYPT_MODE,privateKey,wrapped),clear=null;
        try{if(key.length!=32)throw new SecurityException("Invalid invitation key");clear=Protocol.aes(Cipher.DECRYPT_MODE,key,nonce,header,cipher);DataInputStream in=input(clear);String id=in.readUTF(),name=in.readUTF();long groupExpiry=in.readLong();byte[] seed=blob(in,32),roster=blob(in,MAX_ROSTER);end(in);
            Protocol.Group g=new Protocol.Group(id,name,seed,groupExpiry,Protocol.fingerprint(owner));Arrays.fill(seed,(byte)0);g.members.put(g.owner,Protocol.b64(owner.getEncoded()));Protocol.Group candidate=rosterView(g,roster);
            if(groupExpiry<=System.currentTimeMillis()||groupExpiry>java.time.Instant.now().plus(java.time.Duration.ofDays(3660)).toEpochMilli()||!candidate.members.containsKey(Protocol.fingerprint(own)))throw new SecurityException("Invalid group or missing recipient");applyRoster(g,candidate);return g;
        }finally{Arrays.fill(key,(byte)0);if(clear!=null)Arrays.fill(clear,(byte)0);}
    }
    public static String receipt(Protocol.Group g,String requestId,PublicKey own,PrivateKey privateKey)throws Exception{
        byte[] body=pack(o->{o.writeUTF("joined-v1");o.writeUTF(g.id);o.writeUTF(requestId);o.writeUTF(Protocol.fingerprint(own));});return "QKA1:"+Protocol.b64(signed(body,privateKey));
    }
    public static String receiptMember(Protocol.Group g,String wire,Map<String,String> pending)throws Exception{
        byte[] packet=decode(wire,"QKA1:",1500);DataInputStream out=input(packet);byte[] body=blob(out,500);blob(out,256);end(out);DataInputStream in=input(body);
        if(!in.readUTF().equals("joined-v1")||!in.readUTF().equals(g.id))throw new SecurityException("Confirmation belongs to another group");String request=in.readUTF(),member=in.readUTF();end(in);
        if(!member.equals(pending.get(request)))throw new SecurityException("Unknown joining confirmation");verified(packet,DirectProtocol.recipient(g,member),500);pending.remove(request);g.memberStatus.put(member,"Joined");return member;
    }
}
