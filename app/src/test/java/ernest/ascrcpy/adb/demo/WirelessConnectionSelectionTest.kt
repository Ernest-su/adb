package ernest.ascrcpy.adb.demo

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessConnectionSelectionTest {
    @Test fun skipsStaleMatchingServiceAndUsesReachableDuplicate() = runBlocking {
        val services = Channel<Pair<String, Int>>(Channel.UNLIMITED)
        services.trySend("adb-guid" to 34241)
        services.trySend("adb-guid (2)" to 44307)

        val selected = firstReachableWirelessService(
            services,
            matches = { (name) -> name.contains("guid") },
            isReachable = { (_, port) -> port == 44307 },
        )

        assertEquals(44307, selected.second)
        services.close()
        Unit
    }
}
