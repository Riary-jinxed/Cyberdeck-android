package dev.netrunner.music.ui

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.netrunner.music.data.Library
import dev.netrunner.music.system.PlaybackService

/** What the screen shows of the player in the service, through a MediaController. */
class PlayerState {
    var controller by mutableStateOf<MediaController?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var item by mutableStateOf<MediaItem?>(null)
        private set
    var index by mutableIntStateOf(0)
        private set
    var count by mutableIntStateOf(0)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    /** Bumped when the queue changes, so its list is read again. */
    var queueVersion by mutableIntStateOf(0)
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { queueVersion++ }
    }

    fun attach(c: MediaController) {
        controller = c
        c.addListener(listener)
        sync(c)
    }

    fun detach() {
        controller?.run { removeListener(listener); release() }
        controller = null
    }

    private fun sync(p: Player) {
        playing = p.isPlaying
        item = p.currentMediaItem
        index = p.currentMediaItemIndex
        count = p.mediaItemCount
        duration = p.duration.coerceAtLeast(0L)
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
    }

    val position: Long get() = controller?.currentPosition ?: 0L

    fun play(tracks: List<Library.Track>, start: Int) {
        val c = controller ?: return
        c.setMediaItems(tracks.map { it.toMediaItem() }, start, 0L)
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0L)
            c.play()
        }
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seek(ms: Long) = controller?.seekTo(ms)
    fun jump(i: Int) = controller?.seekTo(i, 0L)
    fun remove(i: Int) = controller?.removeMediaItem(i)
    fun setShuffle(on: Boolean) = controller?.setShuffleModeEnabled(on)

    /** Off → whole queue → this track → off. */
    fun cycleRepeat() = controller?.let {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun queue(): List<MediaItem> = controller?.let { c -> (0 until c.mediaItemCount).map { c.getMediaItemAt(it) } }.orEmpty()

    /** Adds [track] right after the current one. */
    fun playNext(track: Library.Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(track), 0)
        c.addMediaItem(c.currentMediaItemIndex + 1, track.toMediaItem())
    }

    fun enqueue(track: Library.Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(track), 0)
        c.addMediaItem(track.toMediaItem())
    }
}

@Composable
fun rememberPlayerState(): PlayerState {
    val context = LocalContext.current
    val state = remember { PlayerState() }
    DisposableEffect(Unit) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        future.addListener({ runCatching { state.attach(future.get()) } }, ContextCompat.getMainExecutor(context))
        onDispose {
            if (state.controller != null) state.detach() else MediaController.releaseFuture(future)
        }
    }
    return state
}
