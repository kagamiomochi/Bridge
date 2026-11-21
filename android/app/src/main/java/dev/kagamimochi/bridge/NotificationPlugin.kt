package dev.kagamimochi.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Notification sync in both directions.
 *
 * Phone -> Linux: a posted (or updated) notification is sent as `notify.posted`,
 * and `notify.removed` follows when the user dismisses it. Linux shows it with
 * notify-send and closes the bubble again on removal.
 *
 * Linux -> phone: notifications from the desktop arrive as `notify.posted` and
 * are shown with the system notification UI; `notify.removed` cancels them.
 *
 * Only notifications the user can dismiss are mirrored to Linux. Ongoing
 * notifications (media players, downloads, foreground services) and group
 * summaries are left out: media already has its own plugin, and summaries repeat
 * their children.
 */
class NotificationPlugin(
    private val context: Context,
    private val shizuku: ShizukuClipboard,
    private val scope: CoroutineScope,
) : BridgePlugin, NotificationRelay.Listener {

    override val prefix = "notify"

    private val nm = context.getSystemService(NotificationManager::class.java)
    @Volatile private var sender: Sender? = null
    private var job: Job? = null

    // Notification ids for what Linux sent, so updates replace and removals cancel
    private val remoteIds = ConcurrentHashMap<String, Int>()
    private val nextRemoteId = AtomicInteger(REMOTE_ID_BASE)

    // Keys that were sent, so a removal is not reported for something Linux never saw
    private val sent = LinkedHashSet<String>()
    private val labels = ConcurrentHashMap<String, String>()

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Linux notifications", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun remember(key: String) = synchronized(sent) {
        sent.remove(key)
        sent.add(key)
        if (sent.size > MAX_TRACKED) sent.remove(sent.first())
    }

    private fun forget(key: String): Boolean = synchronized(sent) { sent.remove(key) }

    override fun onConnected(sender: Sender) {
        this.sender = sender
        NotificationRelay.listener = this
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!BridgeListenerService.isEnabled(context)) {
                    shizuku.enableNotificationListener(
                        BridgeListenerService.component(context).flattenToString()
                    )
                }
                Status.notifications = if (BridgeListenerService.isEnabled(context)) {
                    "watching"
                } else {
                    "waiting for listener permission"
                }
                delay(5000)
            }
        }.also { job = it }
    }

    override fun onDisconnected() {
        job?.cancel()
        sender = null
        NotificationRelay.listener = null
        Status.notifications = "stopped"
    }

    override fun onMessage(type: String, data: JSONObject) {
        when (type) {
            "notify.posted" -> showRemote(data)
            "notify.removed" -> hideRemote(data.optString("key"))
        }
    }

    /** Shows a notification that Linux posted. */
    private fun showRemote(data: JSONObject) {
        val key = data.optString("key").take(MAX_KEY)
        if (key.isEmpty()) return
        val app = data.optString("app").ifEmpty { "Linux" }.take(MAX_APP)
        val title = data.optString("title").ifEmpty { app }.take(MAX_TITLE)
        val text = data.optString("text").take(MAX_TEXT)
        val previous = remoteIds[key]
        val id = previous ?: nextRemoteId.getAndIncrement().also { remoteIds[key] = it }
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(app)
            .setWhen(data.optLong("time", System.currentTimeMillis()))
            .setShowWhen(true)
            .setAutoCancel(true)
            // An update should not ring or vibrate again
            .setOnlyAlertOnce(previous != null)
        if (text.isNotEmpty()) {
            builder.setStyle(Notification.BigTextStyle().bigText(text))
        }
        nm.notify(id, builder.build())
        Status.event("received notification from Linux: $app - $title")
    }

    /** Cancels a notification when Linux dismissed or it expired there. */
    private fun hideRemote(key: String) {
        val id = remoteIds.remove(key) ?: return
        nm.cancel(id)
    }

    /** Releases the mirrored notifications. Called when the service is destroyed. */
    fun close() {
        remoteIds.values.toList().forEach { nm.cancel(it) }
        remoteIds.clear()
    }

    override fun onPosted(sbn: StatusBarNotification) {
        val target = sender ?: return
        val data = describe(sbn) ?: return
        remember(data.getString("key"))
        Status.event("sent notification: ${data.optString("app")} - ${data.optString("title")}")
        target.send("notify.posted", data)
    }

    override fun onRemoved(sbn: StatusBarNotification) {
        val target = sender ?: return
        val key = sbn.key ?: return
        if (!forget(key)) return
        target.send("notify.removed", JSONObject().put("key", key.take(MAX_KEY)))
    }

    /** Builds the message, or returns null for notifications that are not mirrored. */
    private fun describe(sbn: StatusBarNotification): JSONObject? {
        if (sbn.packageName == context.packageName) return null // never echo our own notifications
        val notification = sbn.notification ?: return null
        if (!sbn.isClearable) return null
        val flags = notification.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return null
        }
        val extras = notification.extras ?: return null

        fun text(vararg keys: String): String =
            keys.firstNotNullOfOrNull { key ->
                extras.getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            }.orEmpty()

        val app = labels.getOrPut(sbn.packageName) { appLabel(sbn.packageName) }
        val title = text(Notification.EXTRA_TITLE, Notification.EXTRA_CONVERSATION_TITLE)
            .ifEmpty { app }
        val body = text(
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
        )
        // Nothing to show beyond the app name
        if (title == app && body.isEmpty()) return null

        return JSONObject()
            .put("key", sbn.key.take(MAX_KEY))
            .put("app", app.take(MAX_APP))
            .put("title", title.take(MAX_TITLE))
            .put("text", body.take(MAX_TEXT))
            .put("time", sbn.postTime)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg // package visibility restrictions on newer Android versions
    }

    private companion object {
        const val CHANNEL = "bridge_notifications"
        // Keep clear of SyncService (1) and MediaPlugin (2)
        const val REMOTE_ID_BASE = 1000
        const val MAX_KEY = 256
        const val MAX_APP = 128
        const val MAX_TITLE = 512
        const val MAX_TEXT = 4096
        const val MAX_TRACKED = 200
    }
}
