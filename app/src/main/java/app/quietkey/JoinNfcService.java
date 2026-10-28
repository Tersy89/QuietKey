package app.quietkey;

import android.nfc.cardemulation.HostApduService;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Serves the owner's one-time QKB1 joining code over an NFC tap, only while the invite screen is
 * open (the payload is cleared otherwise and the service answers "not found"). The tap carries the
 * same small bootstrap as the QR; the invitation itself still travels over the KEK-protected
 * Wi-Fi link and stays encrypted to the recipient's identity key.
 */
public final class JoinNfcService extends HostApduService {
    static final byte[] AID={(byte)0xF0,0x45,0x4E,0x49,0x47,0x4D,0x41,0x01};
    private static final byte[] OK={(byte)0x90,0x00},NOT_FOUND={(byte)0x6A,(byte)0x82},BAD={(byte)0x6D,0x00};
    private static final int CHUNK=240;
    private static volatile byte[] payload;

    static void serve(String code){payload=code.getBytes(StandardCharsets.US_ASCII);}
    static void clear(){byte[] p=payload;payload=null;if(p!=null)Arrays.fill(p,(byte)0);}

    @Override public byte[] processCommandApdu(byte[] apdu,Bundle extras){
        byte[] p=payload;
        if(apdu==null||apdu.length<4)return BAD;
        if(apdu[1]==(byte)0xA4){
            if(p==null||apdu.length<5+AID.length||!Arrays.equals(Arrays.copyOfRange(apdu,5,5+AID.length),AID))return NOT_FOUND;
            return new byte[]{(byte)(p.length>>8),(byte)p.length,OK[0],OK[1]};
        }
        if(apdu[1]==(byte)0xB0){
            if(p==null)return NOT_FOUND;int offset=((apdu[2]&255)<<8)|(apdu[3]&255);if(offset>=p.length)return BAD;
            int n=Math.min(CHUNK,p.length-offset);byte[] out=new byte[n+2];System.arraycopy(p,offset,out,0,n);out[n]=OK[0];out[n+1]=OK[1];return out;
        }
        return BAD;
    }
    @Override public void onDeactivated(int reason){}

    /** Reader side: select the Enigma AID, then read the code in chunks. */
    static String read(IsoDep tag)throws IOException{
        if(tag==null)throw new IOException("Not an Enigma phone");
        tag.connect();tag.setTimeout(3000);
        try{
            byte[] select=new byte[6+AID.length];select[1]=(byte)0xA4;select[2]=0x04;select[4]=(byte)AID.length;System.arraycopy(AID,0,select,5,AID.length);
            byte[] r=tag.transceive(select);if(r.length!=4||r[2]!=OK[0]||r[3]!=OK[1])throw new IOException("The other phone is not showing an invitation");
            int total=((r[0]&255)<<8)|(r[1]&255);if(total<5||total>JoinBootstrap.MAX)throw new IOException("Invalid joining code");
            ByteArrayOutputStream code=new ByteArrayOutputStream();
            while(code.size()<total){int off=code.size();byte[] chunk=tag.transceive(new byte[]{0,(byte)0xB0,(byte)(off>>8),(byte)off,(byte)Math.min(CHUNK,total-off)});
                if(chunk.length<3||chunk[chunk.length-2]!=OK[0]||chunk[chunk.length-1]!=OK[1])throw new IOException("NFC read interrupted");code.write(chunk,0,chunk.length-2);}
            return new String(code.toByteArray(),0,total,StandardCharsets.US_ASCII);
        }finally{try{tag.close();}catch(IOException ignored){}}
    }
}
