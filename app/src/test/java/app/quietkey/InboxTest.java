package app.quietkey;

import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;

public final class InboxTest {
    private static MessageRecord record(String id,String group,String sender,String recipient,String name,long created,boolean outgoing){
        return new MessageRecord(id,group,sender,recipient,"file",UUID.randomUUID()+".plain",name,Inbox.TEXT_NAME.equals(name)?Inbox.TEXT_MIME:"application/pdf","",created,0,outgoing,false);
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
        assertEquals("Message",Inbox.label(new MessageRecord("1","g","a","b","file",UUID.randomUUID()+".plain",Inbox.TEXT_NAME,Inbox.TEXT_MIME,"",1,0,false,false)));
        assertEquals("Message (view once)",Inbox.label(new MessageRecord("2","g","a","b","once","",Inbox.TEXT_NAME,Inbox.TEXT_MIME,"",1,0,false,false)));
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
    /** A typed message is sent as a .enigma package; the recipient gets exactly the text, and its name/type mark it as a message. */
    @Test public void typedMessageTravelsAsEnigmaPackage()throws Exception{
        TestDevice a=new TestDevice(),b=new TestDevice();long now=System.currentTimeMillis();
        Protocol.Group group=JoinProtocol.create("Chat","A",a.id,a.pools,TestDevice.E);
        String request=JoinProtocol.request(b.id,b.pools,TestDevice.E,"B");Protocol.Group receiver=JoinProtocol.accept(JoinProtocol.invite(group,JoinProtocol.parseRequest(request),a.id,a.pools,TestDevice.E),request,b.id,b.pools);
        String text="Héllo 👋\nsee you at 6";
        byte[] packet=SealedProtocol.seal(group,a.id,a.pools.backup(a.id,TestDevice.E,now),Collections.singletonList(new SealedProtocol.Recipient(b.id.publicKey,a.pools.theirBackup(b.fp),a.pools.take(b.fp),null)),
            new SealedProtocol.Content(Inbox.TEXT_NAME,Inbox.TEXT_MIME,text.getBytes(StandardCharsets.UTF_8),0,false),JoinProtocol.carrier(group),TestDevice.E);
        assertEquals(receiver.id,SealedProtocol.groupOf(SealedProtocol.fromText(SealedProtocol.toText(packet).replaceAll("(.{60})","$1\n"))));
        SealedProtocol.Opened o=SealedProtocol.open(receiver,b.id,b.pools,packet);
        assertTrue(Inbox.typed(o.name,o.mime));assertEquals(Inbox.TEXT,Inbox.preview(o.mime,o.name,o.body.length));assertEquals(a.fp,o.sender);assertEquals(text,new String(o.body,StandardCharsets.UTF_8));
    }
}
