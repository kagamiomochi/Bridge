package dev.kagamimochi.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the sync service after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        // Do nothing if the phone is not paired or the user pressed Stop
        if (Config.load(context).pairing == null || !Config.isServiceEnabled(context)) return
        try {
            context.startForegroundService(Intent(context, SyncService::class.java))
            Status.event("service started (${intent.action?.substringAfterLast('.')})")
        } catch (e: Exception) {
            // The system may refuse to start a foreground service from the background
            Status.event("could not start service: ${e.message}")
        }
    }
}
