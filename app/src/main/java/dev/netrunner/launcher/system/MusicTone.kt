package dev.netrunner.launcher.system

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a song picked on the phone into a ringtone or notification sound: the chosen excerpt is
 * decoded, faded in and out, saved as a WAV in the system sound folders and set as default.
 * Nothing leaves the phone and no audio ships with the app.
 */
object MusicTone {
    /** Longest excerpt kept: a ringtone loops anyway, and a WAV this long stays under ~10 MB. */
    const val MAX_MS = 40_000L

    data class Source(val uri: Uri, val title: String, val durationMs: Long)

    fun probe(context: Context, uri: Uri): Source? = runCatching {
        val duration = MediaMetadataRetriever().run {
            try {
                setDataSource(context, uri)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                release()
            }
        } ?: return null
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "Musique"
        Source(uri, name.substringBeforeLast('.').take(40).trim(), duration)
    }.getOrNull()

    // ------------------------------------------------------------------ preview

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    fun preview(context: Context, source: Source, startMs: Long, endMs: Long) {
        stopPreview()
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(context, source.uri)
                prepare()
                seekTo(startMs.toInt())
                start()
            }
        }.getOrNull()
        handler.postDelayed(::stopPreview, endMs - startMs)
    }

    fun stopPreview() {
        handler.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    // ------------------------------------------------------------------ cut and install

    /** Decodes [startMs, endMs] and makes it the default sound of [kind]. */
    suspend fun install(context: Context, source: Source, startMs: Long, endMs: Long, kind: Tones.Kind): Boolean =
        withContext(Dispatchers.Default) {
            val pcm = runCatching { decode(context, source.uri, startMs * 1000, endMs * 1000) }.getOrNull()
                ?: return@withContext false
            if (pcm.samples.isEmpty()) return@withContext false
            fade(pcm)
            val bytes = Tones.wav(pcm.samples, pcm.rate, pcm.channels)
            withContext(Dispatchers.IO) { Tones.installWav(context, source.title, kind, bytes) }
        }

    private class Pcm(val samples: ShortArray, val rate: Int, val channels: Int)

    /** Growable buffer of interleaved 16-bit samples. */
    private class Samples(initial: Int) {
        var data = ShortArray(initial.coerceAtLeast(1024))
        var size = 0
        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
    }

    private fun decode(context: Context, uri: Uri, startUs: Long, endUs: Long): Pcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val out = Samples(((endUs - startUs) * rate / 1_000_000 * channels).toInt())

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var done = false
            while (!done) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                        if (size < 0 || extractor.sampleTime > endUs) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!.apply {
                        position(info.offset)
                        limit(info.offset + info.size)
                        order(ByteOrder.LITTLE_ENDIAN)
                    }
                    val shorts = if (float) null else buffer.asShortBuffer()
                    val floats = if (float) buffer.asFloatBuffer() else null
                    val frames = (shorts?.remaining() ?: floats!!.remaining()) / channels
                    for (frame in 0 until frames) {
                        val t = info.presentationTimeUs + frame * 1_000_000L / rate
                        if (t >= endUs) {
                            done = true
                            break
                        }
                        if (t < startUs) continue
                        for (c in 0 until channels) {
                            val i = frame * channels + c
                            out.add(
                                shorts?.get(i)
                                    ?: (floats!!.get(i).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                            )
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
            return Pcm(out.data.copyOf(out.size), rate, channels)
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    /** Short fade-in and a longer fade-out, so the cut never clicks. */
    private fun fade(pcm: Pcm) {
        val frames = pcm.samples.size / pcm.channels
        val fadeIn = (pcm.rate * 0.3).toInt().coerceAtMost(frames / 4)
        val fadeOut = (pcm.rate * 1.5).toInt().coerceAtMost(frames / 3)
        for (frame in 0 until frames) {
            val gain = when {
                frame < fadeIn -> frame.toFloat() / fadeIn
                frame >= frames - fadeOut -> (frames - frame).toFloat() / fadeOut
                else -> continue
            }
            for (c in 0 until pcm.channels) {
                val i = frame * pcm.channels + c
                pcm.samples[i] = (pcm.samples[i] * gain).toInt().toShort()
            }
        }
    }
}
