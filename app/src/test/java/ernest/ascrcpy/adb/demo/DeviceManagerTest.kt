package ernest.ascrcpy.adb.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun deleteUsesQuotedPathAndChecksShellResult() {
        val file = RemoteFile("a'b.txt", "/sdcard/a'b.txt", 0x8000, 1, 0)
        val folder = RemoteFile("folder", "/sdcard/folder", 0x4000, 0, 0)
        assertTrue(DeviceManager.deleteCommand(file).startsWith("rm -- '/sdcard/a'\\''b.txt' 2>&1;"))
        assertTrue(DeviceManager.deleteCommand(folder).startsWith("rm -r -- '/sdcard/folder' 2>&1;"))
        DeviceManager.requireDeleteSuccess("\n__ADB_DELETE_EXIT__:0\n")
        assertTrue(runCatching {
            DeviceManager.requireDeleteSuccess("rm: permission denied\n__ADB_DELETE_EXIT__:1\n")
        }.exceptionOrNull()?.message?.contains("permission denied") == true)
        assertTrue(runCatching { DeviceManager.requireDeleteSuccess("unexpected output") }.isFailure)
        assertTrue(runCatching { DeviceManager.deleteCommand(file.copy(path = "/sdcard/../")) }.isFailure)
    }

    @Test fun appActionsValidatePackagesAndRequireSuccess() {
        assertEquals("pm clear --user 10 'com.example.notes'",
            DeviceManager.appCommand(AppAction.CLEAR_DATA, "com.example.notes", 10))
        assertEquals("pm uninstall --user 0 'com.example.notes'",
            DeviceManager.appCommand(AppAction.UNINSTALL, "com.example.notes", 0))
        assertTrue(runCatching { DeviceManager.appCommand(AppAction.UNINSTALL, "com.example.x;reboot", 0) }.isFailure)
        assertTrue(runCatching { DeviceManager.appCommand(AppAction.CLEAR_DATA, "com.example.x", -1) }.isFailure)
        DeviceManager.requireAppSuccess("Success\n", 0)
        assertTrue(runCatching { DeviceManager.requireAppSuccess("Failure [DELETE_FAILED]", 0) }.isFailure)
        assertTrue(runCatching { DeviceManager.requireAppSuccess("Success", 1) }.isFailure)
    }

    @Test fun recognizesTailscaleAddresses() {
        assertEquals(true, "100.64.0.1".isTailscaleAddress())
        assertEquals(true, "100.127.255.254".isTailscaleAddress())
        assertEquals(true, "fd7a:115c:a1e0::1".isTailscaleAddress())
        assertEquals(false, "100.128.0.1".isTailscaleAddress())
        assertEquals(false, "192.168.1.17".isTailscaleAddress())
    }
}
