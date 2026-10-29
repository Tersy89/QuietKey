package app.quietkey;

import android.content.*;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.security.KeyStore;
import java.util.*;
import static org.junit.Assert.*;

/**
 * Opt-in disposable emulator only (decision 12): wrong PINs are counted on disk, the fifth wipes,
 * the panic PIN wipes at once. Wiping deletes every Keystore key of the app, so never run on a phone.
 */
@RunWith(AndroidJUnit4.class)
public final class PinLockTest {
    private Context context;private File directory;
    @Before public void setup(){
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("enigmaUiTest")));
        Assume.assumeTrue(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk_gphone"));
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory=new File(target.getCacheDir(),"pin-test-"+UUID.randomUUID());assertTrue(directory.mkdirs());
        context=new ContextWrapper(target){@Override public File getFilesDir(){return directory;}};
    }
    private byte[] protect(String pin,String panic)throws Exception{
        byte[] secret=new PasswordLock(context).create("Emulator PIN passphrase".toCharArray()),master=Protocol.random(32);
        new PinLock(context).set(secret,pin,panic,master);
        try(Vault v=new Vault(context,master)){v.deviceName="PIN device";v.save();}
        return secret;
    }
    private static boolean deviceKeyExists()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);return ks.containsAlias("quietkey.vault.v1");}

    @Test public void wrongPinsAreCountedAndTheFifthWipes()throws Exception{
        byte[] secret=protect("2580","9999");PinLock lock=new PinLock(context);
        byte[] master=lock.unlock(secret,"2580");try(Vault v=new Vault(context,master)){assertEquals("PIN device",v.deviceName);}
        for(int left=4;left>=1;left--){PinLock.WrongPin wrong=assertThrows(PinLock.WrongPin.class,()->new PinLock(context).unlock(secret,"1111"));assertEquals(left,wrong.remaining);assertEquals(left,new PinLock(context).state().remaining());}
        assertThrows(PinLock.Wiped.class,()->lock.unlock(secret,"1111"));
        assertFalse(lock.configured());assertFalse(new PasswordLock(context).configured());assertFalse(new File(directory,"groups.qkv").exists());assertFalse(deviceKeyExists());
    }
    @Test public void correctPinResetsTheCountAndPanicWipes()throws Exception{
        byte[] secret=protect("2580","0000");PinLock lock=new PinLock(context);
        assertThrows(PinLock.WrongPin.class,()->lock.unlock(secret,"1111"));assertEquals(4,lock.state().remaining());
        Arrays.fill(lock.unlock(secret,"2580"),(byte)0);assertEquals(5,lock.state().remaining());
        assertThrows(Exception.class,()->lock.unlock(Protocol.random(32),"2580"));
        assertThrows(PinLock.Wiped.class,()->lock.unlock(secret,"0000"));
        assertFalse(lock.configured());assertFalse(deviceKeyExists());
    }
    @After public void cleanup(){if(directory==null)return;File[] files=directory.listFiles();if(files!=null)for(File f:files)f.delete();directory.delete();}
}
