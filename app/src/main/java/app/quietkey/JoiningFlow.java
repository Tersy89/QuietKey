package app.quietkey;

import java.security.*;
import java.util.*;

/** Retries may repeat an existing grant, never erase used-request protection. */
public final class JoiningFlow {
    public static final int MAX_PENDING=64;
    public static final class Invitation {
        public final String request,group,member,packet;
        public final long expires,revision;
        public Invitation(String request,String group,String member,String packet,long expires,long revision){
            this.request=request;this.group=group;this.member=member;this.packet=packet;this.expires=expires;this.revision=revision;
        }
    }
    public static String request(PublicKey own,PrivateKey key,String name,String current,Set<String> used,boolean fresh)throws Exception{
        if(!fresh&&!current.isEmpty())try{if(!used.contains(PairingProtocol.parseRequest(current).id))return current;}catch(Exception ignored){}
        return PairingProtocol.request(own,key,name);
    }
    public static String invite(Protocol.Group group,String wire,PublicKey own,PrivateKey key,Set<String> used,Map<String,String> pending,Map<String,Invitation> cached)throws Exception{
        PairingProtocol.Request request=PairingProtocol.parseRequest(wire);
        if(!group.owner.equals(Protocol.fingerprint(own)))throw new SecurityException("Only the group owner can invite");
        if(System.currentTimeMillis()>=group.expires)throw new SecurityException("Group expired");
        String member=Protocol.fingerprint(request.key);Invitation prior=cached.get(request.id);
        if(used.contains(request.id)){
            if(prior==null||!prior.group.equals(group.id)||!prior.request.equals(wire)||!prior.member.equals(member)||!member.equals(pending.get(request.id))||group.blocked.contains(member)||!Protocol.b64(request.key.getEncoded()).equals(group.members.get(member)))
                throw new SecurityException("On the joining phone tap Start again to make a fresh QR, then invite it here. No need to reset either app.");
            if(prior.revision==group.rosterRevision)return prior.packet;
        }else if(used.size()>=10000)throw new SecurityException("Joining history full");
        // Remove expired cached transfers, keeping their permanent used-request tombstones.
        for(String id:new ArrayList<>(cached.keySet()))if(cached.get(id).expires<=System.currentTimeMillis()){cached.remove(id);pending.remove(id);}
        boolean replaces=false;for(Invitation item:cached.values())if(item.group.equals(group.id)&&item.member.equals(member))replaces=true;
        if(prior==null&&!replaces&&cached.size()>=MAX_PENDING)throw new SecurityException("Too many unfinished invitations. Cancel one in group details first.");
        String packet=PairingProtocol.invite(group,request,own,key);
        cancel(group.id,member,pending,cached);
        group.memberStatus.put(member,"Invited");used.add(request.id);pending.put(request.id,member);
        cached.put(request.id,new Invitation(wire,group.id,member,packet,request.expires,group.rosterRevision));
        return packet;
    }
    public static void cancel(String group,String member,Map<String,String> pending,Map<String,Invitation> cached){
        for(String id:new ArrayList<>(cached.keySet())){Invitation item=cached.get(id);if(item.group.equals(group)&&(member==null||item.member.equals(member))){cached.remove(id);pending.remove(id);}}
    }
}
