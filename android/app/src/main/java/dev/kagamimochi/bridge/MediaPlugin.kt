package dev.kagamimochi.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Now-playing sync.
 *
 * Phone -> Linux: the active media session is sent as `media.state`.
 * Linux -> phone: the Linux player is shown as a notification with
 * previous / play-pause / next buttons, which send `media.control` back.
 * `media.control` from Linux operates the phone's player.
 */
class MediaPlugin(
    private val context: Context,
    private val shizuku: ShizukuClipboard,
    private val scope: CoroutineScope,
) : BridgePlugin {

    override val prefix = "media"

    private val main = Handler(Looper.getMainLooper())
    private val listener = ComponentName(context, MediaListenerService::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)

    @Volatile private var sender: Sender? = null
    private var job: Job? = null

    // Only touched on the main thread
    private var attached = false
    private var current: MediaController? = null
    @Volatile private var lastSignature: String? = null

    /**
     * A media session that stands for the Linux player. With a MediaStyle notification
     * attached to it, Android shows it in the system media controls (quick settings,
     * notification shade, lock screen), and headset buttons are routed to it too.
     */
    private val session = MediaSession(context, "BridgeLinux").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = sendControl("play")
            override fun onPause() = sendControl("pause")
            override fun onSkipToNext() = sendControl("next")
            override fun onSkipToPrevious() = sendControl("previous")
        }, main)
    }

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Linux media", NotificationManager.IMPORTANCE_LOW)
        )
    }

    // ---- Phone -> Linux ----

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        select(list)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // A different app may have started playing, so choose the session again
            select(activeSessions())
        }
        override fun onSessionDestroyed() = select(activeSessions())
    }

    override fun onConnected(sender: Sender) {
        this.sender = sender
        lastSignature = null
        job = scope.launch(Dispatchers.IO) {
            // The listener may not be effective right after it was enabled, so retry until it works
            while (isActive) {
                if (!attached) {
                    if (!listenerEnabled()) shizuku.enableNotificationListener(listener.flattenToString())
                    if (listenerEnabled()) withContext(Dispatchers.Main) { attach() }
                }
                delay(5000)
            }
        }
    }

    override fun onDisconnected() {
        job?.cancel()
        sender = null
        hideRemote()
        main.post { detach() }
    }

    /** Releases the media session. Called when the service is destroyed. */
    fun close() {
        hideRemote()
        session.release()
    }

    private fun hideRemote() {
        nm.cancel(NOTIFICATION_ID)
        session.isActive = false
    }

    private fun activeSessions(): List<MediaController>? =
        try {
            sessions.getActiveSessions(listener)
        } catch (e: SecurityException) {
            null // permission was revoked; the next attach attempt will report it
        }

    private fun listenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return enabled?.split(":")?.contains(listener.flattenToString()) == true
    }

    private fun attach() {
        if (attached) return
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listener, main)
            select(sessions.getActiveSessions(listener))
            attached = true
            Status.event("media: listening to sessions")
        } catch (e: SecurityException) {
            // Not authorized yet; the retry loop will try again
            Status.event("media: waiting for listener permission")
        }
    }

    private fun detach() {
        if (!attached) return
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsListener) }
        current?.unregisterCallback(controllerCallback)
        current = null
        attached = false
    }

    /** Prefers a session that is playing; ignores this app's own sessions. */
    private fun select(list: List<MediaController>?) {
        val candidates = list.orEmpty().filter { it.packageName != context.packageName }
        val next = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull()
        if (next?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(controllerCallback)
            current = next
            next?.registerCallback(controllerCallback, main)
        }
        publish()
    }

    private fun publish() {
        val c = current
        val state = JSONObject()
        if (c == null) {
            state.put("status", "stopped")
        } else {
            val md = c.metadata
            val ps = c.playbackState
            state.put(
                "status", when (ps?.state) {
                    PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> "playing"
                    PlaybackState.STATE_PAUSED -> "paused"
                    else -> "stopped"
                }
            )
            state.put("player", c.packageName)
            state.put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
            state.put("artist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
            state.put("album", md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
            state.put("duration_ms", (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0))
            state.put("position_ms", (ps?.position ?: 0L).coerceAtLeast(0))
        }
        // Position is not part of the signature, so it does not cause constant re-sends
        val signature = listOf("status", "player", "title", "artist", "album", "duration_ms")
            .joinToString("|") { state.optString(it) }
        if (signature == lastSignature) return
        lastSignature = signature
        Status.media = "sent: ${state.optString("title")} (${state.optString("status")})"
        sender?.send("media.state", state)
    }

    // ---- Linux -> phone ----

    override fun onMessage(type: String, data: JSONObject) {
        when (type) {
            "media.state" -> showRemote(data)
            "media.control" -> main.post { control(data.optString("action")) }
        }
    }

    /** Operates the phone's player. Falls back to a media key when there is no session. */
    private fun control(action: String) {
        Status.event("media control from Linux: $action")
        val c = current
        if (c != null) {
            val t = c.transportControls
            when (action) {
                "play" -> t.play()
                "pause" -> t.pause()
                "play_pause" ->
                    if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                "next" -> t.skipToNext()
                "previous" -> t.skipToPrevious()
            }
            return
        }
        val key = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    /** Called from the notification buttons (via SyncService). */
    fun sendControl(action: String?) {
        if (action == null) return
        sender?.send("media.control", JSONObject().put("action", action))
    }

    private fun showRemote(data: JSONObject) {
        val status = data.optString("status", "stopped")
        val title = data.optString("title")
        if (status == "stopped" || title.isEmpty()) {
            hideRemote()
            Status.media = "received: nothing playing on Linux"
            return
        }
        Status.media = "received: $title ($status)"
        val artist = data.optString("artist")
        val album = data.optString("album")
        val text = listOf(artist, album).filter { it.isNotEmpty() }.joinToString(" - ")
        val playing = status == "playing"
        val duration = data.optLong("duration_ms", 0L)
        val position = data.optLong("position_ms", 0L)

        // Update the session first: the system media controls are built from it
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
        if (duration > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
        session.setMetadata(metadata.build())
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                // With speed 1.0 the system advances the position by itself while playing
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    position, if (playing) 1f else 0f
                )
                .build()
        )
        session.isActive = true

        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Linux" + data.optString("player").let { if (it.isEmpty()) "" else ": $it" })
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", "previous", 1))
            .addAction(
                if (playing) action(android.R.drawable.ic_media_pause, "Pause", "play_pause", 2)
                else action(android.R.drawable.ic_media_play, "Play", "play_pause", 2)
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", "next", 3))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun action(icon: Int, label: String, name: String, requestCode: Int): Notification.Action {
        val intent = Intent(context, SyncService::class.java)
            .setAction(ACTION_CONTROL)
            .putExtra(EXTRA_ACTION, name)
        val pi = PendingIntent.getService(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(context, icon), label, pi).build()
    }

    companion object {
        const val ACTION_CONTROL = "dev.kagamimochi.bridge.MEDIA_CONTROL"
        const val EXTRA_ACTION = "action"
        private const val CHANNEL = "bridge_media"
        private const val NOTIFICATION_ID = 2
    }
}
