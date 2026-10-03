package app.quietkey;

/** Signed packet expiry. Device clocks and a modified recipient are outside this policy. */
public final class Expiry {
    public static final long MAX_TTL=30L*24*60*60*1000;
    private Expiry(){}
    public static long deadline(long created,long ttl,long groupExpiry){
        if(ttl<0 || ttl>MAX_TTL)throw new IllegalArgumentException("Choose an expiry up to 30 days");
        return ttl==0?0:Math.min(Math.addExact(created,ttl),groupExpiry);
    }
    public static void check(long created,long expires,long groupExpiry){checkAt(created,expires,groupExpiry,System.currentTimeMillis());}
    static void checkAt(long created,long expires,long groupExpiry,long now){
        if(created==0 && expires==0)return; // Older packets carry no TTL.
        if(created<=0 || created>now+300000 || expires<0 || (expires!=0 && (expires<=created || expires>groupExpiry || expires-created>MAX_TTL)))throw new SecurityException("Invalid message expiry or device clock");
        if(expires!=0 && now>=expires)throw new SecurityException("This message has expired");
    }
}
