package dev.kagamimochi.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
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
import kotlinx.coroutines.selects.select
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

    // The ongoing notification is shown only while authenticated and the service is alive
    private val foregroundLock = Any()
    private var connected = false
    private var inForeground = false
    private var destroyed = false
    // Name of the paired device, shown in the notification
    @Volatile private var peerName = ""

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Bridge", NotificationManager.IMPORTANCE_MIN))

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

    private fun buildNotification(deferred: Boolean): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Bridge")
            .setContentText("Connected to ${peerName.ifEmpty { "Linux" }}")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
        // Lets the system skip showing the notification if it is removed right away
        if (deferred && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
        }
        return builder.build()
    }

    /**
     * Applies the connection state to the foreground state.
     * Connected: foreground with the notification. Otherwise: no notification.
     */
    private fun applyForegroundState() = synchronized(foregroundLock) {
        if (destroyed) return@synchronized
        if (connected) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(deferred = false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                inForeground = true
            } catch (e: Exception) {
                // The system may refuse to enter the foreground from the background
                Status.event("could not show notification: ${e.message}")
            }
            return@synchronized
        }
        if (!inForeground) {
            // startForegroundService() requires startForeground() to be called,
            // so call it once and remove the notification right away
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(deferred = true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (e: Exception) {
                Status.event("could not enter foreground: ${e.message}")
            }
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        inForeground = false
    }

    private fun setConnected(value: Boolean) {
        synchronized(foregroundLock) {
            if (connected == value) return
            connected = value
        }
        applyForegroundState()
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
        // Started by startForegroundService(): must call startForeground() within a few seconds
        if (intent != null) applyForegroundState()
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
                        val outcome = connectOnce(target, pairing)
                        val authenticated = outcome != Outcome.NOT_AUTHENTICATED
                        // The address may have changed, so search again next time
                        if (!authenticated) cached = null
                        if (outcome == Outcome.SERVER_SHUTDOWN) {
                            // The Linux side stopped on purpose (PC shutdown): do not retry,
                            // wait until it announces itself again
                            failures = 0
                            cached = waitForServer(pairing)
                            continue
                        }
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

    /**
     * Waits without retrying until the daemon shows up on mDNS again.
     * One discovery runs for the whole wait, so a stale cache entry cannot start a retry loop.
     * A network change restarts the discovery on the new network instead of ending the wait.
     * Returns the address the daemon announced.
     */
    private suspend fun waitForServer(pairing: Pairing): Pair<String, Int> {
        val prefix = pairing.fingerprint.take(16)
        Status.connection = "Linux shut down, waiting for it to start"
        Status.event("Linux closed the connection on purpose, standing by")
        wake.tryReceive() // drop a network event that arrived before the wait started
        var first = true
        while (true) {
            val appeared = Channel<Pair<String, Int>>(Channel.CONFLATED)
            // Right after the shutdown the mDNS cache may still hold the old record: ignore it once
            val readyAt = SystemClock.elapsedRealtime() + if (first) STANDBY_GRACE_MS else 0L
            first = false
            val stop = discovery.scan {
                if (it.fpPrefix == prefix && SystemClock.elapsedRealtime() >= readyAt) {
                    appeared.trySend(it.host to it.port)
                }
            }
            try {
                val target = select<Pair<String, Int>?> {
                    appeared.onReceive { it }
                    wake.onReceive { null }
                }
                if (target != null) {
                    Status.event("Linux is back at ${target.first}:${target.second}")
                    return target
                }
            } finally {
                stop()
            }
            delay(NETWORK_SETTLE_MS)
        }
    }

    private enum class Outcome { NOT_AUTHENTICATED, DISCONNECTED, SERVER_SHUTDOWN }

    /** Tells how the connection ended: it never authenticated, it dropped, or the server closed it on purpose. */
    private suspend fun connectOnce(target: Pair<String, Int>, pairing: Pairing): Outcome {
        val closed = CompletableDeferred<Unit>()
        var authenticated = false
        var serverShutdown = false
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
                    peerName = pairing.serverName
                    setConnected(true)
                    return
                }
                plugins.firstOrNull { type.startsWith(it.prefix + ".") }?.onMessage(type, data)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Close code 1001 after authenticating means the daemon is stopping on purpose
                if (authenticated && code == CLOSE_GOING_AWAY) serverShutdown = true
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
            setConnected(false)
            plugins.forEach { it.onDisconnected() }
            socket.cancel()
        }
        return when {
            serverShutdown -> Outcome.SERVER_SHUTDOWN
            authenticated -> Outcome.DISCONNECTED
            else -> Outcome.NOT_AUTHENTICATED
        }
    }

    override fun onDestroy() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        synchronized(foregroundLock) {
            destroyed = true
            connected = false
        }
        scope.cancel()
        media.close()
        shizuku.unbind()
        Status.connection = "stopped"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "bridge"
        const val NOTIFICATION_ID = 1
        const val BACKOFF_MIN_MS = 1_000L
        const val BACKOFF_MAX_MS = 30_000L
        const val STABLE_CONNECTION_MS = 10_000L
        const val NETWORK_SETTLE_MS = 500L
        const val STANDBY_GRACE_MS = 3_000L
        const val CLOSE_GOING_AWAY = 1001
    }
}
