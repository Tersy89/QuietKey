package app.quietkey;

import static org.junit.Assert.*;
import java.security.*;
import java.util.*;
import org.junit.Test;

/** Regression for 0.4.4: a received message failed when another group (e.g. Starter group) was selected or the messenger wrapped it. */
public class ReceiveRoutingTest {
    @Test public void packetNamesItsGroupAndSurvivesMessengerWrapping()throws Exception{
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);KeyPair a=gen.generateKeyPair(),b=gen.generateKeyPair();
        Protocol.Group starter=Protocol.create("Starter group",Protocol.random(32),a.getPublic());
        Protocol.Group team=Protocol.create("Team",Protocol.random(32),a.getPublic());PairingProtocol.signRoster(team,a.getPublic(),a.getPrivate());
        String request=PairingProtocol.request(b.getPublic(),b.getPrivate(),"B");
        String invitation=JoiningFlow.invite(team,request,a.getPublic(),a.getPrivate(),new HashSet<>(),new HashMap<>(),new HashMap<>());
        Protocol.Group joined=PairingProtocol.accept(invitation,request,b.getPublic(),b.getPrivate());
        String wire=DirectProtocol.encrypt(joined,team.owner,"hello owner",b.getPublic(),b.getPrivate());
        StringBuilder wrapped=new StringBuilder();for(int i=0;i<wire.length();i+=70)wrapped.append(wire,i,Math.min(wire.length(),i+70)).append("\r\n");
        String pasted="  "+wrapped+"  ";
        assertTrue(DirectProtocol.isPrivate(pasted));
        assertEquals(team.id,DirectProtocol.groupId(pasted));
        assertThrows(SecurityException.class,()->DirectProtocol.decrypt(starter,pasted,a.getPublic(),a.getPrivate()));
        Map<String,Protocol.Group> owned=new HashMap<>();owned.put(starter.id,starter);owned.put(team.id,team);
        assertEquals("hello owner",DirectProtocol.decrypt(owned.get(DirectProtocol.groupId(pasted)),pasted,a.getPublic(),a.getPrivate()).text);
    }
}
