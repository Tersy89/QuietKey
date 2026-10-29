package app.quietkey;

import org.json.JSONObject;

/**
 * One message in a conversation, persisted inside the encrypted vault (0.7.0, decision 1):
 * - kind "file": the readable content, encrypted under the vault key, in files/history/{payload};
 * - kind "once": a view-once message; nothing readable is kept.
 * Outgoing records also name the .enigma package kept for Share again ({@link #packet}).
 * A multi-recipient send stores one record per recipient, sharing the payload and packet.
 */
public final class MessageRecord {
    public final String id,group,sender,recipient,kind,payload,name,mime,packet;
    public final long createdAt,expiresAt;
    public final boolean outgoing,reduced;
    public MessageRecord(String id,String group,String sender,String recipient,String kind,String payload,String name,String mime,String packet,long created,long expires,boolean outgoing,boolean reduced){
        this.id=id;this.group=group;this.sender=sender;this.recipient=recipient;this.kind=kind;this.payload=payload;this.name=name;this.mime=mime==null?"":mime;this.packet=packet==null?"":packet;
        createdAt=created;expiresAt=expires;this.outgoing=outgoing;this.reduced=reduced;
    }
    JSONObject json()throws Exception {
        JSONObject j=new JSONObject();j.put("id",id);j.put("group",group);j.put("sender",sender);j.put("recipient",recipient);j.put("kind",kind);j.put("payload",payload);j.put("name",name);j.put("mime",mime);j.put("packet",packet);
        j.put("created",createdAt);j.put("expires",expiresAt);j.put("outgoing",outgoing);j.put("reduced",reduced);return j;
    }
    static MessageRecord read(JSONObject j)throws Exception{
        String kind=j.getString("kind"),payload=j.getString("payload"),packet=j.optString("packet","");
        boolean valid=kind.equals("file")?payload.matches("[0-9a-f-]{36}\\.plain"):kind.equals("once")&&payload.isEmpty();
        if(!valid||!(packet.isEmpty()||packet.matches("[A-Za-z0-9_-]{22}\\.enigma")))throw new SecurityException("Invalid message history");
        return new MessageRecord(j.getString("id"),j.getString("group"),j.getString("sender"),j.getString("recipient"),kind,payload,j.getString("name"),j.optString("mime",""),packet,j.getLong("created"),j.getLong("expires"),j.getBoolean("outgoing"),j.optBoolean("reduced",false));
    }
}
