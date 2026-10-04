package app.quietkey;

import android.content.*;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Opt-in disposable emulator only: password replacement also resets the biometric alias. */
@RunWith(AndroidJUnit4.class)
public final class PasswordVaultTest {
    @Test public void deviceBoundPasswordVaultAndRewrap()throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("enigmaUiTest")));
        Assume.assumeTrue(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk_gphone"));
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory=new File(target.getCacheDir(),"password-test-"+UUID.randomUUID());assertTrue(directory.mkdir());
        Context context=new ContextWrapper(target){@Override public File getFilesDir(){return directory;}};
        PasswordLock lock=new PasswordLock(context);char[] first="First emulator password".toCharArray(),next="Next emulator password".toCharArray();byte[] master=null;
        try {
            master=lock.create(first);assertEquals(32,master.length);assertTrue(lock.configured());
            assertThrows(SecurityException.class,()->new Vault(context));
            Protocol.Group group;
            try(Vault vault=new Vault(context,master)){vault.deviceName="Protected device";group=Protocol.create("Protected group",Protocol.random(32),vault.publicKey());PairingProtocol.signRoster(group,vault.publicKey(),vault.privateKey());vault.groups.add(group);vault.save();assertNull(vault.privateKey().getEncoded());}
            File stored=new File(directory,"groups.qkv");byte[] ciphertext=Files.readAllBytes(stored.toPath());assertEquals(2,ciphertext[0]);assertFalse(new String(ciphertext,java.nio.charset.StandardCharsets.ISO_8859_1).contains("Protected group"));
            assertThrows(Exception.class,()->lock.unlock("Wrong emulator password".toCharArray()));
            byte[] unlocked=lock.unlock(first);assertArrayEquals(master,unlocked);
            Vault reopened=new Vault(context,unlocked);assertEquals("Protected device",reopened.deviceName);assertEquals(group.roster,reopened.groups.get(0).roster);byte[] loadedSeed=reopened.groups.get(0).seed;reopened.close();assertArrayEquals(new byte[32],loadedSeed);assertThrows(IllegalStateException.class,reopened::privateKey);assertThrows(IllegalStateException.class,reopened::save);
            lock.replace(next,unlocked);assertThrows(Exception.class,()->lock.unlock(first));byte[] changed=lock.unlock(next);assertArrayEquals(master,changed);
            try(Vault preserved=new Vault(context,changed)){assertEquals(1,preserved.groups.size());assertEquals("Protected device",preserved.deviceName);}
            byte[] wrong=changed.clone();wrong[0]^=1;assertThrows(Exception.class,()->new Vault(context,wrong));Arrays.fill(wrong,(byte)0);
            ciphertext[ciphertext.length-1]^=1;Files.write(stored.toPath(),ciphertext);assertThrows(Exception.class,()->new Vault(context,changed));
            File passwordFile=new File(directory,"password.qkl");byte[] box=Files.readAllBytes(passwordFile.toPath());box[box.length-1]^=1;Files.write(passwordFile.toPath(),box);assertThrows(Exception.class,()->lock.unlock(next));
            Arrays.fill(unlocked,(byte)0);Arrays.fill(changed,(byte)0);
        } finally {if(master!=null)Arrays.fill(master,(byte)0);Arrays.fill(first,'\0');Arrays.fill(next,'\0');File[] files=directory.listFiles();if(files!=null)for(File file:files)assertTrue(file.delete());assertTrue(directory.delete());}
    }
}
