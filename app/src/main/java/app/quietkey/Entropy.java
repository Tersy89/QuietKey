package app.quietkey;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;

/**
 * Key-generation randomness for 0.7.0. OS randomness is the foundation; live camera digests and
 * motion-sensor readings are mixed into a pool as additional input (SYSTEM_DESIGN decision 10).
 * Every output combines fresh OS randomness with the current pool, so a dark camera or a phone lying
 * still never weakens it. Extra input is not a measured entropy guarantee.
 */
public final class Entropy {
    private static final SecureRandom OS=new SecureRandom();
    private final byte[] pool=new byte[32];
    private long counter;private int motionSamples;private boolean camera;

    public synchronized void addCamera(byte[] digest){
        if(digest==null||digest.length!=32)throw new IllegalArgumentException("Invalid camera digest");
        mix("camera",digest);camera=true;
    }
    /** One accelerometer/gyroscope event: its sensor type, nanosecond timestamp and raw values. */
    public synchronized void addMotion(int sensorType,long timestampNanos,float[] values){
        if(values==null||values.length>16)throw new IllegalArgumentException("Invalid motion sample");
        ByteBuffer b=ByteBuffer.allocate(12+4*values.length);b.putInt(sensorType).putLong(timestampNanos);for(float v:values)b.putFloat(v);
        mix("motion",b.array());motionSamples++;
    }
    /** 32 bytes for one key: HKDF over fresh OS randomness, salted with the mixed pool. */
    public synchronized byte[] next32(String purpose){
        byte[] os=new byte[32];OS.nextBytes(os);counter++;
        try{return Protocol.hkdf(os,pool,("Enigma/entropy/v1\n"+purpose+"\n"+counter).getBytes(StandardCharsets.UTF_8));}
        catch(Exception e){throw new IllegalStateException(e);}finally{Arrays.fill(os,(byte)0);}
    }
    public synchronized boolean hasCamera(){return camera;}
    public synchronized int motionSamples(){return motionSamples;}
    private void mix(String label,byte[] input){
        try{MessageDigest sha=MessageDigest.getInstance("SHA-256");sha.update(pool);sha.update(label.getBytes(StandardCharsets.UTF_8));sha.update(input);
            byte[] next=sha.digest();System.arraycopy(next,0,pool,0,32);Arrays.fill(next,(byte)0);}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
