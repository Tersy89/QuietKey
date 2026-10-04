package app.quietkey;

import org.json.JSONObject;

/** Only ciphertext and routing metadata are persisted, inside the encrypted vault. */
public final class MessageRecord {
    public final String id,group,sender,recipient,kind,payload,name;
    public final long createdAt,expiresAt;
    public final boolean outgoing;
    public MessageRecord(String id,String group,String sender,String recipient,String kind,String payload,String name,long created,long expires,boolean outgoing){
        this.id=id;this.group=group;this.sender=sender;this.recipient=recipient;this.kind=kind;this.payload=payload;this.name=name;createdAt=created;expiresAt=expires;this.outgoing=outgoing;
    }
    JSONObject json()throws Exception {JSONObject j=new JSONObject();j.put("id",id);j.put("group",group);j.put("sender",sender);j.put("recipient",recipient);j.put("kind",kind);j.put("payload",payload);j.put("name",name);j.put("created",createdAt);j.put("expires",expiresAt);j.put("outgoing",outgoing);return j;}
    static MessageRecord read(JSONObject j)throws Exception{
        String kind=j.getString("kind"),payload=j.getString("payload");
        if((!kind.equals("text") && !kind.equals("file")) || (kind.equals("text")?payload.length()>75000:!payload.matches("[0-9a-f-]{36}\\.enigma")))throw new SecurityException("Invalid message history");
        return new MessageRecord(j.getString("id"),j.getString("group"),j.getString("sender"),j.getString("recipient"),kind,payload,j.getString("name"),j.getLong("created"),j.getLong("expires"),j.getBoolean("outgoing"));
    }
}
