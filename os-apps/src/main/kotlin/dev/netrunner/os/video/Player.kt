package dev.netrunner.os.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.nio.ByteBuffer

/**
 * libVLC (the engine of VLC) decodes, Netrunner Vidéo draws: each frame comes back as pixels
 * and is shown by Compose like any image, so the whole window stays ours.
 */
class Player(private val onFinished: () -> Unit) {
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Why there is no engine (libVLC missing), or null. */
    val error: String?

    private val factory: MediaPlayerFactory?
    private val player: EmbeddedMediaPlayer?
    private var info: ImageInfo? = null
    private var bytes: ByteArray? = null
    private val bitmap = Bitmap()

    init {
        var e: String? = null
        val f = runCatching {
            if (!NativeDiscovery().discover()) error("libVLC introuvable")
            MediaPlayerFactory("--no-video-title-show", "--quiet")
        }.onFailure { e = "Moteur vidéo absent (libVLC) : installe le paquet libvlc5. ${it.message.orEmpty()}" }.getOrNull()
        factory = f
        player = f?.mediaPlayers()?.newEmbeddedMediaPlayer()
        error = e
        player?.videoSurface()?.set(
            f!!.videoSurfaces().newVideoSurface(
                object : BufferFormatCallback {
                    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                        info = ImageInfo.makeN32(sourceWidth, sourceHeight, ColorAlphaType.OPAQUE)
                        return RV32BufferFormat(sourceWidth, sourceHeight)
                    }

                    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {
                        bytes = ByteArray(buffers[0].limit())
                    }
                },
                object : RenderCallback {
                    override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
                        val i = info ?: return
                        val b = bytes ?: return
                        nativeBuffers[0].rewind()
                        nativeBuffers[0].get(b)
                        bitmap.allocPixels(i)
                        bitmap.installPixels(b)
                        frame = bitmap.asComposeImageBitmap()
                    }
                },
                true,
            ),
        )
        player?.events()?.addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) = onFinished()
        })
    }

    fun play(file: File) {
        frame = null
        player?.media()?.play(file.path)
    }

    val playing get() = player?.status()?.isPlaying == true
    val time get() = player?.status()?.time() ?: 0L
    val length get() = player?.status()?.length() ?: 0L

    fun toggle() { player?.controls()?.pause() }
    fun pause(on: Boolean) { player?.controls()?.setPause(on) }
    fun seek(ms: Long) { player?.controls()?.setTime(ms.coerceIn(0, maxOf(0, length))) }
    fun skip(ms: Long) = seek(time + ms)

    var volume: Int
        get() = player?.audio()?.volume()?.takeIf { it >= 0 } ?: 100
        set(v) { player?.audio()?.setVolume(v.coerceIn(0, 150)) }

    /** Cycles the subtitles (off, then each track); says which is on. */
    fun nextSubtitles(): String = cycle(
        player?.subpictures()?.trackDescriptions().orEmpty().map { it.id() to it.description() },
        player?.subpictures()?.track() ?: -1,
    ) { player?.subpictures()?.setTrack(it) }

    /** Cycles the audio tracks (languages); says which is on. */
    fun nextAudio(): String = cycle(
        player?.audio()?.trackDescriptions().orEmpty().map { it.id() to it.description() }.filter { it.first >= 0 },
        player?.audio()?.track() ?: -1,
    ) { player?.audio()?.setTrack(it) }

    private fun cycle(tracks: List<Pair<Int, String>>, current: Int, set: (Int) -> Unit): String {
        if (tracks.isEmpty()) return "Aucune piste"
        val i = tracks.indexOfFirst { it.first == current }
        val next = tracks[(i + 1).mod(tracks.size)]
        set(next.first)
        return next.second
    }

    fun release() {
        runCatching { player?.controls()?.stop() }
        runCatching { player?.release() }
        runCatching { factory?.release() }
    }
}
