package app.quietkey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Remote joining by file (Signal, email, USB): a passphrase both people agreed by voice tags the
 * request and the invitation, so a swapped file is refused without comparing check codes live.
 * The tag only authenticates; the invitation is still encrypted to the requester's keys and
 * signed by the owner (JoinProtocol). Wire: EJP1: + base64url(HMAC) + "." + QKJ4/QKG4 packet.
 */
public final class JoinPhrase {
    public static final String PREFIX="EJP1:";
    public static final String REQUEST="request",INVITATION="invitation";
    public static final int ITERATIONS=310000,MIN_WORDS=4,MIN_LENGTH=16;
    private JoinPhrase(){}

    /** Case, spacing and dashes do not matter, so "Orange tiger-harbour  nine" equals "orange tiger harbour nine". */
    public static String normalize(String phrase){return phrase==null?"":phrase.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_.,]+"," ").trim();}
    public static void requireStrong(String phrase){
        String p=normalize(phrase);
        if(p.isEmpty()||p.split(" ").length<MIN_WORDS||p.length()<MIN_LENGTH)throw new IllegalArgumentException("Use at least "+MIN_WORDS+" words and "+MIN_LENGTH+" letters, for example: orange tiger harbour nine");
    }
    /** Slow on purpose (PBKDF2-SHA256), salted by the request, so a copied request file resists offline guessing. */
    public static byte[] key(String phrase,String requestWire)throws Exception{
        MessageDigest sha=MessageDigest.getInstance("SHA-256");sha.update("Enigma/join-phrase/v1".getBytes(StandardCharsets.UTF_8));sha.update(requestWire.trim().getBytes(StandardCharsets.US_ASCII));
        PBEKeySpec spec=new PBEKeySpec(normalize(phrase).toCharArray(),sha.digest(),ITERATIONS,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}finally{spec.clearPassword();}
    }
    public static boolean sealed(String packet){return packet!=null&&packet.trim().startsWith(PREFIX)&&packet.indexOf('.')>0;}
    /** The packet inside, unverified; the owner needs the request itself to derive the key. */
    public static String inner(String packet){
        if(!sealed(packet))throw new SecurityException("This joining file has no passphrase. Ask them to update Enigma and make a new one.");
        String p=packet.trim();return p.substring(p.indexOf('.')+1);
    }
    public static String seal(byte[] key,String label,String wire)throws Exception{return PREFIX+Protocol.b64(tag(key,label,wire.trim()))+"."+wire.trim();}
    public static String open(byte[] key,String label,String packet)throws Exception{
        String wire=inner(packet),p=packet.trim();byte[] given;
        try{given=Protocol.un64(p.substring(PREFIX.length(),p.indexOf('.')));}catch(IllegalArgumentException e){throw new SecurityException("Damaged joining file");}
        if(!MessageDigest.isEqual(given,tag(key,label,wire)))throw new SecurityException("The passphrase does not match, or the file was changed. Check the phrase with them and try again.");
        return wire;
    }
    private static byte[] tag(byte[] key,String label,String wire)throws Exception{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        mac.update(("Enigma/join-phrase/"+label).getBytes(StandardCharsets.UTF_8));mac.update((byte)0);return mac.doFinal(wire.getBytes(StandardCharsets.US_ASCII));
    }
}
