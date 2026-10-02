package ernest.ascrcpy.adb.crypto

import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.Principal
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt

/** TLS credentials backed by the same RSA key as classic ADB authorization. */
internal class WirelessTls(private val identity: KeyPair) {
    private val certificate: X509Certificate by lazy {
        // Match adb's GenerateX509Certificate(). Some adbd implementations reject
        // a paired key when its TLS certificate lacks the ADB CA/key-usage fields.
        val name = X500Name("C=US,O=Android,CN=Adb")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            Date(now),
            Date(now + 3650L * 24 * 60 * 60 * 1000),
            name,
            identity.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
        )
        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            JcaX509ExtensionUtils().createSubjectKeyIdentifier(identity.public),
        )
        val signed = builder.build(JcaContentSignerBuilder("SHA256withRSA").build(identity.private))
        CertificateFactory.getInstance("X.509")
            .generateCertificate(signed.encoded.inputStream()) as X509Certificate
    }

    private val context: SSLContext by lazy {
        SSLContext.getInstance("TLS", Conscrypt.newProvider()).apply {
            init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        }
    }

    fun wrap(socket: Socket, host: String, port: Int, readTimeoutMillis: Int = 0): SSLSocket {
        val tls = context.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        try {
            tls.enabledProtocols = arrayOf("TLSv1.3")
            tls.soTimeout = 15_000
            tls.startHandshake()
            tls.soTimeout = readTimeoutMillis
            return tls
        } catch (error: Throwable) {
            tls.close()
            throw error
        }
    }

    private val keyManager = object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
            if (keyType == null || keyType.contains("RSA", true)) arrayOf("adb") else emptyArray()

        override fun chooseClientAlias(
            keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?,
        ): String? = if (keyType == null || keyType.any { it.contains("RSA", true) }) "adb" else null

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(
            keyType: String?, issuers: Array<out Principal>?, socket: Socket?,
        ): String? = null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == "adb") arrayOf(certificate) else null

        override fun getPrivateKey(alias: String?) = if (alias == "adb") identity.private else null
    }

    private val trustManager = object : X509TrustManager {
        // The pairing code authenticates the initial TLS connection through SPAKE2.
        // Wireless adbd then authorizes our previously paired client certificate.
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
