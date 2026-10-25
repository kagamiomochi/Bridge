package dev.kagamimochi.bridge

import android.content.ClipData
import android.os.IBinder
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Runs in a Shizuku-started process with the shell UID (2000), so it may access
 * the clipboard service from the background. The IClipboard method signatures
 * differ between Android versions, so arguments are built from parameter types.
 * Errors are written to logcat with the tag "BridgeClip".
 */
class ClipboardUserService : IClipboardService.Stub() {

    private val tag = "BridgeClip"
    private val iface = Class.forName("android.content.IClipboard")

    private val clipboard: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "clipboard") as IBinder
        Class.forName("android.content.IClipboard\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    private fun call(name: String, clip: ClipData?): Any? {
        return try {
            val method: Method = iface.methods.first { it.name == name }
            var stringSeen = false
            val args = method.parameterTypes.map { type ->
                when {
                    type == ClipData::class.java -> clip
                    type == String::class.java -> {
                        // First String is the calling package, the rest (attribution tag) are null
                        if (!stringSeen) { stringSeen = true; "com.android.shell" } else null
                    }
                    type == Int::class.javaPrimitiveType -> 0 // userId / deviceId
                    else -> null
                }
            }.toTypedArray()
            method.invoke(clipboard, *args)
        } catch (e: InvocationTargetException) {
            Log.e(tag, "$name failed", e.targetException)
            null
        } catch (e: Exception) {
            Log.e(tag, "$name failed", e)
            null
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun getText(): String? {
        val clip = call("getPrimaryClip", null) as? ClipData ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    override fun setText(text: String?) {
        if (text == null) return
        call("setPrimaryClip", ClipData.newPlainText("bridge", text))
    }
}
