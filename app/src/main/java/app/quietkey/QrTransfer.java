package app.quietkey;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded multi-frame QR collection. No state is committed until complete verification. */
public final class QrTransfer {
    public static List<String> frames(String packet)throws Exception{
        if(packet.length()>30000)throw new SecurityException("Invitation too large");
        if(packet.length()<=400)return Collections.singletonList(packet);
        String hash=Protocol.b64(MessageDigest.getInstance("SHA-256").digest(packet.getBytes(StandardCharsets.UTF_8)));int count=(packet.length()+319)/320;List<String> result=new ArrayList<>();
        for(int i=0;i<count;i++)result.add("QKF1:"+hash+":"+i+":"+count+":"+packet.substring(i*320,Math.min(packet.length(),(i+1)*320)));return result;
    }
    private String hash;private String[] parts;private long started;
    public String add(String frame)throws Exception{
        if(!frame.startsWith("QKF1:")){if(frame.length()>30000)throw new SecurityException("QR too large");return frame;}
        String[] p=frame.split(":",5);if(p.length!=5||p[1].length()!=43||Protocol.un64(p[1]).length!=32||p[4].length()>480||p[4].isEmpty())throw new SecurityException("Invalid QR frame");
        int index=Integer.parseInt(p[2]),count=Integer.parseInt(p[3]);if(count<2||count>96||index<0||index>=count)throw new SecurityException("Invalid QR frame number");
        if(parts==null){hash=p[1];parts=new String[count];started=System.currentTimeMillis();}
        if(System.currentTimeMillis()-started>PairingProtocol.WINDOW||!hash.equals(p[1])||parts.length!=count)throw new SecurityException("Different or expired QR transfer. Start again");
        if(parts[index]!=null&&!parts[index].equals(p[4]))throw new SecurityException("Conflicting QR frame");parts[index]=p[4];for(String part:parts)if(part==null)return null;
        String packet=String.join("",parts);if(packet.length()>30000||!hash.equals(Protocol.b64(MessageDigest.getInstance("SHA-256").digest(packet.getBytes(StandardCharsets.UTF_8)))))throw new SecurityException("QR transfer changed");return packet;
    }
    public int received(){if(parts==null)return 0;int n=0;for(String s:parts)if(s!=null)n++;return n;}
    public String missing(){if(parts==null)return "all";StringBuilder missing=new StringBuilder();int shown=0;for(int i=0;i<parts.length;i++)if(parts[i]==null){if(shown++==6){missing.append("…");break;}if(missing.length()>0)missing.append(", ");missing.append(i+1);}return missing.toString();}
    public int total(){return parts==null?0:parts.length;}
}
