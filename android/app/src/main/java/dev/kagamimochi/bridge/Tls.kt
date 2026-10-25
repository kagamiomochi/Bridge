package dev.kagamimochi.bridge

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Accepts only the server certificate whose SHA-256 matches the paired fingerprint. */
class PinnedTrustManager(private val fingerprintHex: String) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("empty certificate chain")
        val actual = sha256(chain[0].encoded).toHex()
        if (!MessageDigest.isEqual(actual.toByteArray(), fingerprintHex.toByteArray())) {
            throw CertificateException("certificate fingerprint mismatch")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Accepts any certificate and only records its SHA-256. Used ONLY while pairing:
 * the code-based proof is bound to this fingerprint, so a man-in-the-middle
 * presenting a different certificate makes the proof check fail.
 */
class RecordingTrustManager : X509TrustManager {

    @Volatile
    var fingerprint: ByteArray? = null

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("empty certificate chain")
        fingerprint = sha256(chain[0].encoded)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Builds a client that trusts only via [trustManager] and skips hostname checks. */
fun OkHttpClient.withTrustManager(trustManager: X509TrustManager): OkHttpClient {
    val ssl = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), null)
    }
    return newBuilder()
        .sslSocketFactory(ssl.socketFactory, trustManager)
        .hostnameVerifier { _, _ -> true }
        .build()
}
