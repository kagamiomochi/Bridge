package dev.kagamimochi.bridge

import android.service.notification.StatusBarNotification

/**
 * Passes notification events from [BridgeListenerService] to [NotificationPlugin].
 * Both live in the same process, so a plain object is enough. The listener is null
 * while the sync service is not connected, and events are then dropped.
 */
object NotificationRelay {

    interface Listener {
        fun onPosted(sbn: StatusBarNotification)
        fun onRemoved(sbn: StatusBarNotification)
    }

    @Volatile
    var listener: Listener? = null

    fun posted(sbn: StatusBarNotification) {
        listener?.onPosted(sbn)
    }

    fun removed(sbn: StatusBarNotification) {
        listener?.onRemoved(sbn)
    }
}
