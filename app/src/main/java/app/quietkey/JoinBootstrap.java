package app.quietkey;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static app.quietkey.Packets.*;

/**
 * QKB1: the one small packet the owner shows as a single QR or serves over an NFC tap.
 * It carries how to reach the owner (temporary hotspot or same Wi-Fi), a one-time key-encryption
 * key for the local transport, and the owner's identity fingerprint for pinning. It never carries
 * the group secret: that stays inside the recipient-encrypted, owner-signed QKG2 invitation.
 */
public final class JoinBootstrap {
    public static final String PREFIX="QKB1:";
    public static final int HOTSPOT=1,SAME_WIFI=2,WPA2=0,WPA3=1,MAX=600;
    public final int mode,security,port;
    public final String ssid,passphrase,address,owner;
    public final long expires;
    private final byte[] kek;
    private JoinBootstrap(int mode,String ssid,String passphrase,int security,String address,int port,byte[] kek,String owner,long expires){
        this.mode=mode;this.ssid=ssid;this.passphrase=passphrase;this.security=security;this.address=address;this.port=port;this.kek=kek;this.owner=owner;this.expires=expires;
    }
    public static JoinBootstrap hotspot(String ssid,String passphrase,int security,String address,int port,String owner){return create(HOTSPOT,ssid,passphrase,security,address,port,owner);}
    public static JoinBootstrap sameWifi(String address,int port,String owner){return create(SAME_WIFI,"","",WPA2,address,port,owner);}
    private static JoinBootstrap create(int mode,String ssid,String passphrase,int security,String address,int port,String owner){
        JoinBootstrap b=new JoinBootstrap(mode,ssid,passphrase,security,address,port,Protocol.random(32),owner,System.currentTimeMillis()+JoinProtocol.WINDOW);b.check();return b;
    }
    private void check(){
        if(mode!=HOTSPOT&&mode!=SAME_WIFI)throw new SecurityException("Unsupported joining method");
        if(mode==HOTSPOT&&(ssid.isEmpty()||ssid.getBytes(StandardCharsets.UTF_8).length>32||passphrase.length()<8||passphrase.length()>63))throw new SecurityException("Invalid hotspot details");
        if(security!=WPA2&&security!=WPA3)throw new SecurityException("Unsupported hotspot security");
        if(!LocalJoinWire.privateAddress(address)||port<1024||port>65535)throw new SecurityException("Invalid owner address");
        if(kek.length!=32||Protocol.un64(owner).length!=32)throw new SecurityException("Invalid joining key");
    }
    public String encode()throws Exception{
        return PREFIX+Protocol.b64(pack(o->{o.writeUTF("bootstrap-v1");o.writeByte(mode);o.writeUTF(ssid);o.writeUTF(passphrase);o.writeByte(security);o.write(LocalJoinWire.addressBytes(address));o.writeShort(port);blob(o,kek);blob(o,Protocol.un64(owner));o.writeLong(expires);}));
    }
    public static JoinBootstrap parse(String wire)throws Exception{
        wire=wire==null?"":wire.trim();if(wire.length()>MAX)throw new SecurityException("Not an Enigma joining code");
        DataInputStream in=input(decode(wire,PREFIX,MAX));
        if(!in.readUTF().equals("bootstrap-v1"))throw new SecurityException("Update Enigma on both phones");
        int mode=in.readUnsignedByte();String ssid=in.readUTF(),passphrase=in.readUTF();int security=in.readUnsignedByte();byte[] ip=new byte[4];in.readFully(ip);int port=in.readUnsignedShort();
        byte[] kek=blob(in,32),owner=blob(in,32);long expires=in.readLong();end(in);
        String address=(ip[0]&255)+"."+(ip[1]&255)+"."+(ip[2]&255)+"."+(ip[3]&255);
        JoinBootstrap b=new JoinBootstrap(mode,ssid,passphrase,security,address,port,kek,Protocol.b64(owner),expires);b.check();
        long now=System.currentTimeMillis();if(now>=expires||expires-now>JoinProtocol.WINDOW+60000)throw new SecurityException("This joining code has expired. Ask the owner to start the invitation again.");
        return b;
    }
    /** Transport key for LocalJoinWire frames; domain-separated from everything else derived from the KEK. */
    public byte[] transportKey()throws Exception{return Protocol.hkdf(kek,"Enigma/join-bootstrap/v1".getBytes(StandardCharsets.UTF_8),"transport".getBytes(StandardCharsets.UTF_8));}
    /** Six digits both screens show; binds this bootstrap to the exact signed request that arrived. */
    public String checkCode(String request)throws Exception{
        byte[] digest=Protocol.hkdf(kek,MessageDigest.getInstance("SHA-256").digest(request.getBytes(StandardCharsets.US_ASCII)),"check-code".getBytes(StandardCharsets.UTF_8));
        try{return digits(digest);}finally{Arrays.fill(digest,(byte)0);}
    }
    /** Manual file transfer has no shared KEK: both sides derive the code from the request and the owner's public key. */
    public static String manualCheckCode(String request,Identity.Public owner)throws Exception{
        MessageDigest sha=MessageDigest.getInstance("SHA-256");sha.update("Enigma/manual-check/v3".getBytes(StandardCharsets.UTF_8));sha.update(request.trim().getBytes(StandardCharsets.US_ASCII));sha.update(owner.encode());
        return digits(sha.digest());
    }
    private static String digits(byte[] digest){long value=((digest[0]&255L)<<24|(digest[1]&255L)<<16|(digest[2]&255L)<<8|(digest[3]&255L))%1000000L;String s=String.format(Locale.ROOT,"%06d",value);return s.substring(0,3)+" "+s.substring(3);}
    /** Recipient side: the invitation must come from the identity the QR or NFC tap named. */
    public void requireOwner(String fingerprint){if(!owner.equals(fingerprint))throw new SecurityException("This invitation is not from the phone whose code you scanned. Joining refused.");}
    public void clear(){Arrays.fill(kek,(byte)0);}
}
