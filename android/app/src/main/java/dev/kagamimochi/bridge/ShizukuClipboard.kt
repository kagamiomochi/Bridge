package dev.kagamimochi.bridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/** Client-side wrapper that binds the Shizuku user service. */
class ShizukuClipboard(context: Context) {

    @Volatile
    private var service: IClipboardService? = null
    private var lastBindAttempt = 0L

    val isBound: Boolean get() = service != null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ClipboardUserService::class.java.name)
    ).processNameSuffix("clipboard").daemon(false).version(2)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IClipboardService.Stub.asInterface(binder)
            Status.shizukuServiceBound = true
            Status.event("Shizuku user service bound")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            Status.shizukuServiceBound = false
            Status.event("Shizuku user service disconnected")
        }
    }

    /** Safe to call repeatedly; retries at most every 5 seconds. */
    fun ensureBound() {
        if (service != null) return
        val now = System.currentTimeMillis()
        if (now - lastBindAttempt < 5000) return
        lastBindAttempt = now
        if (!Shizuku.pingBinder()) {
            Status.event("Shizuku is not running")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Status.event("Shizuku permission not granted")
            return
        }
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { Status.event("bindUserService failed: ${it.message}") }
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        Status.shizukuServiceBound = false
    }

    fun getText(): String? = runCatching { service?.getText() }.getOrNull()

    fun setText(text: String) {
        runCatching { service?.setText(text) }
    }

    fun enableNotificationListener(component: String) {
        runCatching { service?.enableNotificationListener(component) }
    }
}
