package ernest.ascrcpy.adb

import java.security.KeyPair

/** Supplies the RSA identity used when an adbd instance requests authentication. */
interface AdbKeyProvider {
    fun sign(token: ByteArray): ByteArray
    fun encodedPublicKey(): ByteArray
}

/** Exposes the persistent RSA identity for Android 11+ wireless TLS. */
interface AdbTlsKeyProvider : AdbKeyProvider {
    val keyPair: KeyPair
}
