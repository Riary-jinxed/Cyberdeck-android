package dev.netrunner.launcher.media

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.netrunner.launcher.notify.NetrunnerNotificationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NowPlaying(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val positionUpdatedAt: Long,
    val speed: Float,
    val durationMs: Long,
) {
    /** Current position, extrapolated from the last update while playing. */
    fun livePosition(now: Long = SystemClock.elapsedRealtime()): Long {
        val elapsed = if (playing) ((now - positionUpdatedAt) * speed).toLong() else 0L
        val pos = positionMs + elapsed
        return if (durationMs > 0) pos.coerceIn(0, durationMs) else pos.coerceAtLeast(0)
    }
}

/**
 * Follows whatever app is playing media (Spotify first when several are). Needs the
 * notification listener to be enabled: that is Android's gate for reading media sessions.
 */
class MediaWatcher(private val context: Context) {

    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, NetrunnerNotificationService::class.java)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private var controller: MediaController? = null
    private var started = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish()
            // Another player may have taken over while this one paused.
            if (state?.state != PlaybackState.STATE_PLAYING) pickFrom(currentSessions())
        }
        override fun onSessionDestroyed() = pickFrom(currentSessions())
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pickFrom(controllers.orEmpty())
    }

    /** Returns false when the notification listener access is missing. */
    fun start(): Boolean {
        if (started) return true
        val ok = runCatching {
            sessions?.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            pickFrom(currentSessions())
        }.isSuccess
        started = ok
        return ok
    }

    fun stop() {
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(callback)
        controller = null
        started = false
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs)
    }

    /** Opens the player app on its "now playing" screen. */
    @Suppress("DEPRECATION")
    fun openPlayer() {
        val c = controller ?: return
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }
        val sent = c.sessionActivity?.let {
            runCatching { it.send(context, 0, null, null, null, null, options); true }.getOrDefault(false)
        } ?: false
        if (!sent) {
            context.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun currentSessions(): List<MediaController> =
        runCatching { sessions?.getActiveSessions(listenerComponent).orEmpty() }.getOrDefault(emptyList())

    private fun pickFrom(controllers: List<MediaController>) {
        val best = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.packageName == SPOTIFY }
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.packageName == SPOTIFY }
            ?: controllers.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val meta = c?.metadata
        if (c == null || meta == null) {
            _nowPlaying.value = null
            return
        }
        val state = c.playbackState
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        _nowPlaying.value = NowPlaying(
            packageName = c.packageName,
            appLabel = runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.packageName, 0)).toString()
            }.getOrDefault(c.packageName),
            title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = (meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            art = art?.asImageBitmap(),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.position ?: 0L,
            positionUpdatedAt = state?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed ?: 1f,
            durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
        )
    }

    private companion object {
        const val SPOTIFY = "com.spotify.music"
    }
}
