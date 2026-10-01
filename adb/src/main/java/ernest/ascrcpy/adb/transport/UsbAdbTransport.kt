package ernest.ascrcpy.adb.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import ernest.ascrcpy.adb.AdbException
import java.io.EOFException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An ADB device visible to Android's USB Host API. USB permission is requested by the app. */
class UsbAdbTransport(
    private val manager: UsbManager,
    private val device: UsbDevice,
) : AdbTransport {
    private var connection: UsbDeviceConnection? = null
    private var adbInterface: UsbInterface? = null
    private var input: UsbEndpoint? = null
    private var output: UsbEndpoint? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        check(connection == null) { "Transport is already connected" }
        if (!manager.hasPermission(device)) throw AdbException("USB permission is required for ${device.deviceName}")
        val selected = findAdbInterface(device)
            ?: throw AdbException("No ADB USB interface on ${device.deviceName}; enable USB debugging")
        val opened = manager.openDevice(device)
            ?: throw AdbException("Unable to open USB device ${device.deviceName}")
        try {
            if (!opened.claimInterface(selected.first, true)) {
                throw AdbException("Unable to claim ADB USB interface")
            }
            adbInterface = selected.first
            input = selected.second
            output = selected.third
            connection = opened
        } catch (error: Throwable) {
            opened.close()
            throw error
        }
    }

    override suspend fun readExactly(buffer: ByteArray, offset: Int, length: Int) {
        requireRange(buffer, offset, length)
        withContext(Dispatchers.IO) {
            val active = checkNotNull(connection) { "USB transport is closed" }
            val endpoint = checkNotNull(input)
            var position = offset
            var emptyTransfers = 0
            while (position < offset + length) {
                val count = active.bulkTransfer(endpoint, buffer, position,
                    minOf(MAX_TRANSFER, offset + length - position), TIMEOUT_MS)
                if (count < 0 || (count == 0 && ++emptyTransfers >= 3)) {
                    throw EOFException("USB ADB read failed or timed out")
                }
                if (count > 0) emptyTransfers = 0
                position += count
            }
        }
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        requireRange(buffer, offset, length)
        withContext(Dispatchers.IO) {
            val active = checkNotNull(connection) { "USB transport is closed" }
            val endpoint = checkNotNull(output)
            var position = offset
            var emptyTransfers = 0
            while (position < offset + length) {
                val count = active.bulkTransfer(endpoint, buffer, position,
                    minOf(MAX_TRANSFER, offset + length - position), TIMEOUT_MS)
                if (count < 0 || (count == 0 && ++emptyTransfers >= 3)) {
                    throw AdbException("USB ADB write failed or timed out")
                }
                if (count > 0) emptyTransfers = 0
                position += count
            }
        }
    }

    override fun close() {
        connection?.let { opened ->
            adbInterface?.let { runCatching { opened.releaseInterface(it) } }
            opened.close()
        }
        connection = null
        adbInterface = null
        input = null
        output = null
    }

    private fun requireRange(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
    }

    companion object {
        private const val MAX_TRANSFER = 16 * 1024 // Android 8 may truncate larger USB transfers.
        private const val TIMEOUT_MS = 10_000

        /** Lists devices exposing the standard ADB interface; permission may still be needed. */
        fun discover(manager: UsbManager): List<UsbDevice> =
            manager.deviceList.values.filter { findAdbInterface(it) != null }

        private fun findAdbInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
            for (index in 0 until device.interfaceCount) {
                val candidate = device.getInterface(index)
                if (candidate.interfaceClass != 0xff || candidate.interfaceSubclass != 0x42 ||
                    candidate.interfaceProtocol != 1) continue
                var input: UsbEndpoint? = null
                var output: UsbEndpoint? = null
                for (endpointIndex in 0 until candidate.endpointCount) {
                    val endpoint = candidate.getEndpoint(endpointIndex)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT) output = endpoint
                }
                if (input != null && output != null) return Triple(candidate, input, output)
            }
            return null
        }
    }
}
