package dev.kagamimochi.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Result of a successful pairing, stored on the phone. */
data class Pairing(
    val fingerprint: String, // SHA-256 of the server certificate (hex), pinned afterwards
    val deviceId: String,
    val token: String,
    val serverName: String,
)

object PairingClient {

    // Must match the daemon. The iteration count is fixed on purpose so that a
    // man-in-the-middle cannot weaken it.
    private const val PBKDF2_ITERATIONS = 100_000
    private const val CODE_LENGTH = 8

    fun normalizeCode(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() }
            .replace('O', '0').replace('I', '1').replace('L', '1')

    private fun pbkdf2(code: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(code.toCharArray(), salt, PBKDF2_ITERATIONS, 256))
            .encoded

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    suspend fun pair(
        host: String,
        port: Int,
        serverName: String,
        rawCode: String,
        deviceName: String,
        base: OkHttpClient = OkHttpClient(),
    ): Result<Pairing> {
        val code = normalizeCode(rawCode)
        if (code.length != CODE_LENGTH) {
            return Result.failure(IllegalArgumentException("The code must be $CODE_LENGTH characters"))
        }

        val recorder = RecordingTrustManager()
        val client = base.withTrustManager(recorder)
        val done = CompletableDeferred<Result<Pairing>>()
        val clientNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        var key: ByteArray? = null
        var serverNonce: ByteArray? = null

        val request = Request.Builder().url("wss://$host:$port").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    envelope(
                        "pair.request",
                        JSONObject().put("device_name", deviceName).put("client_nonce", clientNonce.toHex())
                    )
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val data = msg.optJSONObject("data") ?: JSONObject()
                    val fp = recorder.fingerprint ?: error("no server certificate")
                    when (msg.getString("type")) {
                        "pair.challenge" -> {
                            val sn = data.getString("server_nonce").hexToBytes()
                            val k = pbkdf2(code, sn)
                            key = k
                            serverNonce = sn
                            val proof = hmac(k, "client".toByteArray() + clientNonce + sn + fp)
                            webSocket.send(envelope("pair.proof", JSONObject().put("proof", proof.toHex())))
                        }
                        "pair.ok" -> {
                            val k = key ?: error("unexpected pair.ok")
                            val sn = serverNonce ?: error("unexpected pair.ok")
                            // Verify that the server also knows the code (and sees the same certificate)
                            val expected = hmac(k, "server".toByteArray() + clientNonce + sn + fp)
                            val actual = data.getString("server_proof").hexToBytes()
                            if (!MessageDigest.isEqual(expected, actual)) error("server proof mismatch")
                            done.complete(
                                Result.success(
                                    Pairing(fp.toHex(), data.getString("device_id"), data.getString("token"), serverName)
                                )
                            )
                        }
                        "pair.denied" ->
                            done.complete(Result.failure(Exception(data.optString("reason", "denied"))))
                    }
                } catch (e: Exception) {
                    done.complete(Result.failure(e))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(Result.failure(Exception("connection closed ($code $reason)")))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.complete(Result.failure(t))
            }
        })

        try {
            return withTimeoutOrNull(30_000) { done.await() }
                ?: Result.failure(Exception("timed out"))
        } finally {
            socket.cancel()
        }
    }
}
