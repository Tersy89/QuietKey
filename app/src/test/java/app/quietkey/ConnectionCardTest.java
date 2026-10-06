package app.quietkey;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ConnectionCardTest {
    @Test public void oneSmallCodeCarriesExactPrivateEndpointAndRandomSecret()throws Exception {
        String code=LocalJoinWire.newCode(),packet=ConnectionCard.create("192.168.43.1",code);assertTrue(packet.length()<70);
        ConnectionCard card=ConnectionCard.parse(packet);assertEquals("192.168.43.1",card.address);assertEquals(code,card.code);assertArrayEquals(LocalJoinWire.codeKey(code),LocalJoinWire.codeKey(card.code));
        assertEquals(packet,QrImage.decode(QrImage.encode(packet,320)));
    }
    @Test public void cardsRefusePublicDnsMalformedAndShortSecrets()throws Exception {
        String code=LocalJoinWire.newCode();for(String host:new String[]{"example.com","8.8.8.8","127.0.0.1","192.168.1.256","192.168.1.1:80"})assertThrows(IllegalArgumentException.class,()->ConnectionCard.parse("QKC1:"+host+":"+code));
        for(String packet:new String[]{"QKC1:192.168.1.1:123456","QKC2:192.168.1.1:"+code,"QKC1:192.168.1.1:"+code+":extra","x".repeat(101)})assertThrows(IllegalArgumentException.class,()->ConnectionCard.parse(packet));
    }
}
