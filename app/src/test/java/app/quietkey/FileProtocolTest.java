package app.quietkey;

import org.junit.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;

public final class FileProtocolTest {
    private static KeyPair a,b,c;
    private Protocol.Group group,receiver;
    private File file;
    @BeforeClass public static void identities() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        a=generator.generateKeyPair(); b=generator.generateKeyPair(); c=generator.generateKeyPair();
    }
    @Before public void setup() throws Exception {
        group=Protocol.create("Files",Protocol.random(32),a.getPublic()); group.members.put(Protocol.fingerprint(b.getPublic()),Protocol.b64(b.getPublic().getEncoded()));
        receiver=Protocol.accept(Protocol.invite(group,b.getPublic(),a.getPublic(),a.getPrivate()),b.getPublic(),b.getPrivate());
        file=File.createTempFile("enigma-file-", ".enigma");
    }
    @After public void cleanup() { file.delete(); }
    private void encrypt(byte[] clear,String name,String mime) throws Exception {
        try(OutputStream out=new FileOutputStream(file)) { FileProtocol.encrypt(group,Protocol.fingerprint(b.getPublic()),new ByteArrayInputStream(clear),out,name,mime,a.getPublic(),a.getPrivate()); }
    }
    @Test public void arbitraryBinaryAndEncryptedMetadataRoundTrip() throws Exception {
        byte[] original=Protocol.random(200000); encrypt(original,"私密 photo.png","image/png");
        assertFalse(new String(Files.readAllBytes(file.toPath()),StandardCharsets.ISO_8859_1).contains("photo.png"));
        try(FileProtocol.Verified verified=FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate())) {
            assertEquals("私密 photo.png",verified.name); assertEquals("image/png",verified.mime); assertEquals(original.length,verified.size);
            receiver.seen.add(verified.messageId); // restore remains possible after the durable replay commit
            ByteArrayOutputStream out=new ByteArrayOutputStream(); FileProtocol.restore(verified,out); assertArrayEquals(original,out.toByteArray());
        }
    }
    @Test public void emptyFileRoundTrip() throws Exception {
        encrypt(new byte[0],"empty.bin","application/octet-stream");
        try(FileProtocol.Verified verified=FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate())) { ByteArrayOutputStream out=new ByteArrayOutputStream(); FileProtocol.restore(verified,out); assertEquals(0,out.size()); }
    }
    @Test public void exactChunkBoundaryRoundTrip() throws Exception {
        byte[] original=Protocol.random(65536); encrypt(original,"a.pdf","application/pdf");
        try(FileProtocol.Verified verified=FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate())) { ByteArrayOutputStream out=new ByteArrayOutputStream(); FileProtocol.restore(verified,out); assertArrayEquals(original,out.toByteArray()); }
    }
    @Test public void previouslyOpenedFileCanBeExplicitlyRestoredAgain() throws Exception {
        encrypt(new byte[]{1},"a","application/octet-stream");
        try(FileProtocol.Verified verified=FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate())) { receiver.seen.add(verified.messageId); }
        try(FileProtocol.Verified verified=FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate())){assertTrue(verified.previouslyOpened);ByteArrayOutputStream out=new ByteArrayOutputStream();FileProtocol.restore(verified,out);assertArrayEquals(new byte[]{1},out.toByteArray());}
    }
    @Test public void wrongGroupIsRejected() throws Exception {
        encrypt(new byte[]{1},"a","application/octet-stream");
        Protocol.Group other=Protocol.create("Other",Protocol.random(32),b.getPublic());
        assertThrows(SecurityException.class,()->FileProtocol.inspect(other,file,b.getPublic(),b.getPrivate()));
    }
    @Test public void unknownSenderIsRejected() throws Exception {
        encrypt(new byte[]{1},"a","application/octet-stream"); receiver.members.remove(group.owner);
        assertThrows(SecurityException.class,()->FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate()));
    }
    @Test public void groupSeedIsNotTheFileKey() throws Exception {
        encrypt(new byte[]{1},"a","application/octet-stream"); Arrays.fill(receiver.seed,(byte)0);
        try(FileProtocol.Verified verified=FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate())) { assertEquals(1,verified.size); }
    }
    @Test public void signatureTamperingIsRejected() throws Exception {
        encrypt(new byte[]{1},"a","application/octet-stream"); byte[] bytes=Files.readAllBytes(file.toPath()); bytes[bytes.length-1]^=1; Files.write(file.toPath(),bytes);
        assertThrows(SecurityException.class,()->FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate()));
    }
    @Test public void gcmFailureRejectedEvenWithValidSenderSignature() throws Exception {
        encrypt(Protocol.random(90000),"a","application/octet-stream"); byte[] bytes=Files.readAllBytes(file.toPath()); bytes[bytes.length-257]^=1;
        Signature signature=Signature.getInstance("SHA256withRSA"); signature.initSign(a.getPrivate()); signature.update(bytes,0,bytes.length-256);
        System.arraycopy(signature.sign(),0,bytes,bytes.length-256,256); Files.write(file.toPath(),bytes);
        assertThrows(javax.crypto.AEADBadTagException.class,()->FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate()));
    }
    @Test public void truncationAndTrailingDataRejected() throws Exception {
        encrypt(new byte[]{1},"a","application/octet-stream"); byte[] bytes=Files.readAllBytes(file.toPath());
        Files.write(file.toPath(),Arrays.copyOf(bytes,bytes.length-1)); assertThrows(Exception.class,()->FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate()));
        Files.write(file.toPath(),Arrays.copyOf(bytes,bytes.length+1)); assertThrows(Exception.class,()->FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate()));
    }
    @Test public void maliciousLengthRejectedWithoutLargeAllocation() throws Exception {
        encrypt(new byte[]{1},"a","application/octet-stream"); byte[] bytes=Files.readAllBytes(file.toPath()); Arrays.fill(bytes,4,8,(byte)0x7f); Files.write(file.toPath(),bytes);
        assertThrows(Exception.class,()->FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate()));
    }
    @Test public void fileSizeLimitEnforcedForUnknownLengthStreams() throws Exception {
        InputStream endless=new InputStream() {
            long remaining=FileProtocol.MAX_FILE_BYTES+1;
            @Override public int read() { if(remaining--<=0)return -1; return 42; }
            @Override public int read(byte[] bytes,int offset,int length) { if(remaining==0)return -1; int count=(int)Math.min(remaining,length); Arrays.fill(bytes,offset,offset+count,(byte)42); remaining-=count; return count; }
        };
        assertThrows(IOException.class,()->FileProtocol.encrypt(group,Protocol.fingerprint(b.getPublic()),endless,OutputStream.nullOutputStream(),"a","application/octet-stream",a.getPublic(),a.getPrivate()));
    }
    @Test public void pathNamesAreSanitized() throws Exception {
        encrypt(new byte[]{1},"../../private\\photo.png","bad mime");
        try(FileProtocol.Verified verified=FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate())) { assertEquals("photo.png",verified.name); assertEquals("application/octet-stream",verified.mime); }
    }
    @Test public void freshRandomness() throws Exception {
        encrypt(new byte[]{1},"a","application/octet-stream"); byte[] first=Files.readAllBytes(file.toPath());
        encrypt(new byte[]{1},"a","application/octet-stream"); assertFalse(Arrays.equals(first,Files.readAllBytes(file.toPath())));
    }
    @Test public void closedVerificationCannotRestore() throws Exception {
        encrypt(new byte[]{1},"a","application/octet-stream"); FileProtocol.Verified verified=FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate()); verified.close();
        assertThrows(IllegalStateException.class,()->FileProtocol.restore(verified,new ByteArrayOutputStream()));
    }
    @Test public void anotherGroupMemberCannotReadRecipientFile()throws Exception {
        group.members.put(Protocol.fingerprint(c.getPublic()),Protocol.b64(c.getPublic().getEncoded()));
        Protocol.Group third=Protocol.accept(Protocol.invite(group,c.getPublic(),a.getPublic(),a.getPrivate()),c.getPublic(),c.getPrivate());
        third.members.put(Protocol.fingerprint(b.getPublic()),Protocol.b64(b.getPublic().getEncoded()));
        encrypt(new byte[]{1},"a","application/octet-stream");assertArrayEquals(group.seed,third.seed);
        assertThrows(SecurityException.class,()->FileProtocol.inspect(third,file,c.getPublic(),c.getPrivate()));
        assertThrows(Exception.class,()->FileProtocol.inspect(third,file,b.getPublic(),c.getPrivate()));
    }
    @Test public void unregisteredRecipientCannotBeSelected()throws Exception {
        assertThrows(SecurityException.class,()->FileProtocol.encrypt(group,Protocol.b64(Protocol.random(32)),new ByteArrayInputStream(new byte[]{1}),new ByteArrayOutputStream(),"a","application/octet-stream",a.getPublic(),a.getPrivate()));
    }
}
