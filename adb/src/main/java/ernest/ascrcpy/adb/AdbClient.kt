package ernest.ascrcpy.adb

import android.hardware.usb.UsbDevice
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.StateFlow

/** Network address of an Android Debug Bridge daemon. */
data class AdbEndpoint(
    val host: String,
    val port: Int = DEFAULT_ADB_PORT,
    val usb: Boolean = false,
) {
    init {
        require(host.isNotBlank()) { "ADB host must not be blank" }
        require(usb || port in 1..65535) { "ADB port must be between 1 and 65535" }
    }

    val serial: String get() = if (usb) "usb:$host" else "$host:$port"

    companion object {
        const val DEFAULT_ADB_PORT = 5555
        fun usb(device: UsbDevice) = AdbEndpoint(device.deviceName, usb = true)
    }
}

data class AdbDevice(
    val endpoint: AdbEndpoint,
    val banner: String,
    val properties: Map<String, String>,
)

sealed interface AdbConnectionState {
    data object Disconnected : AdbConnectionState
    data class Connecting(val endpoint: AdbEndpoint) : AdbConnectionState
    data class Authorizing(val endpoint: AdbEndpoint) : AdbConnectionState
    data class Connected(val device: AdbDevice) : AdbConnectionState
    data class Failed(val endpoint: AdbEndpoint, val cause: Throwable) : AdbConnectionState
}

data class AdbCommandResult(
    val stdout: ByteArray,
    val exitCode: Int? = null,
) {
    fun text(): String = stdout.toString(Charsets.UTF_8)
}

/**
 * Stable, implementation-independent ADB facade consumed by applications.
 *
 * Implementations may use TCP, USB, a local ADB server, or a third-party library.
 */
interface AdbClient : Closeable {
    val state: StateFlow<AdbConnectionState>

    suspend fun connect(endpoint: AdbEndpoint): AdbDevice

    /** The caller must first obtain UsbManager permission for [device]. */
    suspend fun connectUsb(device: UsbDevice): AdbDevice =
        throw UnsupportedOperationException("USB transport is unavailable")

    /** Pair with the temporary pairing port using a displayed code or QR password. */
    suspend fun pairWireless(pairingEndpoint: AdbEndpoint, code: String): String =
        throw UnsupportedOperationException("Wireless pairing is unavailable")

    /** Connect to the separate Wireless debugging connection port after pairing. */
    suspend fun connectWireless(endpoint: AdbEndpoint): AdbDevice =
        throw UnsupportedOperationException("Wireless debugging is unavailable")

    suspend fun disconnect()

    suspend fun shell(command: String): AdbCommandResult

    suspend fun push(source: InputStream, remotePath: String, mode: Int = 0x1A4)

    /** Downloads a remote file through the ADB sync service. */
    suspend fun pull(remotePath: String, destination: OutputStream)

    /** Opens an arbitrary adbd service such as `localabstract:scrcpy_12345678`. */
    suspend fun open(service: String): AdbChannel
}

/** A full-duplex logical stream multiplexed over an ADB transport. */
interface AdbChannel : Closeable {
    suspend fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int
    suspend fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
}

fun interface AdbClientFactory {
    fun create(): AdbClient
}

open class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AdbAuthenticationException(message: String) : AdbException(message)

class AdbProtocolException(message: String) : AdbException(message)
