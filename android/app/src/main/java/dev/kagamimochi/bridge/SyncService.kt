package dev.kagamimochi.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private lateinit var shizuku: ShizukuClipboard
    private lateinit var discovery: Discovery
    private val plugins = mutableListOf<BridgePlugin>()
    private var loop: Job? = null

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                        delay(3000)
                        continue
                    }
                    val authenticated = connectOnce(target, pairing)
                    // The address may have changed, so search again next time
                    if (!authenticated) cached = null
                    delay(3000)
                }
            }
        }
        return START_STICKY
    }

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

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Status.connection = "closed ($code $reason)"
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Status.connection = "failed: ${t.javaClass.simpleName} ${t.message}"
                closed.complete(Unit)
            }
        })
        try {
            closed.await()
        } finally {
            plugins.forEach { it.onDisconnected() }
            socket.cancel()
        }
        return authenticated
    }

    override fun onDestroy() {
        scope.cancel()
        shizuku.unbind()
        Status.connection = "stopped"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
