package dev.kagamimochi.bridge

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty. Having this listener enabled is what allows the app to call
 * MediaSessionManager.getActiveSessions(). Bridge enables it through Shizuku, so the
 * user does not have to open the system settings.
 */
class MediaListenerService : NotificationListenerService()
