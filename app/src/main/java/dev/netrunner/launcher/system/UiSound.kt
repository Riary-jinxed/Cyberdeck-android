package dev.netrunner.launcher.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Interface sounds, synthesised at startup (no audio files): short bips, a glitch burst and a
 * jack-in sweep, each paired with a light vibration. Silent when the ringer is not on "sound",
 * and can be switched off with the terminal command `sound off`.
 */
object UiSound {
    enum class Cue { Tap, Open, Close, Confirm, Error, Glitch, JackIn }

    private const val RATE = 22_050
    private var tracks: Map<Cue, AudioTrack> = emptyMap()

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY, true)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY, on).apply()

    fun play(context: Context, cue: Cue) {
        val c = context.applicationContext
        if (!isEnabled(c)) return
        vibrate(c, cue)
        val audio = c.getSystemService(AudioManager::class.java)
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val track = tracksFor(c)[cue] ?: return
        runCatching {
            track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    private fun vibrate(context: Context, cue: Cue) {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        val effect = when (cue) {
            Cue.Tap, Cue.Close -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            Cue.Open, Cue.Confirm -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            Cue.Error -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            Cue.Glitch -> VibrationEffect.createWaveform(longArrayOf(0, 12, 30, 12, 30, 12), -1)
            Cue.JackIn -> VibrationEffect.createWaveform(longArrayOf(0, 20, 60, 40), -1)
        }
        runCatching { vibrator.vibrate(effect) }
    }

    @Synchronized
    private fun tracksFor(context: Context): Map<Cue, AudioTrack> {
        if (tracks.isEmpty()) {
            tracks = Cue.entries.associateWith { build(synth(it)) }
        }
        return tracks
    }

    private fun build(samples: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
        track.setVolume(0.35f)
        return track
    }

    /** Square-ish tones and noise, shaped with a quick fade so nothing clicks. */
    private fun synth(cue: Cue): ShortArray {
        val segments: List<Pair<Double, Int>> = when (cue) {
            Cue.Tap -> listOf(2400.0 to 25)
            Cue.Open -> listOf(1200.0 to 35, 1800.0 to 45)
            Cue.Close -> listOf(1800.0 to 35, 1100.0 to 45)
            Cue.Confirm -> listOf(1500.0 to 40, 0.0 to 20, 2200.0 to 60)
            Cue.Error -> listOf(320.0 to 90, 0.0 to 30, 260.0 to 120)
            Cue.Glitch -> listOf(-1.0 to 30, 3000.0 to 15, -1.0 to 40, 900.0 to 15, -1.0 to 25)
            Cue.JackIn -> (0 until 8).map { 600.0 + it * 260 to 22 }
        }
        val out = ArrayList<Short>()
        for ((freq, ms) in segments) {
            val n = RATE * ms / 1000
            val fade = minOf(n / 4, RATE / 400)
            for (i in 0 until n) {
                val env = when {
                    i < fade -> i / fade.toDouble()
                    i > n - fade -> (n - i) / fade.toDouble()
                    else -> 1.0
                }
                val v = when {
                    freq == 0.0 -> 0.0
                    freq < 0 -> Random.nextDouble(-1.0, 1.0) * 0.6
                    else -> {
                        val s = sin(2 * PI * freq * i / RATE)
                        // Soft-clipped sine: brighter, more "HUD" than a pure tone.
                        (s * 2.5).coerceIn(-1.0, 1.0) * 0.7
                    }
                }
                out += (v * env * Short.MAX_VALUE * 0.8).toInt().toShort()
            }
        }
        return out.toShortArray()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
    private const val KEY = "ui_sound"
}
