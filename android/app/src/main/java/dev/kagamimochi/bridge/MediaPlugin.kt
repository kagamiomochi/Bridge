package dev.kagamimochi.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Now-playing sync.
 *
 * Phone -> Linux: the active media session is sent as `media.state`, and its cover
 * art as `media.art` (once per track).
 * Linux -> phone: the Linux player is shown in the system media controls, with cover
 * art and a seek bar. Button presses and seeks are sent back as `media.control`.
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

    // Position that was last sent to Linux: (position ms, elapsedRealtime, playing)
    @Volatile private var positionRef: Triple<Long, Long, Boolean>? = null
    @Volatile private var artSentId: String? = null

    // Last things received from Linux
    @Volatile private var remoteState: JSONObject? = null
    @Volatile private var remoteArt: Pair<String, Bitmap>? = null
    @Volatile private var remotePlaying = false

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
            override fun onSeekTo(pos: Long) {
                // Move the bar right away; Linux sends the real position afterwards
                applyPlaybackState(remotePlaying, pos)
                sendControl("seek", pos)
            }
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
        positionRef = null
        artSentId = null
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
        remoteState = null
        remoteArt = null
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
        var art: Bitmap? = null
        var position = 0L
        var playing = false
        if (c == null) {
            state.put("status", "stopped")
        } else {
            val md = c.metadata
            val ps = c.playbackState
            playing = ps?.state == PlaybackState.STATE_PLAYING
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
            val duration = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0)
            state.put("duration_ms", duration)
            position = currentPosition(ps).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
            state.put("position_ms", position)
            art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            // Identifies the track, so Linux can tell which cover belongs to this state
            state.put("art_id", hash16(listOf("player", "title", "artist", "album").joinToString("|") { state.optString(it) }))
        }
        // Position is not part of the signature, so it does not cause constant re-sends
        val signature = listOf("status", "player", "title", "artist", "album", "duration_ms")
            .joinToString("|") { state.optString(it) }
        val now = SystemClock.elapsedRealtime()
        val ref = positionRef
        val changed = signature != lastSignature
        // A seek shows up as a gap between the reported position and the expected one
        val drifted = !changed && ref != null &&
            abs(position - (ref.first + if (ref.third) now - ref.second else 0L)) > DRIFT_MS
        if (changed || drifted) {
            lastSignature = signature
            positionRef = Triple(position, now, playing)
            Status.media = "sent: ${state.optString("title")} (${state.optString("status")})"
            sender?.send("media.state", state)
        }
        // The cover is sent separately, once per track, because it is much larger than the state
        val artId = state.optString("art_id")
        if (art != null && artId.isNotEmpty() && artId != artSentId) {
            artSentId = artId
            sendArt(artId, art)
        }
    }

    /** The position right now. PlaybackState.position is only valid at lastPositionUpdateTime. */
    private fun currentPosition(ps: PlaybackState?): Long {
        if (ps == null) return 0L
        if (ps.state != PlaybackState.STATE_PLAYING) return ps.position
        val elapsed = SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
        return ps.position + (elapsed * ps.playbackSpeed).toLong()
    }

    private fun hash16(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    /** Scales the cover down and sends it as a JPEG. */
    private fun sendArt(id: String, bmp: Bitmap) {
        val target = sender ?: return
        scope.launch(Dispatchers.Default) {
            val scale = minOf(1f, ART_MAX_PX.toFloat() / maxOf(bmp.width, bmp.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val bytes = out.toByteArray()
            if (bytes.size > ART_MAX_BYTES) {
                Status.event("media: cover art too large, skipped")
                return@launch
            }
            target.send(
                "media.art",
                JSONObject().put("id", id).put("mime", "image/jpeg")
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            )
        }
    }

    // ---- Linux -> phone ----

    override fun onMessage(type: String, data: JSONObject) {
        when (type) {
            "media.state" -> showRemote(data)
            "media.art" -> receiveArt(data)
            "media.control" -> {
                val action = data.optString("action")
                val position = data.optLong("position_ms", -1L)
                main.post { control(action, position) }
            }
        }
    }

    /** Operates the phone's player. Falls back to a media key when there is no session. */
    private fun control(action: String, positionMs: Long) {
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
                "seek" -> if (positionMs >= 0) t.seekTo(positionMs)
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

    /** Called from the system media controls and the notification buttons. */
    fun sendControl(action: String?, positionMs: Long? = null) {
        if (action == null) return
        val data = JSONObject().put("action", action)
        if (positionMs != null) data.put("position_ms", positionMs)
        sender?.send("media.control", data)
    }

    private fun receiveArt(data: JSONObject) {
        val id = data.optString("id")
        val encoded = data.optString("data")
        if (id.isEmpty() || encoded.isEmpty()) return
        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return
        }
        val bmp = decodeBitmap(bytes) ?: return
        remoteArt = id to bmp
        // The cover usually arrives right after the state, so show the track again with it
        remoteState?.let { showRemote(it) }
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > ART_MAX_PX * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun applyPlaybackState(playing: Boolean, position: Long) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                // With speed 1.0 the system advances the position by itself while playing
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    position, if (playing) 1f else 0f
                )
                .build()
        )
    }

    private fun showRemote(data: JSONObject) {
        val status = data.optString("status", "stopped")
        val title = data.optString("title")
        if (status == "stopped" || title.isEmpty()) {
            remoteState = null
            remotePlaying = false
            hideRemote()
            Status.media = "received: nothing playing on Linux"
            return
        }
        remoteState = data
        Status.media = "received: $title ($status)"
        val artist = data.optString("artist")
        val album = data.optString("album")
        val text = listOf(artist, album).filter { it.isNotEmpty() }.joinToString(" - ")
        val playing = status == "playing"
        remotePlaying = playing
        val duration = data.optLong("duration_ms", 0L)
        val position = data.optLong("position_ms", 0L)
        // Only use the cover if it belongs to this track
        val artId = data.optString("art_id")
        val art = remoteArt?.takeIf { artId.isNotEmpty() && it.first == artId }?.second

        // Update the session first: the system media controls are built from it
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
        // The seek bar needs a duration
        if (duration > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
        if (art != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
        session.setMetadata(metadata.build())
        applyPlaybackState(playing, position)
        session.isActive = true

        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Linux" + data.optString("player").let { if (it.isEmpty()) "" else ": $it" })
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(action(R.drawable.ic_media_previous_flat, "Previous", "previous", 1))
            .addAction(
                if (playing) action(R.drawable.ic_media_pause_flat, "Pause", "play_pause", 2)
                else action(R.drawable.ic_media_play_flat, "Play", "play_pause", 2)
            )
            .addAction(action(R.drawable.ic_media_next_flat, "Next", "next", 3))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        if (art != null) builder.setLargeIcon(art)
        nm.notify(NOTIFICATION_ID, builder.build())
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

        // Cover art is scaled down to this size before sending, and dropped above the byte limit
        private const val ART_MAX_PX = 512
        private const val ART_MAX_BYTES = 600_000

        // A position this far off the expected one is treated as a seek and re-sent
        private const val DRIFT_MS = 1500L
    }
}
