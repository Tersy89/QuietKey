package app.quietkey;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** At-rest vault encrypted with a non-exportable Android Keystore AES key. */
public final class Vault implements AutoCloseable {
    private static final String ID = "quietkey.identity.v1", STORAGE = "quietkey.vault.v1";
    private final AtomicFile file;
    private final KeyStore ks;
    private byte[] master;
    private boolean closed;
    public final ArrayList<Protocol.Group> groups = new ArrayList<>();
    public final ArrayList<MessageRecord> history=new ArrayList<>();
    public String deviceName="This device", personalGroupId;
    public boolean setupCompleted;
    public long setupCapturedAt;
    public String joiningRequest="";
    public final HashSet<String> pairingUsed=new HashSet<>();
    public final LinkedHashMap<String,String> pendingInvites=new LinkedHashMap<>();
    public final LinkedHashMap<String,JoiningFlow.Invitation> invitationCache=new LinkedHashMap<>();
    public Vault(Context context) throws Exception {
        this(context,null);
    }
    public Vault(Context context,byte[] masterKey) throws Exception {
        if(masterKey==null && new PasswordLock(context).configured())throw new SecurityException("Unlock Enigma first");
        if(masterKey!=null){if(masterKey.length!=32)throw new SecurityException("Invalid vault key");master=masterKey.clone();}
        file = new AtomicFile(new File(context.getFilesDir(), "groups.qkv"));
        ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        boolean stored = file.getBaseFile().exists() || new File(file.getBaseFile()+".bak").exists();
        if (stored && (!ks.containsAlias(ID) || !ks.containsAlias(STORAGE))) throw new SecurityException("Vault keys unavailable. Recovery requires pairing again on a new installation");
        if (!ks.containsAlias(ID)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ID, KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build());
            generator.generateKeyPair();
        }
        if (!ks.containsAlias(STORAGE)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(STORAGE, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        if (stored) {load();if(master!=null && file.readFully()[0]==1)save();}
    }
    private void live(){if(closed)throw new IllegalStateException("Vault is locked");}
    public PublicKey publicKey() throws Exception { live();return ks.getCertificate(ID).getPublicKey(); }
    public PrivateKey privateKey() throws Exception { live();return (PrivateKey) ks.getKey(ID, null); }
    public synchronized byte[] copyMaster(){live();if(master==null)throw new IllegalStateException("Set an app password first");return master.clone();}
    @Override public synchronized void close(){closed=true;if(master!=null)Arrays.fill(master,(byte)0);master=null;for(Protocol.Group g:groups)Arrays.fill(g.seed,(byte)0);groups.clear();history.clear();joiningRequest="";pendingInvites.clear();invitationCache.clear();pairingUsed.clear();}
    private SecretKey storageKey() throws Exception { return (SecretKey) ks.getKey(STORAGE, null); }
    private void load() throws Exception {
        byte[] bytes = file.readFully();
        if (bytes.length < 29 || bytes.length > 24000000 || (bytes[0] != 1 && bytes[0]!=2)) throw new SecurityException("Invalid vault file");
        if(bytes[0]==2 && master==null)throw new SecurityException("Unlock Enigma first");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,bytes[0]==1?storageKey():new javax.crypto.spec.SecretKeySpec(master,"AES"), new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 1, 13)));
        cipher.updateAAD((bytes[0]==1?"QuietKey-vault-v1":"Enigma-vault-v2").getBytes(StandardCharsets.UTF_8));
        byte[] clear = cipher.doFinal(Arrays.copyOfRange(bytes, 13, bytes.length));
        JSONObject root;
        try { root = new JSONObject(new String(clear, StandardCharsets.UTF_8)); } finally { Arrays.fill(clear, (byte)0); }
        if (root.getInt("version") != 1) throw new SecurityException("Unsupported vault version");
        deviceName=root.optString("deviceName","This device");
        setupCompleted=root.optBoolean("setupCompleted",false);
        personalGroupId=root.optString("personalGroupId",null);
        setupCapturedAt=root.optLong("setupCapturedAt",0);
        joiningRequest=root.optString("joiningRequest","");
        JSONArray used=root.optJSONArray("pairingUsed");if(used!=null){if(used.length()>10000)throw new SecurityException("Joining history full");for(int i=0;i<used.length();i++)pairingUsed.add(used.getString(i));}
        JSONObject pending=root.optJSONObject("pendingInvites");if(pending!=null){if(pending.length()>10000)throw new SecurityException("Too many invitations");Iterator<String> it=pending.keys();while(it.hasNext()){String id=it.next();pendingInvites.put(id,pending.getString(id));}}
        JSONObject invitations=root.optJSONObject("invitationCache");
        if(invitations!=null){if(invitations.length()>JoiningFlow.MAX_PENDING)throw new SecurityException("Too many saved invitations");Iterator<String> ids=invitations.keys();while(ids.hasNext()){String id=ids.next();JSONObject item=invitations.getJSONObject(id);String request=item.getString("request"),packet=item.getString("packet");if(request.length()>1600||packet.length()>30000)throw new SecurityException("Invalid saved invitation");invitationCache.put(id,new JoiningFlow.Invitation(request,item.getString("group"),item.getString("member"),packet,item.getLong("expires"),item.getLong("revision")));}}
        JSONArray list = root.getJSONArray("groups");
        if (list.length() > 30) throw new SecurityException("Too many groups");
        HashSet<String> ids = new HashSet<>();
        for (int i=0;i<list.length();i++) {
            JSONObject item = list.getJSONObject(i);
            Protocol.Group g = new Protocol.Group(item.getString("id"), item.getString("name"), Protocol.un64(item.getString("seed")), item.getLong("expires"), item.getString("owner"));
            if (!ids.add(g.id)) throw new SecurityException("Duplicate group");
            JSONObject members = item.getJSONObject("members"); Iterator<String> keys = members.keys();
            while (keys.hasNext()) { String key = keys.next(); String encoded = members.getString(key);
                if (!Protocol.fingerprint(Protocol.publicKey(encoded)).equals(key)) throw new SecurityException("Invalid membership"); g.members.put(key, encoded); }
            if (!g.members.containsKey(g.owner)) throw new SecurityException("Missing group owner");
            g.rosterRevision=item.optLong("rosterRevision",0);g.roster=item.optString("roster","");
            JSONArray blocked=item.optJSONArray("blocked");if(blocked!=null)for(int j=0;j<blocked.length();j++)g.blocked.add(blocked.getString(j));
            JSONObject status=item.optJSONObject("memberStatus");if(status!=null)for(String member:g.members.keySet())g.memberStatus.put(member,status.optString(member,"Member"));
            JSONObject names=item.optJSONObject("memberNames");
            if(names!=null) for(String id:g.members.keySet()) {
                String name=names.optString(id,""); if(!name.isEmpty() && name.length()<=40)g.memberNames.put(id,name);
            }
            JSONArray seen = item.getJSONArray("seen"); if (seen.length()>100000) throw new SecurityException("Replay log too large");
            for (int j=0;j<seen.length();j++) g.seen.add(seen.getString(j));
            groups.add(g);
        }
        JSONArray messages=root.optJSONArray("history");
        if(messages!=null){if(messages.length()>200)throw new SecurityException("Message history too large");for(int i=0;i<messages.length();i++)history.add(MessageRecord.read(messages.getJSONObject(i)));}
    }
    public synchronized void save() throws Exception {
        live();
        if (groups.size()>30) throw new IllegalArgumentException("Maximum 30 groups");
        JSONArray list = new JSONArray();
        for (Protocol.Group g : groups) {
            JSONObject item = new JSONObject(); item.put("id",g.id); item.put("name",g.name); item.put("seed",Protocol.b64(g.seed)); item.put("expires",g.expires); item.put("owner",g.owner);
            JSONObject members = new JSONObject(); for (Map.Entry<String,String> m:g.members.entrySet()) members.put(m.getKey(),m.getValue());
            item.put("members",members); item.put("seen",new JSONArray(new ArrayList<>(g.seen))); list.put(item);
            JSONObject names=new JSONObject(); for(Map.Entry<String,String> m:g.memberNames.entrySet())names.put(m.getKey(),m.getValue());
            item.put("memberNames",names);
            item.put("rosterRevision",g.rosterRevision);item.put("roster",g.roster);item.put("blocked",new JSONArray(new ArrayList<>(g.blocked)));item.put("memberStatus",new JSONObject(g.memberStatus));
        }
        JSONObject root = new JSONObject(); root.put("version",1); root.put("groups",list);
        root.put("deviceName",deviceName); root.put("setupCompleted",setupCompleted);
        root.put("personalGroupId",personalGroupId); root.put("setupCapturedAt",setupCapturedAt);
        if(invitationCache.size()>JoiningFlow.MAX_PENDING)throw new SecurityException("Too many saved invitations");
        JSONObject invitations=new JSONObject();for(Map.Entry<String,JoiningFlow.Invitation> entry:invitationCache.entrySet()){JoiningFlow.Invitation item=entry.getValue();JSONObject value=new JSONObject();value.put("request",item.request);value.put("group",item.group);value.put("member",item.member);value.put("packet",item.packet);value.put("expires",item.expires);value.put("revision",item.revision);invitations.put(entry.getKey(),value);}root.put("invitationCache",invitations);
        root.put("joiningRequest",joiningRequest);root.put("pairingUsed",new JSONArray(new ArrayList<>(pairingUsed)));root.put("pendingInvites",new JSONObject(pendingInvites));
        JSONArray messages=new JSONArray();for(MessageRecord message:history)messages.put(message.json());root.put("history",messages);
        byte[] clear = root.toString().getBytes(StandardCharsets.UTF_8);
        if(clear.length + 29 > 24000000) { Arrays.fill(clear,(byte)0); throw new SecurityException("Vault capacity reached. Create a fresh installation or delete old groups after preserving access on another device."); }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,master==null?storageKey():new javax.crypto.spec.SecretKeySpec(master,"AES"));
        cipher.updateAAD((master==null?"QuietKey-vault-v1":"Enigma-vault-v2").getBytes(StandardCharsets.UTF_8));
        byte[] encrypted;
        try { encrypted = cipher.doFinal(clear); } finally { Arrays.fill(clear,(byte)0); }
        ByteArrayOutputStream packetStream = new ByteArrayOutputStream(); packetStream.write(master==null?1:2); packetStream.write(cipher.getIV()); packetStream.write(encrypted);
        byte[] packet = packetStream.toByteArray();
        FileOutputStream stream = null;
        try { stream = file.startWrite(); stream.write(packet); stream.getFD().sync(); file.finishWrite(stream);
            if(!Arrays.equals(packet,file.readFully())) throw new IOException("Vault commit verification failed"); }
        catch (Exception e) { if (stream != null) file.failWrite(stream); throw e; }
    }
}
