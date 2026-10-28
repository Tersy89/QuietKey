package app.quietkey;

import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;

public final class InboxTest {
    private static MessageRecord record(String id,String group,String sender,String recipient,String name,long created,boolean outgoing){
        return new MessageRecord(id,group,sender,recipient,"file",UUID.randomUUID()+".enigma",name,created,0,outgoing);
    }
    @Test public void chatsGroupByGroupAndOtherPersonNewestFirst(){
        List<MessageRecord> history=Arrays.asList(
            record("1","g1","me","bob","a.png",100,true),
            record("2","g1","bob","me",Inbox.TEXT_NAME,300,false),
            record("3","g2","me","bob","b.pdf",200,true),
            record("4","g1","me","carol","c.txt",50,true));
        List<Inbox.Chat> chats=Inbox.chats(history);
        assertEquals(3,chats.size());
        assertEquals("g1",chats.get(0).group);assertEquals("bob",chats.get(0).member);assertEquals(2,chats.get(0).items.size());
        assertEquals("2",chats.get(0).last().id);assertEquals("1",chats.get(0).items.get(0).id);
        assertEquals("g2",chats.get(1).group);assertEquals("carol",chats.get(2).member);
        assertEquals(Arrays.asList("1","2"),ids(Inbox.thread(history,"g1","bob")));
        assertTrue(Inbox.thread(history,"g2","carol").isEmpty());
    }
    private static List<String> ids(List<MessageRecord> records){List<String> out=new ArrayList<>();for(MessageRecord r:records)out.add(r.id);return out;}
    @Test public void labelsAndPreviewLimits(){
        assertEquals("Message",Inbox.label(record("1","g","a","b",Inbox.TEXT_NAME,1,false)));
        assertEquals("holiday.jpg",Inbox.label(record("1","g","a","b","holiday.jpg",1,false)));
        assertEquals("Message (sent as text)",Inbox.label(new MessageRecord("1","g","a","b","text","QKD3:x","Private text",1,0,false)));
        assertEquals(Inbox.IMAGE,Inbox.preview("image/jpeg","x.jpg",1000));
        assertEquals(Inbox.OTHER,Inbox.preview("image/jpeg","x.jpg",Inbox.IMAGE_PREVIEW+1));
        assertEquals(Inbox.OTHER,Inbox.preview("image/svg+xml","x.svg",1000));
        assertEquals(Inbox.TEXT,Inbox.preview("text/plain","notes",10));
        assertEquals(Inbox.TEXT,Inbox.preview("application/octet-stream","notes.TXT",10));
        assertEquals(Inbox.OTHER,Inbox.preview("text/plain","big.txt",Inbox.TEXT_PREVIEW+1));
        assertEquals(Inbox.OTHER,Inbox.preview("application/pdf","x.pdf",10));
        assertEquals(Inbox.OTHER,Inbox.preview(null,null,10));
        assertTrue(Inbox.typed(Inbox.TEXT_NAME,Inbox.TEXT_MIME));assertFalse(Inbox.typed("message.txt","application/octet-stream"));
    }
    /** A typed message is sent as a .enigma file; the recipient restores exactly the text, and its name/type mark it as a message. */
    @Test public void typedMessageTravelsAsEnigmaFile()throws Exception{
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair a=generator.generateKeyPair(),b=generator.generateKeyPair();
        Protocol.Group group=Protocol.create("Chat",Protocol.random(32),a.getPublic());group.members.put(Protocol.fingerprint(b.getPublic()),Protocol.b64(b.getPublic().getEncoded()));
        Protocol.Group receiver=Protocol.accept(Protocol.invite(group,b.getPublic(),a.getPublic(),a.getPrivate()),b.getPublic(),b.getPrivate());
        String text="Héllo 👋\nsee you at 6";File file=File.createTempFile("enigma-message-",".enigma");
        try{
            try(OutputStream out=new FileOutputStream(file)){FileProtocol.encrypt(group,Protocol.fingerprint(b.getPublic()),new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),out,Inbox.TEXT_NAME,Inbox.TEXT_MIME,a.getPublic(),a.getPrivate());}
            assertEquals(receiver.id,FileProtocol.groupId(file));
            try(FileProtocol.Verified v=FileProtocol.inspect(receiver,file,b.getPublic(),b.getPrivate())){
                assertTrue(Inbox.typed(v.name,v.mime));assertEquals(Inbox.TEXT,Inbox.preview(v.mime,v.name,v.size));assertEquals(Protocol.fingerprint(a.getPublic()),v.sender);
                ByteArrayOutputStream out=new ByteArrayOutputStream();FileProtocol.restore(v,out);assertEquals(text,new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
        }finally{file.delete();}
    }
}
