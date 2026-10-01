package ernest.ascrcpy.adb.transport

import ernest.ascrcpy.adb.crypto.WirelessTls
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Starts as raw TCP for CNXN/STLS, then carries ADB packets inside TLS. */
internal class TlsAdbTransport(
    private val host: String,
    private val port: Int,
    private val tls: WirelessTls,
) : AdbTransport {
    private var socket: Socket? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        check(socket == null)
        socket = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), 10_000)
        }
    }

    suspend fun upgrade() = withContext(Dispatchers.IO) {
        socket = tls.wrap(checkNotNull(socket), host, port)
    }

    override suspend fun readExactly(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        withContext(Dispatchers.IO) {
            val input = checkNotNull(socket).getInputStream()
            var position = offset
            while (position < offset + length) {
                val count = input.read(buffer, position, offset + length - position)
                if (count < 0) throw EOFException("Wireless ADB transport closed")
                position += count
            }
        }
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        withContext(Dispatchers.IO) {
            checkNotNull(socket).getOutputStream().write(buffer, offset, length)
        }
    }

    override fun close() {
        socket?.close()
        socket = null
    }
}
