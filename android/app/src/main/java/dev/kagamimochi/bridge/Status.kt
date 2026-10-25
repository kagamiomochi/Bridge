package dev.kagamimochi.bridge

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared runtime state so the UI can show what the service is doing. */
object Status {
    @Volatile var connection = "stopped"
    @Volatile var shizukuServiceBound = false
    @Volatile var lastEvent = "-"

    fun event(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lastEvent = "$time $message"
        Log.d("Bridge", message)
    }
}
