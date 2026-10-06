package app.quietkey;

import java.io.DataInputStream;
import java.util.Collection;

/** Untrusted routing hints only. Normal decryption still authenticates the complete packet. */
final class IncomingText {
    private IncomingText() {}
    static String normalize(String text) {
        if(text==null || text.length()>100000)throw new IllegalArgumentException("Copy the complete encrypted message from the sender");
        String wire=text.replaceAll("\\s","");
        if(wire.length()>75000 || !wire.matches("QK(?:D[123]|M1):[A-Za-z0-9_-]+={0,2}"))
            throw new IllegalArgumentException("Paste the complete encrypted message, starting with QKD3:. Use the sender's Copy or Share button");
        return wire;
    }
    static String groupId(String wire)throws Exception {
        boolean direct=wire.startsWith("QKD");
        DataInputStream outer=Packets.input(Packets.decode(wire,wire.substring(0,5),75000));
        byte[] body=Packets.blob(outer,direct?40000:18000);Packets.blob(outer,256);Packets.end(outer);
        DataInputStream in=Packets.input(body);byte[] header=Packets.blob(in,direct?20000:300);
        Packets.blob(in,Protocol.MAX_TEXT_BYTES+16);Packets.end(in);
        DataInputStream h=Packets.input(header);String version=h.readUTF();
        String expected=direct?"direct-v"+wire.charAt(3):"message-v1";
        if(!expected.equals(version))throw new SecurityException("Unsupported encrypted message. Update both phones");
        String id=h.readUTF();if(id.length()>80)throw new SecurityException("Invalid message group");return id;
    }
    static Protocol.Group group(Collection<Protocol.Group> groups,String id) {
        for(Protocol.Group group:groups)if(group.id.equals(id))return group;
        throw new SecurityException("This message's group is not on this phone. Join the sender's group, then open the message again");
    }
}
