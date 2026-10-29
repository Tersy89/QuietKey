package app.quietkey;

import org.junit.*;
import static org.junit.Assert.*;

/** Decision 12: PIN as a second factor bound to the first-factor secret; panic PIN; stored state. */
public final class PinBoxTest {
    @Test public void masterNeedsBothTheFirstFactorSecretAndThePin()throws Exception{
        byte[] secret=Protocol.random(32),master=Protocol.random(32);
        PinBox.State s=PinBox.seal(secret,"2580","9999",master);
        assertArrayEquals(master,PinBox.open(s,secret,"2580"));
        assertThrows(Exception.class,()->PinBox.open(s,secret,"2581"));
        assertThrows(Exception.class,()->PinBox.open(s,Protocol.random(32),"2580"));
        assertTrue(s.belongsTo(secret));assertFalse(s.belongsTo(Protocol.random(32)));
    }
    @Test public void panicPinAndValidation()throws Exception{
        byte[] secret=Protocol.random(32);PinBox.State s=PinBox.seal(secret,"1234",PinBox.DEFAULT_PANIC,Protocol.random(32));
        assertTrue(PinBox.isPanic(s,"9999"));assertFalse(PinBox.isPanic(s,"1234"));assertFalse(PinBox.isPanic(s,"999"));
        assertThrows(IllegalArgumentException.class,()->PinBox.seal(secret,"1234","1234",Protocol.random(32)));
        assertThrows(IllegalArgumentException.class,()->PinBox.seal(secret,"12345","9999",Protocol.random(32)));
        assertThrows(IllegalArgumentException.class,()->PinBox.seal(secret,"12a4","9999",Protocol.random(32)));
        assertFalse(PinBox.valid(null));assertFalse(PinBox.valid("١٢٣٤"));assertTrue(PinBox.valid("0000"));
    }
    @Test public void stateRoundTripsWithAttemptCount()throws Exception{
        byte[] secret=Protocol.random(32),master=Protocol.random(32);PinBox.State s=PinBox.seal(secret,"4711","0000",master);
        s.failures=3;PinBox.State again=PinBox.State.decode(s.encode());
        assertEquals(3,again.failures);assertEquals(2,again.remaining());assertEquals("0000",again.panic);assertArrayEquals(master,PinBox.open(again,secret,"4711"));
        byte[] bad=s.encode();bad[7]=9; // failures above the limit
        assertThrows(java.io.IOException.class,()->PinBox.State.decode(bad));
    }
}
