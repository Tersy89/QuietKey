package app.quietkey;
import java.io.*;

/** Shared bounded binary framing; never accepts unbounded allocations. */
final class Packets {
    interface Writer{void write(DataOutputStream out)throws Exception;}
    static byte[] pack(Writer w)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();w.write(new DataOutputStream(b));return b.toByteArray();}
    static DataInputStream input(byte[] b){return new DataInputStream(new ByteArrayInputStream(b));}
    static void blob(DataOutputStream out,byte[] b)throws IOException{out.writeInt(b.length);out.write(b);}
    static byte[] blob(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max||n>in.available())throw new IOException("Invalid packet size");byte[] b=new byte[n];in.readFully(b);return b;}
    static void end(DataInputStream in)throws IOException{if(in.available()!=0)throw new IOException("Unexpected trailing data");}
    /** Packets pasted or carried in messengers may be line-wrapped. */
    static String clean(String wire){return wire==null?"":wire.replaceAll("\\s","");}
    static byte[] decode(String text,String prefix,int max){text=text.trim();if(!text.startsWith(prefix)||text.length()>max)throw new SecurityException("Invalid packet");return Protocol.un64(text.substring(prefix.length()));}
}
