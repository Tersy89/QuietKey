package app.quietkey;
import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.security.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

/** Run on an emulator/test device. Covers Android-provider behavior desktop tests cannot. */
@RunWith(AndroidJUnit4.class)
public final class KeystoreTest {
    @Test public void keystorePairingSigningAndVaultPersistence() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory=new File(target.getCacheDir(),"keystore-test-"+UUID.randomUUID());
        assertTrue(directory.mkdir());
        // Exercise the real Keystore with an isolated file, never the user's group vault.
        Context context=new ContextWrapper(target) {
            @Override public File getFilesDir() { return directory; }
        };
        Vault vault=new Vault(context);
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        KeyPair friend=generator.generateKeyPair();
        Protocol.Group group=Protocol.create("Instrumentation test",Protocol.random(32),vault.publicKey());
        group.members.put(Protocol.fingerprint(friend.getPublic()),Protocol.b64(friend.getPublic().getEncoded()));
        String invitation=Protocol.invite(group,friend.getPublic(),vault.publicKey(),vault.privateKey());
        Protocol.Group friendGroup=Protocol.accept(invitation,friend.getPublic(),friend.getPrivate());
        assertEquals("Android signing",Protocol.decrypt(friendGroup,Protocol.encrypt(group,"Android signing",vault.publicKey(),vault.privateKey())).text);
        Protocol.Group remote=Protocol.create("Remote owner",Protocol.random(32),friend.getPublic());
        Protocol.Group local=Protocol.accept(Protocol.invite(remote,vault.publicKey(),friend.getPublic(),friend.getPrivate()),vault.publicKey(),vault.privateKey());
        assertArrayEquals(remote.seed,local.seed); // explicit Android OAEP SHA-256/MGF1-SHA1 interop
        try {
            String wire=Protocol.encrypt(group,"Persisted replay test",vault.publicKey(),vault.privateKey());
            group.seen.add(Protocol.decrypt(group,wire).messageId); vault.groups.add(group); vault.save();
            Vault reloaded=new Vault(context);
            Protocol.Group saved=reloaded.groups.stream().filter(g->g.id.equals(group.id)).findFirst().orElseThrow(AssertionError::new);
            assertArrayEquals(group.seed,saved.seed);assertEquals(group.owner,saved.owner);assertEquals(group.seen,saved.seen);
            assertEquals(vault.publicKey(),reloaded.publicKey());assertNull(vault.privateKey().getEncoded());
            String direct=DirectProtocol.encrypt(group,Protocol.fingerprint(friend.getPublic()),"Private Android signing",vault.publicKey(),vault.privateKey());
            assertEquals("Private Android signing",DirectProtocol.decrypt(friendGroup,direct,friend.getPublic(),friend.getPrivate()).text);
            local.members.put(Protocol.fingerprint(friend.getPublic()),Protocol.b64(friend.getPublic().getEncoded()));
            remote.members.put(Protocol.fingerprint(vault.publicKey()),Protocol.b64(vault.publicKey().getEncoded()));
            String remoteDirect=DirectProtocol.encrypt(remote,Protocol.fingerprint(vault.publicKey()),"Android private unwrap",friend.getPublic(),friend.getPrivate());
            assertEquals("Android private unwrap",DirectProtocol.decrypt(local,remoteDirect,vault.publicKey(),vault.privateKey()).text);
            try { Protocol.decrypt(saved,wire); fail("Replay accepted after vault reload"); }
            catch (SecurityException expected) { assertTrue(expected.getMessage().contains("already")); }
            File stored=new File(directory,"groups.qkv");
            byte[] packet=Files.readAllBytes(stored.toPath());
            assertFalse(new String(packet,StandardCharsets.ISO_8859_1).contains(group.name));
            packet[packet.length-1]^=1;
            try (FileOutputStream stream=new FileOutputStream(stored)) { stream.write(packet); }
            try { new Vault(context); fail("Tampered vault accepted"); }
            catch (javax.crypto.AEADBadTagException expected) { /* authentication must fail */ }
        } finally {
            for (String name:new String[]{"groups.qkv","groups.qkv.bak","groups.qkv.new"}) {
                File testFile=new File(directory,name);
                if (testFile.exists()) assertTrue(testFile.delete());
            }
            assertTrue(directory.delete());
        }
    }
}
