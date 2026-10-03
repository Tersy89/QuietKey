package app.quietkey;
import org.junit.Test;
import static org.junit.Assert.*;
public final class ExpiryTest {
    @Test public void deadlineAndExactBoundary(){assertEquals(2000,Expiry.deadline(1000,1000,9000));Expiry.checkAt(1000,2000,9000,1999);assertThrows(SecurityException.class,()->Expiry.checkAt(1000,2000,9000,2000));}
    @Test public void noExpiryLegacyAndGroupClamp(){assertEquals(0,Expiry.deadline(1000,0,9000));Expiry.checkAt(0,0,9000,999999);Expiry.checkAt(1000,0,9000,8000);assertEquals(1500,Expiry.deadline(1000,1000,1500));}
    @Test public void invalidRangesAndClock(){assertThrows(IllegalArgumentException.class,()->Expiry.deadline(1000,-1,9000));assertThrows(IllegalArgumentException.class,()->Expiry.deadline(1000,Expiry.MAX_TTL+1,Long.MAX_VALUE));assertThrows(SecurityException.class,()->Expiry.checkAt(1000,999,9000,1000));assertThrows(SecurityException.class,()->Expiry.checkAt(1000,10000,9000,1000));assertThrows(SecurityException.class,()->Expiry.checkAt(400000,0,Long.MAX_VALUE,1000));}
}
