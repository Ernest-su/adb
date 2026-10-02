package ernest.ascrcpy.adb.crypto

import com.flyfish233.crypto.spake2.Spake2Context
import com.flyfish233.crypto.spake2.Spake2Role
import ernest.ascrcpy.adb.AdbAuthenticationException
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.AdbProtocolException
import ernest.ascrcpy.adb.AdbTlsKeyProvider
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.conscrypt.Conscrypt

/** AOSP wireless pairing protocol; the returned GUID identifies the paired device. */
internal class WirelessPairing(private val keys: AdbTlsKeyProvider) {
    suspend fun pair(endpoint: AdbEndpoint, code: String): String = withContext(Dispatchers.IO) {
        require(!endpoint.usb) { "Pairing requires a network endpoint" }
        require(code.isNotEmpty()) { "Pairing password must not be empty" }
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(endpoint.host, endpoint.port), 10_000)
            raw.soTimeout = 15_000
            raw.tcpNoDelay = true
            WirelessTls(keys.keyPair).wrap(raw, endpoint.host, endpoint.port, 15_000).use { socket ->
                val exported = Conscrypt.exportKeyingMaterial(socket, "adb-label\u0000", null, 64)
                val password = code.toByteArray(Charsets.UTF_8) + exported
                val spake = Spake2Context(
                    Spake2Role.Alice,
                    "adb pair client\u0000".toByteArray(),
                    "adb pair server\u0000".toByteArray(),
                )
                try {
                    val input = DataInputStream(socket.inputStream)
                    val output = DataOutputStream(socket.outputStream)
                    writePacket(output, SPAKE2_MSG, spake.generateMessage(password))
                    val peerMessage = readPacket(input, SPAKE2_MSG)
                    val secret = spake.processMessage(peerMessage)
                        ?: throw AdbAuthenticationException("Wireless pairing code was rejected")
                    val cipher = PairingCipher(secret)
                    val peerInfo = ByteArray(PEER_INFO_SIZE)
                    peerInfo[0] = ADB_RSA_PUB_KEY
                    val publicKey = keys.encodedPublicKey()
                    require(publicKey.size <= PEER_INFO_SIZE - 1) { "ADB public key is too large" }
                    publicKey.copyInto(peerInfo, 1)
                    writePacket(output, PEER_INFO, cipher.encrypt(peerInfo))
                    val result = cipher.decrypt(readPacket(input, PEER_INFO))
                    if (result.size != PEER_INFO_SIZE || result[0] != ADB_DEVICE_GUID) {
                        throw AdbProtocolException("Invalid wireless pairing response")
                    }
                    val end = result.indexOf(0, startIndex = 1).let { if (it < 0) result.size else it }
                    result.copyOfRange(1, end).toString(Charsets.UTF_8).also {
                        if (it.isBlank()) throw AdbProtocolException("Empty wireless device GUID")
                    }
                } finally {
                    spake.destroy()
                    password.fill(0)
                }
            }
        } finally {
            raw.close()
        }
    }

    private fun writePacket(output: DataOutputStream, type: Int, payload: ByteArray) {
        output.writeByte(1)
        output.writeByte(type)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun readPacket(input: DataInputStream, expectedType: Int): ByteArray {
        val version = input.readUnsignedByte()
        val type = input.readUnsignedByte()
        val length = input.readInt()
        if (version != 1 || type != expectedType || length !in 1..MAX_PAIRING_PAYLOAD) {
            throw AdbProtocolException("Invalid wireless pairing packet")
        }
        return ByteArray(length).also { input.readFully(it) }
    }

    private class PairingCipher(secret: ByteArray) {
        private val key: SecretKeySpec
        private var sendCounter = 0L
        private var receiveCounter = 0L

        init {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(secret)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val output = mac.doFinal("adb pairing_auth aes-128-gcm key".toByteArray() + byteArrayOf(1))
            key = SecretKeySpec(output.copyOf(16), "AES")
            prk.fill(0)
        }

        fun encrypt(payload: ByteArray): ByteArray = process(Cipher.ENCRYPT_MODE, payload, sendCounter++)

        fun decrypt(payload: ByteArray): ByteArray = try {
            process(Cipher.DECRYPT_MODE, payload, receiveCounter++)
        } catch (error: Exception) {
            throw AdbAuthenticationException("Wireless pairing code was rejected")
        }

        private fun process(mode: Int, payload: ByteArray, counter: Long): ByteArray {
            val iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(payload)
        }
    }

    companion object {
        private const val SPAKE2_MSG = 0
        private const val PEER_INFO = 1
        private const val ADB_RSA_PUB_KEY: Byte = 0
        private const val ADB_DEVICE_GUID: Byte = 1
        private const val PEER_INFO_SIZE = 8192
        private const val MAX_PAIRING_PAYLOAD = 2 * PEER_INFO_SIZE
    }
}

private fun ByteArray.indexOf(value: Byte, startIndex: Int): Int {
    for (index in startIndex until size) if (this[index] == value) return index
    return -1
}
