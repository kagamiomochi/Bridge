package dev.kagamimochi.bridge

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The single notification listener Bridge uses. Media sync needs it to read media
 * sessions, and notification sync receives posted and removed notifications here.
 * It is enabled through Shizuku, so the user does not have to open the settings.
 */
class BridgeListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) = NotificationRelay.posted(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = NotificationRelay.removed(sbn)

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, BridgeListenerService::class.java)

        fun isEnabled(context: Context): Boolean {
            val component = component(context).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            )
            return enabled?.split(":")?.contains(component) == true
        }
    }
}
