package ernest.ascrcpy.adb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class AdbEndpointTest {
    @Test public void serialCombinesHostAndPort() {
        assertEquals("192.168.1.12:5555", new AdbEndpoint("192.168.1.12", 5555, false).getSerial());
    }

    @Test public void rejectsInvalidPort() {
        assertThrows(IllegalArgumentException.class, () -> new AdbEndpoint("host", 0, false));
    }
}
