package dev.netrunner.launcher.system

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Cyberpunk ringtones, notification sounds and alarms, synthesised on the phone (no audio files
 * shipped), saved to the system sound folders and set as default in one tap.
 */
object Tones {
    enum class Kind(val label: String, val ringtoneType: Int, val folder: String) {
        Ringtone("Sonnerie", RingtoneManager.TYPE_RINGTONE, Environment.DIRECTORY_RINGTONES),
        Notification("Notification", RingtoneManager.TYPE_NOTIFICATION, Environment.DIRECTORY_NOTIFICATIONS),
        Alarm("Alarme", RingtoneManager.TYPE_ALARM, Environment.DIRECTORY_ALARMS),
    }

    data class Tone(val id: String, val title: String, val kind: Kind, val build: () -> ShortArray)

    private const val RATE = 44_100

    private enum class Wave { Square, Saw, Sine, Noise }

    private data class Note(val start: Double, val length: Double, val freq: Double, val wave: Wave, val volume: Double, val decay: Double = 6.0)

    /** Semitone offset from A4. */
    private fun n(semis: Int) = 440.0 * 2.0.pow(semis / 12.0)

    val all = listOf(
        Tone("holocall", "Holo-appel", Kind.Ringtone) { holocall() },
        Tone("breach", "Breach Protocol", Kind.Ringtone) { breach() },
        Tone("netwatch", "Alerte Netwatch", Kind.Ringtone) { netwatch() },
        Tone("ping", "Ping", Kind.Notification) { ping() },
        Tone("glitch", "Glitch", Kind.Notification) { glitchNote() },
        Tone("shard", "Data shard", Kind.Notification) { shard() },
        Tone("nightcity", "Réveil Night City", Kind.Alarm) { nightCity() },
    )

    // ------------------------------------------------------------------ compositions

    private fun holocall(): ShortArray {
        val notes = mutableListOf<Note>()
        // Two bars of a bright E-minor arpeggio, a bass pulse under it, then a breath.
        val arp = listOf(7, 10, 14, 19, 14, 10)
        repeat(2) { bar ->
            val t0 = bar * 1.6
            arp.forEachIndexed { i, s -> notes += Note(t0 + i * 0.11, 0.16, n(s), Wave.Square, 0.22) }
            notes += Note(t0, 0.7, n(-17), Wave.Saw, 0.25, decay = 3.0)
            notes += Note(t0 + 0.8, 0.5, n(-14), Wave.Saw, 0.2, decay = 3.0)
        }
        return render(notes, 3.6)
    }

    private fun breach(): ShortArray {
        val notes = mutableListOf<Note>()
        val seq = listOf(0, 3, 7, 12, 15, 12, 7, 3, 0, 3, 8, 12, 15, 20, 15, 12)
        seq.forEachIndexed { i, s ->
            notes += Note(i * 0.09, 0.08, n(s + 3), Wave.Square, 0.2, decay = 10.0)
            if (i % 4 == 0) notes += Note(i * 0.09, 0.3, n(s - 21), Wave.Saw, 0.25, decay = 5.0)
        }
        notes += Note(1.5, 0.12, 0.0, Wave.Noise, 0.18, decay = 20.0)
        notes += Note(1.7, 0.08, 0.0, Wave.Noise, 0.14, decay = 25.0)
        return render(notes, 2.4)
    }

    private fun netwatch(): ShortArray {
        val notes = mutableListOf<Note>()
        repeat(6) { i ->
            notes += Note(i * 0.45, 0.4, n(if (i % 2 == 0) 5 else 0), Wave.Saw, 0.22, decay = 1.5)
            notes += Note(i * 0.45, 0.4, n(if (i % 2 == 0) 17 else 12), Wave.Sine, 0.12, decay = 1.5)
        }
        return render(notes, 3.2)
    }

    private fun ping() = render(
        listOf(Note(0.0, 0.12, n(19), Wave.Square, 0.25, 12.0), Note(0.1, 0.35, n(26), Wave.Sine, 0.3, 7.0)), 0.6,
    )

    private fun glitchNote() = render(
        listOf(
            Note(0.0, 0.05, 0.0, Wave.Noise, 0.25, 30.0),
            Note(0.06, 0.06, n(31), Wave.Square, 0.2, 20.0),
            Note(0.13, 0.04, 0.0, Wave.Noise, 0.2, 30.0),
            Note(0.18, 0.25, n(14), Wave.Square, 0.22, 10.0),
        ), 0.6,
    )

    private fun shard() = render(
        listOf(12, 16, 19, 24).mapIndexed { i, s -> Note(i * 0.07, 0.3, n(s), Wave.Square, 0.18, 9.0) }, 0.8,
    )

    private fun nightCity(): ShortArray {
        val notes = mutableListOf<Note>()
        val chords = listOf(listOf(-9, -5, -2, 3), listOf(-7, -4, 0, 5), listOf(-12, -8, -5, 0), listOf(-10, -7, -3, 2))
        chords.forEachIndexed { c, chord ->
            val t0 = c * 2.0
            notes += Note(t0, 1.9, n(chord[0] - 12), Wave.Saw, 0.25, decay = 1.0)
            repeat(16) { i -> notes += Note(t0 + i * 0.125, 0.12, n(chord[i % 4] + 12), Wave.Square, 0.14, decay = 12.0) }
        }
        return render(notes, 8.2)
    }

    // ------------------------------------------------------------------ synth

    private fun render(notes: List<Note>, seconds: Double): ShortArray {
        val total = (seconds * RATE).toInt()
        val mix = DoubleArray(total)
        val noise = Random(7)
        for (note in notes) {
            val from = (note.start * RATE).toInt()
            val len = (note.length * RATE).toInt()
            for (i in 0 until len) {
                val idx = from + i
                if (idx >= total) break
                val t = i.toDouble() / RATE
                val phase = (note.freq * t) % 1.0
                val v = when (note.wave) {
                    Wave.Square -> if (phase < 0.5) 1.0 else -1.0
                    Wave.Saw -> 2 * phase - 1
                    Wave.Sine -> sin(2 * PI * phase)
                    Wave.Noise -> noise.nextDouble(-1.0, 1.0)
                }
                val attack = (i / (0.004 * RATE)).coerceAtMost(1.0)
                val release = ((len - i) / (0.01 * RATE)).coerceAtMost(1.0)
                mix[idx] += v * note.volume * attack * release * exp(-note.decay * t)
            }
        }
        // Gentle low-pass to tame the square waves, then soft clipping.
        val out = ShortArray(total)
        var lp = 0.0
        for (i in 0 until total) {
            lp += 0.35 * (mix[i] - lp)
            out[i] = (tanh(lp * 1.4) * 0.9 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun wav(samples: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2)
        }
        return ByteArrayOutputStream().apply { write(header.array()); write(data.array()) }.toByteArray()
    }

    // ------------------------------------------------------------------ playback and install

    private var preview: AudioTrack? = null

    fun preview(tone: Tone) {
        stopPreview()
        val samples = tone.build()
        preview = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
            .apply {
                write(samples, 0, samples.size)
                play()
            }
    }

    fun stopPreview() {
        preview?.let { runCatching { it.stop() }; it.release() }
        preview = null
    }

    fun canSetDefault(context: Context) = Settings.System.canWrite(context)

    fun writeSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Saves the tone to the system sound folder (once) and makes it the default. */
    fun install(context: Context, tone: Tone): Boolean {
        if (!canSetDefault(context)) return false
        val uri = existing(context, tone) ?: save(context, tone) ?: return false
        RingtoneManager.setActualDefaultRingtoneUri(context, tone.kind.ringtoneType, uri)
        return true
    }

    private fun fileName(tone: Tone) = "Netrunner - ${tone.title}.wav"

    private fun existing(context: Context, tone: Tone): Uri? {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
            arrayOf(fileName(tone)),
            null,
        )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null }
    }

    private fun save(context: Context, tone: Tone): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName(tone))
            put(MediaStore.Audio.Media.TITLE, "Netrunner - ${tone.title}")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, tone.kind.folder + "/Netrunner")
            put(MediaStore.Audio.Media.IS_RINGTONE, tone.kind == Kind.Ringtone)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, tone.kind == Kind.Notification)
            put(MediaStore.Audio.Media.IS_ALARM, tone.kind == Kind.Alarm)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(wav(tone.build())) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            uri
        }.getOrElse {
            resolver.delete(uri, null, null)
            null
        }
    }
}
