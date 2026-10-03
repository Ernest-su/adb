package ernest.ascrcpy.adb.demo

import ernest.ascrcpy.adb.AdbClient
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSessionTest {
    @Test fun disconnectClosesClientOffCallerThread() {
        val caller = Thread.currentThread()
        val closed = CountDownLatch(1)
        var closeThread: Thread? = null
        val client = Proxy.newProxyInstance(AdbClient::class.java.classLoader,
            arrayOf(AdbClient::class.java)) { _, method, _ ->
            if (method.name == "close") {
                closeThread = Thread.currentThread()
                closed.countDown()
                null
            } else error("Unexpected call: ${method.name}")
        } as AdbClient

        DeviceSession.set(client, "test")
        DeviceSession.clear()

        assertNull(DeviceSession.client)
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertNotSame(caller, closeThread)
    }
}
