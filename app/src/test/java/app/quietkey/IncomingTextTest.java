package app.quietkey;

import org.junit.*;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;

public final class IncomingTextTest {
    private static KeyPair a,b,c;
    @BeforeClass public static void keys()throws Exception{KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);a=gen.generateKeyPair();b=gen.generateKeyPair();c=gen.generateKeyPair();}
    private Protocol.Group joined(Protocol.Group owner)throws Exception{String request=PairingProtocol.request(b.getPublic(),b.getPrivate(),"B");return PairingProtocol.accept(PairingProtocol.invite(owner,PairingProtocol.parseRequest(request),a.getPublic(),a.getPrivate()),request,b.getPublic(),b.getPrivate());}
    @Test public void joinedRecipientOpensWrappedTextDespiteStarterGroupSelection()throws Exception {
        Protocol.Group owner=Protocol.create("Friends",Protocol.random(32),a.getPublic()),receiver=joined(owner),starter=Protocol.create("Starter group",Protocol.random(32),b.getPublic());
        String wire=DirectProtocol.encrypt(owner,Protocol.fingerprint(b.getPublic()),"Hello B 👋",a.getPublic(),a.getPrivate());
        assertThrows(SecurityException.class,()->DirectProtocol.decrypt(starter,wire,b.getPublic(),b.getPrivate()));
        String copied=IncomingText.normalize(" \n"+wire.replaceAll("(.{71})","$1\r\n")+"\t");assertEquals(wire,copied);
        Protocol.Group routed=IncomingText.group(Arrays.asList(starter,receiver),IncomingText.groupId(copied));assertSame(receiver,routed);
        DirectProtocol.Opened result=DirectProtocol.decrypt(routed,copied,b.getPublic(),b.getPrivate());assertEquals("Hello B 👋",result.text);
        routed.seen.add(result.messageId);assertThrows(SecurityException.class,()->DirectProtocol.decrypt(routed,copied,b.getPublic(),b.getPrivate()));
    }
    @Test public void routingDoesNotAuthorizeWrongRecipientOrTamperedSignature()throws Exception {
        Protocol.Group owner=Protocol.create("Friends",Protocol.random(32),a.getPublic()),receiver=joined(owner);
        owner.members.put(Protocol.fingerprint(c.getPublic()),Protocol.b64(c.getPublic().getEncoded()));
        PairingProtocol.signRoster(owner,a.getPublic(),a.getPrivate());
        String wire=DirectProtocol.encrypt(owner,Protocol.fingerprint(c.getPublic()),"Only C",a.getPublic(),a.getPrivate());
        Protocol.Group routed=IncomingText.group(Collections.singletonList(receiver),IncomingText.groupId(wire));
        assertThrows(SecurityException.class,()->DirectProtocol.decrypt(routed,wire,b.getPublic(),b.getPrivate()));
        String forB=DirectProtocol.encrypt(owner,Protocol.fingerprint(b.getPublic()),"Only B",a.getPublic(),a.getPrivate());byte[] raw=Protocol.un64(forB.substring(5));raw[raw.length-1]^=1;
        String changed="QKD3:"+Protocol.b64(raw);assertEquals(receiver.id,IncomingText.groupId(changed));assertThrows(SecurityException.class,()->DirectProtocol.decrypt(receiver,changed,b.getPublic(),b.getPrivate()));
    }
    @Test public void unknownGroupIncompleteAndOversizedInputFail()throws Exception {
        Protocol.Group owner=Protocol.create("Friends",Protocol.random(32),a.getPublic());joined(owner);
        String wire=DirectProtocol.encrypt(owner,Protocol.fingerprint(b.getPublic()),"Test",a.getPublic(),a.getPrivate());
        assertThrows(SecurityException.class,()->IncomingText.group(Collections.emptyList(),IncomingText.groupId(wire)));
        for(String text:new String[]{"Hello","QKD3:","QKD4:AAAA","QKD3:A!", "QKD3:"+"A".repeat(100001)})assertThrows(IllegalArgumentException.class,()->IncomingText.normalize(text));
        assertThrows(Exception.class,()->IncomingText.groupId(IncomingText.normalize("QKD3:AAAA")));
    }
    @Test public void legacySharedPacketRoutesWithoutChangingAuthentication()throws Exception {
        Protocol.Group owner=Protocol.create("Old",Protocol.random(32),a.getPublic());Protocol.Group receiver=joined(owner);
        String wire=Protocol.encrypt(owner,"Older text",a.getPublic(),a.getPrivate());assertEquals(owner.id,IncomingText.groupId(wire));assertEquals("Older text",Protocol.decrypt(receiver,wire).text);
    }
}
