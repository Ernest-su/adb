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
}
