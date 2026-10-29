package app.quietkey;

import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Group model and shared primitives. No Android dependency; tests run on a JVM. */
public final class Protocol {
    private Protocol() {}
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final class Group {
        public final String id, owner;
        public String name;
        public final byte[] seed;
        public final long expires;
        public final LinkedHashMap<String, String> members = new LinkedHashMap<>();
        public final LinkedHashMap<String, String> memberNames = new LinkedHashMap<>();
        public final HashSet<String> seen = new HashSet<>();
        public long rosterRevision;
        public String roster="";
        public final HashSet<String> blocked=new HashSet<>();
        public final LinkedHashMap<String,String> memberStatus=new LinkedHashMap<>();
        public Group(String id, String name, byte[] seed, long expires, String owner) {
            if (seed.length != 32 || name.trim().isEmpty() || name.length() > 60) throw new IllegalArgumentException("Invalid group");
            UUID.fromString(id);
            if (owner == null || un64(owner).length != 32) throw new IllegalArgumentException("Invalid owner");
            this.id = id; this.name = name; this.seed = seed.clone(); this.expires = expires; this.owner = owner;
        }
    }
    public static byte[] random(int count) { byte[] b = new byte[count]; RANDOM.nextBytes(b); return b; }
    public static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static byte[] un64(String s) { return Base64.getUrlDecoder().decode(s); }
    static void live(Group g) { if (System.currentTimeMillis() >= g.expires) throw new SecurityException("Group expired. Create and pair a new group"); }
    public static byte[] hkdf(byte[] input, byte[] salt, byte[] info) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(salt, "HmacSHA256")); byte[] prk = mac.doFinal(input);
        mac.init(new SecretKeySpec(prk, "HmacSHA256")); mac.update(info); mac.update((byte) 1);
        byte[] result = mac.doFinal(); Arrays.fill(prk, (byte) 0); return result;
    }
    public static byte[] aes(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] value) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce)); c.updateAAD(aad); return c.doFinal(value);
    }
}
