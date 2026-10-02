package ernest.ascrcpy.adb.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceManagerTest {
    @Test fun normalizesAndNavigatesRemotePaths() {
        assertEquals("/sdcard/Download", DeviceManager.normalize("//sdcard///Download/"))
        assertEquals("/sdcard", DeviceManager.parent("/sdcard/Download"))
        assertEquals("/", DeviceManager.parent("/sdcard"))
        assertEquals("/file.txt", DeviceManager.child("/", "file.txt"))
    }

    @Test fun quotesShellValues() {
        assertEquals("'/sdcard/a'\\''b'", DeviceManager.quote("/sdcard/a'b"))
    }

    @Test fun recognizesTailscaleAddresses() {
        assertEquals(true, "100.64.0.1".isTailscaleAddress())
        assertEquals(true, "100.127.255.254".isTailscaleAddress())
        assertEquals(true, "fd7a:115c:a1e0::1".isTailscaleAddress())
        assertEquals(false, "100.128.0.1".isTailscaleAddress())
        assertEquals(false, "192.168.1.17".isTailscaleAddress())
    }
}
