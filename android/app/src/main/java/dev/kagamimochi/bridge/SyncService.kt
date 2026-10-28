package dev.kagamimochi.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private lateinit var shizuku: ShizukuClipboard
    private lateinit var discovery: Discovery
    private val plugins = mutableListOf<BridgePlugin>()
    private lateinit var media: MediaPlugin
    private var loop: Job? = null

    // Cuts the retry wait short when the network changes
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var currentSocket: WebSocket? = null
    @Volatile private var defaultNetwork: Network? = null
    private var skipInitialNetworkEvent = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bridge", "Bridge", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "bridge")
            .setContentTitle("Bridge")
            .setContentText("Syncing with Linux")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        shizuku = ShizukuClipboard(this)
        discovery = Discovery(this)
        // Register new features here
        plugins += ClipboardPlugin(shizuku, scope)
        media = MediaPlugin(this, shizuku, scope)
        plugins += media
        watchNetwork()
    }

    /**
     * Reconnects right away when the default network changes (Wi-Fi reconnect,
     * Wi-Fi <-> mobile data) instead of waiting for the ping timeout.
     */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        // Right after registering, the callback reports the current network once
        skipInitialNetworkEvent = cm.activeNetwork != null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                defaultNetwork = network
                if (skipInitialNetworkEvent) {
                    skipInitialNetworkEvent = false
                    return
                }
                Status.event("network changed, reconnecting")
                // The open socket is tied to the old network, so drop it and retry now
                currentSocket?.cancel()
                wake.trySend(Unit)
            }

            override fun onLost(network: Network) {
                // Ignore the old network being dropped after a new one took over
                if (network != defaultNetwork) return
                defaultNetwork = null
                Status.event("network lost")
                currentSocket?.cancel()
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Status.event("network monitor unavailable: ${e.message}")
        }
    }

    /** Waits up to [ms]. Returns true if it was cut short by a network change. */
    private suspend fun pause(ms: Long): Boolean =
        withTimeoutOrNull(ms) { wake.receive() } != null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Tapped a button on the Linux media notification
        if (intent?.action == MediaPlugin.ACTION_CONTROL) {
            media.sendControl(intent.getStringExtra(MediaPlugin.EXTRA_ACTION))
            return START_STICKY
        }
        if (loop == null) {
            // Keep trying to bind Shizuku until it succeeds
            scope.launch {
                while (isActive) {
                    shizuku.ensureBound()
                    delay(1000)
                }
            }
            loop = scope.launch {
                var cached: Pair<String, Int>? = null
                // Consecutive failed attempts; drives the retry backoff
                var failures = 0
                while (isActive) {
                    val cfg = Config.load(this@SyncService)
                    val pairing = cfg.pairing
                    if (pairing == null) {
                        Status.connection = "not paired"
                        delay(3000)
                        continue
                    }
                    val target = manualTarget(cfg.host) ?: cached ?: run {
                        Status.connection = "searching on the local network"
                        discovery.find(pairing.fingerprint.take(16))?.also {
                            cached = it
                            Status.event("found ${it.first}:${it.second}")
                        }
                    }
                    if (target == null) {
                        failures++
                    } else {
                        val startedAt = SystemClock.elapsedRealtime()
                        val authenticated = connectOnce(target, pairing)
                        // The address may have changed, so search again next time
                        if (!authenticated) cached = null
                        // A connection that dies right after authenticating still counts as a failure
                        val stable = authenticated &&
                            SystemClock.elapsedRealtime() - startedAt >= STABLE_CONNECTION_MS
                        failures = if (stable) 0 else failures + 1
                    }
                    val wait = backoffMs(failures)
                    Status.retry = "retrying in ${wait / 1000}s"
                    val woken = pause(wait)
                    Status.retry = ""
                    if (woken) {
                        // Network changed: start over with a short settle time
                        failures = 0
                        delay(NETWORK_SETTLE_MS)
                    }
                }
            }
        }
        return START_STICKY
    }

    /** 1s, 2s, 4s, 8s, 16s, then 30s between attempts. */
    private fun backoffMs(failures: Int): Long =
        min(BACKOFF_MIN_MS shl min(failures, 5), BACKOFF_MAX_MS)

    private fun manualTarget(host: String): Pair<String, Int>? {
        val h = host.trim()
        if (h.isEmpty()) return null
        val parts = h.split(":")
        return parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 8765)
    }

    /** Returns true if the connection reached the authenticated state. */
    private suspend fun connectOnce(target: Pair<String, Int>, pairing: Pairing): Boolean {
        val closed = CompletableDeferred<Unit>()
        var authenticated = false
        Status.connection = "connecting to ${target.first}:${target.second}"

        // Certificate pinning replaces the normal CA and hostname checks
        val trustManager = PinnedTrustManager(pairing.fingerprint)
        val client = baseClient.withTrustManager(trustManager)

        val request = Request.Builder().url("wss://${target.first}:${target.second}").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Status.connection = "secure channel open, authenticating"
                webSocket.send(
                    envelope(
                        "hello",
                        JSONObject().put("device_id", pairing.deviceId).put("token", pairing.token)
                    )
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                val type = msg.optString("type")
                val data = msg.optJSONObject("data") ?: JSONObject()
                if (type == "hello.ok") {
                    authenticated = true
                    Status.connection = "connected (encrypted)"
                    Status.event("authenticated")
                    val sender = Sender { t, d -> webSocket.send(envelope(t, d)) }
                    plugins.forEach { it.onConnected(sender) }
                    return
                }
                plugins.firstOrNull { type.startsWith(it.prefix + ".") }?.onMessage(type, data)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Finish the close handshake; otherwise onClosed may not fire until a ping fails
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Status.connection = "closed ($code $reason)"
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Status.connection = "failed: ${t.javaClass.simpleName} ${t.message}"
                closed.complete(Unit)
            }
        })
        currentSocket = socket
        try {
            closed.await()
        } finally {
            currentSocket = null
            plugins.forEach { it.onDisconnected() }
            socket.cancel()
        }
        return authenticated
    }

    override fun onDestroy() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        scope.cancel()
        shizuku.unbind()
        Status.connection = "stopped"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val BACKOFF_MIN_MS = 1_000L
        const val BACKOFF_MAX_MS = 30_000L
        const val STABLE_CONNECTION_MS = 10_000L
        const val NETWORK_SETTLE_MS = 500L
    }
}
