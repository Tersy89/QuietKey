package app.quietkey;

import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import org.junit.Test;

public class JoinBootstrapTest {
    private static TestDevice pair(){return new TestDevice();}

    @Test public void hotspotRoundTripStaysSmallForOneQr()throws Exception{
        TestDevice a=pair();String owner=a.fp;
        JoinBootstrap b=JoinBootstrap.hotspot("AndroidShare_1234","pass word-123",JoinBootstrap.WPA2,"192.168.49.1",45873,owner);
        String wire=b.encode();assertTrue(wire.length()<300);
        JoinBootstrap parsed=JoinBootstrap.parse(wire);
        assertEquals("AndroidShare_1234",parsed.ssid);assertEquals("pass word-123",parsed.passphrase);assertEquals("192.168.49.1",parsed.address);assertEquals(45873,parsed.port);assertEquals(owner,parsed.owner);
        assertArrayEquals(b.transportKey(),parsed.transportKey());
        parsed.requireOwner(owner);
    }

    @Test public void sameWifiNeedsNoHotspotDetails()throws Exception{
        JoinBootstrap b=JoinBootstrap.parse(JoinBootstrap.sameWifi("10.0.0.7",45873,pair().fp).encode());
        assertEquals(JoinBootstrap.SAME_WIFI,b.mode);assertEquals("10.0.0.7",b.address);
    }

    @Test public void checkCodeMatchesOnlyForTheSameRequestAndKey()throws Exception{
        TestDevice a=pair(),b=pair(),c=pair();String owner=a.fp;
        JoinBootstrap boot=JoinBootstrap.hotspot("Enigma","password1",JoinBootstrap.WPA2,"192.168.43.1",45873,owner),scanned=JoinBootstrap.parse(boot.encode());
        String request=JoinProtocol.request(b.id,b.pools,TestDevice.E,"B"),racer=JoinProtocol.request(c.id,c.pools,TestDevice.E,"C");
        assertEquals(boot.checkCode(request),scanned.checkCode(request));
        assertNotEquals(boot.checkCode(request),boot.checkCode(racer));
        JoinBootstrap other=JoinBootstrap.hotspot("Enigma","password1",JoinBootstrap.WPA2,"192.168.43.1",45873,owner);
        assertNotEquals(boot.checkCode(request),other.checkCode(request));
        assertTrue(boot.checkCode(request).matches("[0-9]{3} [0-9]{3}"));
    }

    @Test public void manualCheckCodeBindsRequestAndOwner()throws Exception{
        TestDevice a=pair(),b=pair(),m=pair();String request=JoinProtocol.request(b.id,b.pools,TestDevice.E,"B");
        assertEquals(JoinBootstrap.manualCheckCode(request,a.id.publicKey),JoinBootstrap.manualCheckCode(request+"\n",a.id.publicKey));
        assertNotEquals(JoinBootstrap.manualCheckCode(request,a.id.publicKey),JoinBootstrap.manualCheckCode(request,m.id.publicKey));
    }

    @Test public void wrongOwnerTamperingAndPublicAddressesAreRefused()throws Exception{
        String owner=pair().fp;
        JoinBootstrap b=JoinBootstrap.parse(JoinBootstrap.hotspot("Enigma","password1",JoinBootstrap.WPA2,"192.168.43.1",45873,owner).encode());
        assertThrows(SecurityException.class,()->b.requireOwner(pair().fp));
        assertThrows(SecurityException.class,()->JoinBootstrap.hotspot("Enigma","password1",JoinBootstrap.WPA2,"8.8.8.8",45873,owner));
        assertThrows(SecurityException.class,()->JoinBootstrap.hotspot("Enigma","short",JoinBootstrap.WPA2,"192.168.43.1",45873,owner));
        String wire=b.encode();
        assertThrows(Exception.class,()->JoinBootstrap.parse(wire.substring(0,wire.length()-4)));
        assertThrows(Exception.class,()->JoinBootstrap.parse(wire+"AAAA"));
        assertThrows(Exception.class,()->JoinBootstrap.parse("QKJ1:"+wire.substring(5)));
    }

    @Test public void expiredCodesAreRefused()throws Exception{
        String owner=pair().fp;
        byte[] body=Packets.pack(o->{o.writeUTF("bootstrap-v1");o.writeByte(1);o.writeUTF("Enigma");o.writeUTF("password1");o.writeByte(0);o.write(new byte[]{(byte)192,(byte)168,43,1});o.writeShort(45873);Packets.blob(o,new byte[32]);Packets.blob(o,Protocol.un64(owner));o.writeLong(System.currentTimeMillis()-1);});
        assertThrows(SecurityException.class,()->JoinBootstrap.parse("QKB1:"+Protocol.b64(body)));
    }

    @Test public void bootstrapKeyOpensTransportButNeverTheGroupSecret()throws Exception{
        // A transport-key holder (anyone who photographed the QR) sees only the recipient-encrypted QKG2.
        TestDevice a=pair(),b=pair(),eve=pair();
        Protocol.Group g=JoinProtocol.create("Team","Owner",a.id,a.pools,TestDevice.E);JoinProtocol.signRoster(g,a.id,a.pools,TestDevice.E,Collections.emptyMap());
        String request=JoinProtocol.request(b.id,b.pools,TestDevice.E,"B");
        String invitation=JoinProtocol.invite(g,JoinProtocol.parseRequest(request),a.id,a.pools,TestDevice.E);
        JoinBootstrap boot=JoinBootstrap.hotspot("Enigma","password1",JoinBootstrap.WPA2,"192.168.43.1",45873,g.owner);
        ByteArrayOutputStream link=new ByteArrayOutputStream();
        try(LocalJoinWire owner=new LocalJoinWire(new ByteArrayInputStream(new byte[0]),link,true,boot.transportKey())){owner.send(LocalJoinWire.INVITATION,invitation);}
        // Eve unwraps the transport layer with the photographed KEK…
        JoinBootstrap photo=JoinBootstrap.parse(boot.encode());byte[] frames=link.toByteArray();
        String seen;try(LocalJoinWire w=new LocalJoinWire(new ByteArrayInputStream(frames),new ByteArrayOutputStream(),false,photo.transportKey())){w.send(LocalJoinWire.REQUEST,request);seen=w.receive(LocalJoinWire.INVITATION);}
        assertEquals(invitation,seen);
        // …but the invitation inside is encrypted only to B's identity key.
        String eveRequest=JoinProtocol.request(eve.id,eve.pools,TestDevice.E,"Eve");
        assertThrows(Exception.class,()->JoinProtocol.accept(seen,eveRequest,eve.id,eve.pools));
        Protocol.Group joined=JoinProtocol.accept(seen,request,b.id,b.pools);
        photo.requireOwner(joined.owner);
    }
}
