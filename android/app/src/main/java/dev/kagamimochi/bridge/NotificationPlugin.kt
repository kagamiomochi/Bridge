package dev.kagamimochi.bridge

import android.app.Notification
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

/**
 * Notification sync from the phone to Linux.
 *
 * A posted (or updated) notification is sent as `notify.posted`; when the user
 * dismisses it, `notify.removed` follows. Linux shows it with notify-send and
 * closes the bubble again on removal.
 *
 * Only notifications the user can dismiss are mirrored. Ongoing notifications
 * (media players, downloads, foreground services) and group summaries are left
 * out: media already has its own plugin, and summaries repeat their children.
 */
class NotificationPlugin(
    private val context: Context,
    private val shizuku: ShizukuClipboard,
    private val scope: CoroutineScope,
) : BridgePlugin, NotificationRelay.Listener {

    override val prefix = "notify"

    @Volatile private var sender: Sender? = null
    private var job: Job? = null

    // Keys that were sent, so a removal is not reported for something Linux never saw
    private val sent = LinkedHashSet<String>()
    private val labels = ConcurrentHashMap<String, String>()

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
        // Linux cannot dismiss a phone notification from the desktop (yet)
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
        const val MAX_KEY = 256
        const val MAX_APP = 128
        const val MAX_TITLE = 512
        const val MAX_TEXT = 4096
        const val MAX_TRACKED = 200
    }
}
