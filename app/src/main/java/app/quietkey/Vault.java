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
public final class Vault {
    private static final String ID = "quietkey.identity.v1", STORAGE = "quietkey.vault.v1";
    private final AtomicFile file;
    private final KeyStore ks;
    public final ArrayList<Protocol.Group> groups = new ArrayList<>();
    public Vault(Context context) throws Exception {
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
        if (stored) load();
    }
    public PublicKey publicKey() throws Exception { return ks.getCertificate(ID).getPublicKey(); }
    public PrivateKey privateKey() throws Exception { return (PrivateKey) ks.getKey(ID, null); }
    private SecretKey storageKey() throws Exception { return (SecretKey) ks.getKey(STORAGE, null); }
    private void load() throws Exception {
        byte[] bytes = file.readFully();
        if (bytes.length < 29 || bytes.length > 24000000 || bytes[0] != 1) throw new SecurityException("Invalid vault file");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, storageKey(), new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 1, 13)));
        cipher.updateAAD("QuietKey-vault-v1".getBytes(StandardCharsets.UTF_8));
        byte[] clear = cipher.doFinal(Arrays.copyOfRange(bytes, 13, bytes.length));
        JSONObject root;
        try { root = new JSONObject(new String(clear, StandardCharsets.UTF_8)); } finally { Arrays.fill(clear, (byte)0); }
        if (root.getInt("version") != 1) throw new SecurityException("Unsupported vault version");
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
            JSONArray seen = item.getJSONArray("seen"); if (seen.length()>100000) throw new SecurityException("Replay log too large");
            for (int j=0;j<seen.length();j++) g.seen.add(seen.getString(j));
            groups.add(g);
        }
    }
    public synchronized void save() throws Exception {
        if (groups.size()>30) throw new IllegalArgumentException("Maximum 30 groups");
        JSONArray list = new JSONArray();
        for (Protocol.Group g : groups) {
            JSONObject item = new JSONObject(); item.put("id",g.id); item.put("name",g.name); item.put("seed",Protocol.b64(g.seed)); item.put("expires",g.expires); item.put("owner",g.owner);
            JSONObject members = new JSONObject(); for (Map.Entry<String,String> m:g.members.entrySet()) members.put(m.getKey(),m.getValue());
            item.put("members",members); item.put("seen",new JSONArray(new ArrayList<>(g.seen))); list.put(item);
        }
        JSONObject root = new JSONObject(); root.put("version",1); root.put("groups",list);
        byte[] clear = root.toString().getBytes(StandardCharsets.UTF_8);
        if(clear.length + 29 > 24000000) { Arrays.fill(clear,(byte)0); throw new SecurityException("Vault capacity reached. Create a fresh installation or delete old groups after preserving access on another device."); }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,storageKey());
        cipher.updateAAD("QuietKey-vault-v1".getBytes(StandardCharsets.UTF_8));
        byte[] encrypted;
        try { encrypted = cipher.doFinal(clear); } finally { Arrays.fill(clear,(byte)0); }
        ByteArrayOutputStream packetStream = new ByteArrayOutputStream(); packetStream.write(1); packetStream.write(cipher.getIV()); packetStream.write(encrypted);
        byte[] packet = packetStream.toByteArray();
        FileOutputStream stream = null;
        try { stream = file.startWrite(); stream.write(packet); stream.getFD().sync(); file.finishWrite(stream);
            if(!Arrays.equals(packet,file.readFully())) throw new IOException("Vault commit verification failed"); }
        catch (Exception e) { if (stream != null) file.failWrite(stream); throw e; }
    }
}
