package dev.kagamimochi.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest

class ClipboardPlugin(
    private val shizuku: ShizukuClipboard,
    private val scope: CoroutineScope,
) : BridgePlugin {

    override val prefix = "clipboard"

    @Volatile private var lastHash: String? = null
    private var sender: Sender? = null
    private var job: Job? = null

    private fun hash(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    override fun onConnected(sender: Sender) {
        this.sender = sender
        job = scope.launch(Dispatchers.IO) {
            // Do not push whatever is already in the clipboard at connect time
            shizuku.getText()?.let { lastHash = hash(it) }
            while (isActive) {
                poll()
                delay(500)
            }
        }
    }

    override fun onDisconnected() {
        job?.cancel()
        sender = null
    }

    private fun poll() {
        val text = shizuku.getText() ?: return
        val h = hash(text)
        if (h == lastHash) return
        lastHash = h
        Status.event("sent clipboard (${text.length} chars)")
        sender?.send("clipboard.update", JSONObject().put("text", text))
    }

    override fun onMessage(type: String, data: JSONObject) {
        if (type != "clipboard.update") return
        val text = data.optString("text", "")
        if (text.isEmpty()) return
        lastHash = hash(text) // prevents echoing back
        Status.event("received clipboard (${text.length} chars)")
        shizuku.setText(text)
    }
}
