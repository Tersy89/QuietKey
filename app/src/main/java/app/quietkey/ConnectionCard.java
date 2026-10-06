package app.quietkey;

/** One small local connection code, never a group key or an invitation. */
final class ConnectionCard {
    final String address,code;
    private ConnectionCard(String address,String code){this.address=address;this.code=code;}
    static String create(String address,String code)throws Exception {
        if(!LocalJoinWire.privateAddress(address))throw new IllegalArgumentException("Invalid hotspot address");
        byte[] key=LocalJoinWire.codeKey(code);java.util.Arrays.fill(key,(byte)0);
        return "QKC1:"+address+":"+code;
    }
    static ConnectionCard parse(String packet)throws Exception {
        if(packet==null||packet.length()>100)throw new IllegalArgumentException("Scan or paste the owner's connection card");
        String[] parts=packet.trim().split(":",-1);
        if(parts.length!=3||!parts[0].equals("QKC1")||!LocalJoinWire.privateAddress(parts[1]))throw new IllegalArgumentException("Invalid local connection card");
        byte[] key=LocalJoinWire.codeKey(parts[2]);java.util.Arrays.fill(key,(byte)0);
        return new ConnectionCard(parts[1],parts[2]);
    }
}
