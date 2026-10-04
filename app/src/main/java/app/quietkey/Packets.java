package app.quietkey;
import java.io.*;
import java.security.*;

/** Shared bounded binary framing; never accepts unbounded allocations. */
final class Packets {
    interface Writer{void write(DataOutputStream out)throws Exception;}
    static byte[] pack(Writer w)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();w.write(new DataOutputStream(b));return b.toByteArray();}
    static DataInputStream input(byte[] b){return new DataInputStream(new ByteArrayInputStream(b));}
    static void blob(DataOutputStream out,byte[] b)throws IOException{out.writeInt(b.length);out.write(b);}
    static byte[] blob(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max||n>in.available())throw new IOException("Invalid packet size");byte[] b=new byte[n];in.readFully(b);return b;}
    static void end(DataInputStream in)throws IOException{if(in.available()!=0)throw new IOException("Unexpected trailing data");}
    static byte[] signed(byte[] body,PrivateKey key)throws Exception{Signature s=Signature.getInstance("SHA256withRSA");s.initSign(key);s.update(body);return pack(o->{blob(o,body);blob(o,s.sign());});}
    static byte[] verified(byte[] envelope,PublicKey key,int max)throws Exception{DataInputStream in=input(envelope);byte[] body=blob(in,max),sig=blob(in,256);end(in);Signature s=Signature.getInstance("SHA256withRSA");s.initVerify(key);s.update(body);if(!s.verify(sig))throw new SecurityException("Invalid signature");return body;}
    static byte[] decode(String text,String prefix,int max){text=text.trim();if(!text.startsWith(prefix)||text.length()>max)throw new SecurityException("Invalid packet");return Protocol.un64(text.substring(prefix.length()));}
}
