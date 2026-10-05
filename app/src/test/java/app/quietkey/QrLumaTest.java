package app.quietkey;
import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;
public final class QrLumaTest {
    @Test public void paddedRowsAndOffsetIgnoreNonImageBytes(){ByteBuffer buffer=ByteBuffer.wrap(new byte[]{9,9,1,2,3,9,4,5,6});buffer.position(2);assertArrayEquals(new byte[]{1,2,3,4,5,6},QrLuma.copy(buffer,3,2,4,1));assertEquals(2,buffer.position());}
    @Test public void interleavedPixelsAndReadOnlyBuffers(){ByteBuffer buffer=ByteBuffer.wrap(new byte[]{1,9,2,9,9,3,9,4}).asReadOnlyBuffer();assertArrayEquals(new byte[]{1,2,3,4},QrLuma.copy(buffer,2,2,5,2));}
    @Test public void rejectsTruncatedLastRow(){assertThrows(IllegalArgumentException.class,()->QrLuma.copy(ByteBuffer.allocate(5),3,2,3,1));}
    @Test public void rejectsOverlappingRowsAndOversizedDimensions(){assertThrows(IllegalArgumentException.class,()->QrLuma.copy(ByteBuffer.allocate(12),3,2,4,2));assertThrows(IllegalArgumentException.class,()->QrLuma.copy(ByteBuffer.allocate(1),Integer.MAX_VALUE,2,1,1));}
}
