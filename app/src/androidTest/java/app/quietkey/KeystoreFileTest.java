package app.quietkey;

import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class KeystoreFileTest {
    @Test public void signedFilesAndPrivateKeystoreUnwrap()throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory=new File(target.getCacheDir(),"file-test-"+UUID.randomUUID());assertTrue(directory.mkdir());
        Context isolated=new ContextWrapper(target){@Override public File getFilesDir(){return directory;}};
        File packet=new File(directory,"sample.enigma");
        try {
            Vault vault=new Vault(isolated);KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);KeyPair remote=gen.generateKeyPair();
            Protocol.Group remoteGroup=Protocol.create("File owner",Protocol.random(32),remote.getPublic());
            remoteGroup.members.put(Protocol.fingerprint(vault.publicKey()),Protocol.b64(vault.publicKey().getEncoded()));
            Protocol.Group local=Protocol.accept(Protocol.invite(remoteGroup,vault.publicKey(),remote.getPublic(),remote.getPrivate()),vault.publicKey(),vault.privateKey());
            byte[] original=Protocol.random(150000);
            try(OutputStream out=new FileOutputStream(packet)){FileProtocol.encrypt(remoteGroup,Protocol.fingerprint(vault.publicKey()),new ByteArrayInputStream(original),out,"photo.png","image/png",remote.getPublic(),remote.getPrivate());}
            try(FileProtocol.Verified verified=FileProtocol.inspect(local,packet,vault.publicKey(),vault.privateKey())) {
                local.seen.add(verified.messageId);vault.groups.add(local);vault.deviceName="File test";vault.setupCompleted=true;local.memberNames.put(local.owner,"Remote");vault.save();
                ByteArrayOutputStream out=new ByteArrayOutputStream();FileProtocol.restore(verified,out);assertArrayEquals(original,out.toByteArray());
            }
            Vault reload=new Vault(isolated);assertTrue(reload.setupCompleted);assertEquals("File test",reload.deviceName);assertEquals("Remote",reload.groups.get(0).memberNames.get(local.owner));
            try(FileProtocol.Verified verified=FileProtocol.inspect(reload.groups.get(0),packet,vault.publicKey(),vault.privateKey())){assertTrue(verified.previouslyOpened);}
            try(OutputStream out=new FileOutputStream(packet)){FileProtocol.encrypt(local,Protocol.fingerprint(remote.getPublic()),new ByteArrayInputStream(original),out,"photo.png","image/png",vault.publicKey(),vault.privateKey());}
            try(FileProtocol.Verified verified=FileProtocol.inspect(remoteGroup,packet,remote.getPublic(),remote.getPrivate())){ByteArrayOutputStream out=new ByteArrayOutputStream();FileProtocol.restore(verified,out);assertArrayEquals(original,out.toByteArray());}
        } finally {
            for(String name:new String[]{"sample.enigma","groups.qkv","groups.qkv.bak","groups.qkv.new"}){File file=new File(directory,name);if(file.exists())assertTrue(file.delete());}
            assertTrue(directory.delete());
        }
    }
}
