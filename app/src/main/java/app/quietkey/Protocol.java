package app.quietkey;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Versioned, bounded binary protocol. No Android dependency; tests run on a JVM. */
public final class Protocol {
    private Protocol() {}
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String IDENTITY = "QKI1:", INVITE = "QKG1:", MESSAGE = "QKM1:";
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT);
    public static final int MAX_TEXT_BYTES = 16000;
    public static final class Group {
        public final String id, owner;
        public String name;
        public final byte[] seed;
        public final long expires;
        public final LinkedHashMap<String, String> members = new LinkedHashMap<>();
        public final LinkedHashMap<String, String> memberNames = new LinkedHashMap<>();
        public final HashSet<String> seen = new HashSet<>();
        public Group(String id, String name, byte[] seed, long expires, String owner) {
            if (seed.length != 32 || name.trim().isEmpty() || name.length() > 60) throw new IllegalArgumentException("Invalid group");
            UUID.fromString(id);
            if (owner == null || un64(owner).length != 32) throw new IllegalArgumentException("Invalid owner");
            this.id = id; this.name = name; this.seed = seed.clone(); this.expires = expires; this.owner = owner;
        }
    }
    public static final class Opened {
        public final String text, sender, messageId;
        Opened(String text, String sender, String messageId) { this.text = text; this.sender = sender; this.messageId = messageId; }
    }
    public static byte[] random(int count) { byte[] b = new byte[count]; RANDOM.nextBytes(b); return b; }
    public static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static byte[] un64(String s) { return Base64.getUrlDecoder().decode(s); }
    public static PublicKey publicKey(String encoded) throws Exception {
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(un64(encoded)));
        if (!(key instanceof RSAPublicKey) || ((RSAPublicKey) key).getModulus().bitLength() != 2048) throw new SecurityException("Unsupported identity key");
        return key;
    }
    public static String fingerprint(PublicKey key) throws Exception { return b64(MessageDigest.getInstance("SHA-256").digest(key.getEncoded())); }
    public static String identity(PublicKey key) { return IDENTITY + b64(key.getEncoded()); }
    public static PublicKey parseIdentity(String qr) throws Exception {
        if (!qr.startsWith(IDENTITY) || qr.length() > 600) throw new SecurityException("Not an identity QR");
        return publicKey(qr.substring(IDENTITY.length()));
    }
    public static Group create(String name, byte[] cameraDigest, PublicKey ownKey) throws Exception {
        if (cameraDigest == null || cameraDigest.length != 32) throw new IllegalArgumentException("Capture camera frames first");
        // OS randomness is the security foundation. Camera samples are additional input.
        byte[] os = random(32);
        byte[] seed = hkdf(os, cameraDigest, "QuietKey/group-seed/v1".getBytes(StandardCharsets.UTF_8));
        Arrays.fill(os, (byte) 0);
        long expires = Instant.now().atZone(ZoneOffset.UTC).plusYears(10).toInstant().toEpochMilli();
        Group g = new Group(UUID.randomUUID().toString(), name.trim(), seed, expires, fingerprint(ownKey));
        g.members.put(fingerprint(ownKey), b64(ownKey.getEncoded()));
        Arrays.fill(seed, (byte) 0);
        return g;
    }
    public static String invite(Group g, PublicKey recipient, PublicKey sender, PrivateKey privateKey) throws Exception {
        live(g);
        if (!g.owner.equals(fingerprint(sender))) throw new SecurityException("Only the group creator can issue invitations");
        byte[] wrapped = rsa(Cipher.ENCRYPT_MODE, recipient, g.seed);
        byte[] body = pack(out -> {
            out.writeUTF("invite-v1"); out.writeUTF(g.id); out.writeUTF(g.name); out.writeLong(g.expires);
            out.writeUTF(fingerprint(recipient)); blob(out, sender.getEncoded()); blob(out, wrapped);
        });
        return INVITE + b64(signed(body, privateKey));
    }
    public static Group accept(String qr, PublicKey ownKey, PrivateKey privateKey) throws Exception {
        DataInputStream outer = input(decode(qr, INVITE, 3000));
        byte[] body = blob(outer, 2000), sig = blob(outer, 256); end(outer);
        DataInputStream in = input(body);
        if (!in.readUTF().equals("invite-v1")) throw new SecurityException("Unsupported invitation");
        String id = in.readUTF(), name = in.readUTF(); long expires = in.readLong();
        if (!in.readUTF().equals(fingerprint(ownKey))) throw new SecurityException("This invitation belongs to another device");
        PublicKey sender = publicKey(b64(blob(in, 400))); byte[] wrapped = blob(in, 256); end(in);
        verify(sender, body, sig);
        byte[] seed = rsa(Cipher.DECRYPT_MODE, privateKey, wrapped);
        Group g = new Group(id, name, seed, expires, fingerprint(sender)); Arrays.fill(seed, (byte) 0);
        live(g);
        long max = Instant.now().atZone(ZoneOffset.UTC).plusYears(10).plusDays(2).toInstant().toEpochMilli();
        if (expires > max) throw new SecurityException("Invalid expiry");
        g.members.put(fingerprint(sender), b64(sender.getEncoded()));
        g.members.put(fingerprint(ownKey), b64(ownKey.getEncoded()));
        return g;
    }
    public static String encrypt(Group g, String text, PublicKey sender, PrivateKey privateKey) throws Exception {
        live(g);
        String senderId = fingerprint(sender);
        if (!g.members.containsKey(senderId)) throw new SecurityException("Sender is not a trusted member");
        byte[] clear = text.getBytes(StandardCharsets.UTF_8);
        if (clear.length == 0 || clear.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("Use 1–16000 UTF-8 bytes");
        byte[] id = random(16), nonce = random(12);
        byte[] header = pack(out -> { out.writeUTF("message-v1"); out.writeUTF(g.id); out.writeUTF(senderId); blob(out, id); blob(out, nonce); });
        byte[] key = messageKey(g.seed, id, header);
        byte[] cipher;
        try { cipher = aes(Cipher.ENCRYPT_MODE, key, nonce, header, clear); }
        finally { Arrays.fill(key, (byte) 0); Arrays.fill(clear, (byte) 0); }
        byte[] body = pack(out -> { blob(out, header); blob(out, cipher); });
        return MESSAGE + b64(signed(body, privateKey));
    }
    public static Opened decrypt(Group g, String wire) throws Exception {
        live(g);
        DataInputStream outer = input(decode(wire, MESSAGE, 40000));
        byte[] body = blob(outer, 18000), sig = blob(outer, 256); end(outer);
        DataInputStream in = input(body);
        byte[] header = blob(in, 300), cipher = blob(in, MAX_TEXT_BYTES + 16); end(in);
        DataInputStream h = input(header);
        if (!h.readUTF().equals("message-v1") || !h.readUTF().equals(g.id)) throw new SecurityException("Message belongs to a different group");
        String sender = h.readUTF(); byte[] id = blob(h, 16), nonce = blob(h, 12); end(h);
        if (id.length != 16 || nonce.length != 12) throw new SecurityException("Invalid message fields");
        String trusted = g.members.get(sender);
        if (trusted == null) throw new SecurityException("Scan and verify this sender's identity for this group first");
        verify(publicKey(trusted), body, sig);
        String messageId = b64(id);
        if (g.seen.contains(messageId)) throw new SecurityException("This message has already been decrypted");
        if (g.seen.size() >= 100000) throw new SecurityException("Replay log full. Create a new group");
        byte[] key = messageKey(g.seed, id, header);
        byte[] clear;
        try { clear = aes(Cipher.DECRYPT_MODE, key, nonce, header, cipher); }
        finally { Arrays.fill(key, (byte) 0); }
        String text = new String(clear, StandardCharsets.UTF_8); Arrays.fill(clear, (byte) 0);
        return new Opened(text, sender, messageId); // caller must persist seen ID before displaying cleartext
    }
    private static void live(Group g) { if (System.currentTimeMillis() >= g.expires) throw new SecurityException("Group expired. Create and pair a new group"); }
    private static byte[] messageKey(byte[] seed, byte[] id, byte[] header) throws Exception {
        return hkdf(seed, id, MessageDigest.getInstance("SHA-256").digest(header));
    }
    public static byte[] hkdf(byte[] input, byte[] salt, byte[] info) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(salt, "HmacSHA256")); byte[] prk = mac.doFinal(input);
        mac.init(new SecretKeySpec(prk, "HmacSHA256")); mac.update(info); mac.update((byte) 1);
        byte[] result = mac.doFinal(); Arrays.fill(prk, (byte) 0); return result;
    }
    public static byte[] aes(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] value) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce)); c.updateAAD(aad); return c.doFinal(value);
    }
    static byte[] rsa(int mode, Key key, byte[] value) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding"); c.init(mode, key, OAEP); return c.doFinal(value);
    }
    private static byte[] signed(byte[] body, PrivateKey key) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA"); s.initSign(key); s.update(body); byte[] sig = s.sign();
        return pack(out -> { blob(out, body); blob(out, sig); });
    }
    private static void verify(PublicKey key, byte[] body, byte[] sig) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA"); s.initVerify(key); s.update(body);
        if (!s.verify(sig)) throw new SecurityException("Signature invalid; message or QR was changed");
    }
    private interface Writer { void write(DataOutputStream out) throws Exception; }
    private static byte[] pack(Writer writer) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(b); writer.write(out); out.flush(); return b.toByteArray();
    }
    private static void blob(DataOutputStream out, byte[] b) throws Exception { out.writeInt(b.length); out.write(b); }
    private static byte[] blob(DataInputStream in, int max) throws Exception {
        int size = in.readInt(); if (size < 0 || size > max || size > in.available()) throw new SecurityException("Invalid packet size");
        byte[] b = new byte[size]; in.readFully(b); return b;
    }
    private static DataInputStream input(byte[] bytes) { return new DataInputStream(new ByteArrayInputStream(bytes)); }
    private static void end(DataInputStream in) throws Exception { if (in.available() != 0) throw new SecurityException("Unexpected trailing data"); }
    private static byte[] decode(String value, String prefix, int max) {
        value = value.trim(); if (!value.startsWith(prefix) || value.length() > max) throw new SecurityException("Invalid QuietKey packet");
        return un64(value.substring(prefix.length()));
    }

    /** Desktop self-test: java app/src/main/java/app/quietkey/Protocol.java */
    public static void main(String[] args) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA"); gen.initialize(2048);
        KeyPair a = gen.generateKeyPair(), b = gen.generateKeyPair(), c = gen.generateKeyPair();
        Group ga = create("Friends", random(32), a.getPublic());
        ga.members.put(fingerprint(b.getPublic()), b64(b.getPublic().getEncoded()));
        String invitation = invite(ga, b.getPublic(), a.getPublic(), a.getPrivate());
        Group gb = accept(invitation, b.getPublic(), b.getPrivate());
        check(Arrays.equals(ga.seed, gb.seed), "offline wrapped-key pairing");
        check(ga.owner.equals(gb.owner), "creator identity preserved during pairing");
        reject(() -> invite(gb, c.getPublic(), b.getPublic(), b.getPrivate()), "non-owner invitation rejection");
        String impersonated = invite(ga, b.getPublic(), a.getPublic(), c.getPrivate());
        reject(() -> accept(impersonated, b.getPublic(), b.getPrivate()), "creator private key required");
        byte[] tamperedInvite = un64(invitation.substring(INVITE.length())); tamperedInvite[tamperedInvite.length - 1] ^= 1;
        reject(() -> accept(INVITE + b64(tamperedInvite), b.getPublic(), b.getPrivate()), "invitation tampering rejection");
        Group otherGroup = create("Second group", random(32), a.getPublic());
        check(!Arrays.equals(ga.seed, otherGroup.seed) && !ga.id.equals(otherGroup.id), "independent multi-group keys");
        check(parseIdentity(identity(b.getPublic())).equals(b.getPublic()), "identity QR round trip");
        reject(() -> accept(invitation, c.getPublic(), c.getPrivate()), "wrong invitation recipient");
        String wire = encrypt(ga, "Hello B 👋\nPrivate message", a.getPublic(), a.getPrivate());
        Opened opened = decrypt(gb, wire); check(opened.text.equals("Hello B 👋\nPrivate message"), "Unicode message round trip");
        gb.seen.add(opened.messageId); reject(() -> decrypt(gb, wire), "replay rejection");
        String reply = encrypt(gb, "Reply", b.getPublic(), b.getPrivate());
        check(decrypt(ga, reply).text.equals("Reply"), "bidirectional messaging");
        byte[] changed = un64(reply.substring(MESSAGE.length())); changed[changed.length - 1] ^= 1;
        reject(() -> decrypt(ga, MESSAGE + b64(changed)), "signature tampering");
        reject(() -> decrypt(create("Other", random(32), a.getPublic()), reply), "cross-group rejection");
        Group corrupted = new Group(ga.id, ga.name, random(32), ga.expires, ga.owner); corrupted.members.putAll(ga.members);
        reject(() -> decrypt(corrupted, reply), "wrong group seed / GCM authentication");
        Group unknown = new Group(ga.id, ga.name, ga.seed, ga.expires, ga.owner);
        reject(() -> decrypt(unknown, reply), "untrusted sender rejection");
        Group expired = new Group(ga.id, ga.name, ga.seed, 1, ga.owner); expired.members.putAll(ga.members);
        reject(() -> encrypt(expired, "test", a.getPublic(), a.getPrivate()), "expired group rejection");
        reject(() -> encrypt(ga, "x".repeat(MAX_TEXT_BYTES + 1), a.getPublic(), a.getPrivate()), "message size bound");
        reject(() -> decrypt(ga, "QKM1:AAAA"), "truncated packet rejection");
        reject(() -> decrypt(ga, wire + "A"), "trailing data rejection");
        check(!encrypt(ga, "Same", a.getPublic(), a.getPrivate()).equals(encrypt(ga, "Same", a.getPublic(), a.getPrivate())), "fresh per-message randomness");
        // RFC 5869 test case 1, first 32 bytes of OKM.
        byte[] ikm = new byte[22]; Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = new byte[13], info = new byte[10]; for (int i=0;i<13;i++) salt[i]=(byte)i; for(int i=0;i<10;i++) info[i]=(byte)(0xf0+i);
        check(hex(hkdf(ikm,salt,info)).equals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"), "HKDF RFC 5869 known-answer vector");
        System.out.println("All 21 protocol checks passed.");
    }
    private interface Task { void run() throws Exception; }
    private static void reject(Task t, String name) throws Exception { try { t.run(); } catch (Exception expected) { System.out.println("PASS " + name); return; } throw new AssertionError(name); }
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); System.out.println("PASS " + name); }
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for(byte v:b) s.append(String.format("%02x",v&255)); return s.toString(); }
}
