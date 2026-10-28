package app.quietkey;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Cipher;

/** Bounded, ordered joining frames, encrypted with the one-time key from the owner's QR or NFC tap. */
public final class LocalJoinWire implements AutoCloseable {
    public static final int REQUEST=1, INVITATION=2, RECEIPT=3, DONE=4, MAX=30000;
    private static final int MAGIC=0x454A5731;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final boolean owner;
    private final byte[] key;
    private int sent,received;
    public LocalJoinWire(InputStream in,OutputStream out,boolean owner,byte[] key) {
        this.in=new DataInputStream(in);this.out=new DataOutputStream(out);this.owner=owner;
        if(key!=null&&key.length!=32)throw new IllegalArgumentException("Invalid connection key");
        this.key=key==null?null:key.clone();
    }
    public static boolean privateAddress(String address){
        if(address==null||!address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return false;
        String[] parts=address.split("\\.");int[] n=new int[4];for(int i=0;i<4;i++){n[i]=Integer.parseInt(parts[i]);if(n[i]>255)return false;}
        return n[3]!=0&&n[3]!=255&&(n[0]==10||(n[0]==172&&n[1]>=16&&n[1]<=31)||(n[0]==192&&n[1]==168));
    }
    public static byte[] addressBytes(String address){if(!privateAddress(address))throw new IllegalArgumentException("A private IPv4 address is required");String[] parts=address.split("\\.");byte[] bytes=new byte[4];for(int i=0;i<4;i++)bytes[i]=(byte)Integer.parseInt(parts[i]);return bytes;}
    private static int expected(boolean owner,int index){if(index<0||index>1)throw new SecurityException("Joining exchange already complete");return owner?(index==0?INVITATION:DONE):(index==0?REQUEST:RECEIPT);}
    private static void validate(int type,String packet){
        String prefix=type==REQUEST?"QKJ1:":type==INVITATION?"QKG2:":type==RECEIPT?"QKA1:":"Joined";
        int limit=type==REQUEST?1600:type==RECEIPT?1500:type==DONE?6:MAX;
        if(packet==null||packet.length()>limit||!packet.startsWith(prefix)||(type==DONE&&!packet.equals(prefix)))throw new SecurityException("Unexpected joining packet");
        for(int i=0;i<packet.length();i++){char c=packet.charAt(i);if(c<33||c>126)throw new SecurityException("Invalid joining packet characters");}
    }
    private static byte[] header(int type,int sequence,int length,boolean encrypted)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream h=new DataOutputStream(b);h.writeInt(MAGIC);h.writeByte(encrypted?1:0);h.writeByte(type);h.writeInt(sequence);h.writeInt(length);return b.toByteArray();}
    public void send(int type,String packet)throws Exception{
        if(type!=expected(owner,sent))throw new SecurityException("Unexpected joining step");validate(type,packet);
        byte[] clear=packet.getBytes(StandardCharsets.US_ASCII),nonce=null,body=clear;
        byte[] h=header(type,sent,clear.length+(key==null?0:28),key!=null);
        try{if(key!=null){nonce=Protocol.random(12);byte[] cipher=Protocol.aes(Cipher.ENCRYPT_MODE,key,nonce,h,clear);body=new byte[12+cipher.length];System.arraycopy(nonce,0,body,0,12);System.arraycopy(cipher,0,body,12,cipher.length);Arrays.fill(cipher,(byte)0);}out.write(h);out.write(body);out.flush();sent++;}
        finally{Arrays.fill(clear,(byte)0);if(body!=clear)Arrays.fill(body,(byte)0);if(nonce!=null)Arrays.fill(nonce,(byte)0);}
    }
    public String receive(int type)throws Exception{
        if(type!=expected(!owner,received))throw new SecurityException("Unexpected joining step");
        int magic=in.readInt(),mode=in.readUnsignedByte(),actual=in.readUnsignedByte(),sequence=in.readInt(),length=in.readInt();
        if(magic!=MAGIC||mode!=(key==null?0:1)||actual!=type||sequence!=received||length<1||length>MAX+28||(key!=null&&length<29))throw new SecurityException("Invalid joining frame");
        byte[] body=new byte[length],clear=body;in.readFully(body);
        try{if(key!=null)clear=Protocol.aes(Cipher.DECRYPT_MODE,key,Arrays.copyOfRange(body,0,12),header(actual,sequence,length,true),Arrays.copyOfRange(body,12,body.length));
            for(byte value:clear)if(value<33||value>126)throw new SecurityException("Invalid joining packet characters");String packet=new String(clear,StandardCharsets.US_ASCII);validate(type,packet);received++;return packet;
        }finally{Arrays.fill(body,(byte)0);if(clear!=body)Arrays.fill(clear,(byte)0);}
    }
    @Override public void close(){if(key!=null)Arrays.fill(key,(byte)0);}
}
