package dev.netrunner.recorder.system

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.netrunner.recorder.data.RecorderPrefs
import dev.netrunner.recorder.data.Recordings
import dev.netrunner.recorder.data.Wave
import java.io.ByteArrayOutputStream
import java.io.File

/** The one recording in progress, shared by the screen and the foreground service that keeps it alive. */
object Recorder {
    enum class Status { IDLE, RECORDING, PAUSED }

    var status by mutableStateOf(Status.IDLE)
        private set
    /** Recorded time, pauses excluded. */
    var elapsed by mutableLongStateOf(0L)
        private set
    /** Latest levels (0..100), for the live waveform. */
    var recent by mutableStateOf(IntArray(0))
        private set
    /** The last file saved, so the screen can point to it. */
    var lastSaved by mutableStateOf<File?>(null)
        private set

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private val levels = ByteArrayOutputStream()
    private var startedAt = 0L
    private var before = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            if (status == Status.RECORDING) {
                val level = Wave.level(runCatching { r.maxAmplitude }.getOrDefault(0))
                levels.write(level)
                recent = (recent.takeLast(LIVE_BARS - 1) + level).toIntArray()
                elapsed = before + SystemClock.elapsedRealtime() - startedAt
            }
            handler.postDelayed(this, Wave.STEP_MS)
        }
    }

    const val LIVE_BARS = 120

    /** Starts recording into a new file. Throws when the microphone is unavailable. */
    fun start(context: Context) {
        if (status != Status.IDLE) return
        val app = context.applicationContext
        val target = Recordings.newFile(app, Wave.defaultName(System.currentTimeMillis()))
        val high = RecorderPrefs.highQuality(app)
        val r = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(if (high) 48_000 else 44_100)
            setAudioEncodingBitRate(if (high) 192_000 else 96_000)
            setAudioChannels(1)
            setOutputFile(target.path)
        }
        try {
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            target.delete()
            throw e
        }
        recorder = r
        file = target
        levels.reset()
        recent = IntArray(0)
        before = 0L
        elapsed = 0L
        startedAt = SystemClock.elapsedRealtime()
        status = Status.RECORDING
        handler.post(tick)
        app.startForegroundService(Intent(app, RecorderService::class.java))
    }

    fun pause() {
        val r = recorder ?: return
        if (status != Status.RECORDING) return
        r.pause()
        before += SystemClock.elapsedRealtime() - startedAt
        status = Status.PAUSED
    }

    fun resume() {
        val r = recorder ?: return
        if (status != Status.PAUSED) return
        r.resume()
        startedAt = SystemClock.elapsedRealtime()
        status = Status.RECORDING
    }

    /** Stops and keeps the file (with its waveform). Returns it, or null if nothing usable was recorded. */
    fun stop(context: Context): File? {
        val r = recorder ?: return null
        handler.removeCallbacks(tick)
        val f = file
        val ok = runCatching { r.stop() }.isSuccess // stop() throws when nothing was recorded
        r.release()
        recorder = null
        file = null
        status = Status.IDLE
        context.applicationContext.stopService(Intent(context.applicationContext, RecorderService::class.java))
        if (f == null) return null
        if (!ok) {
            f.delete()
            return null
        }
        runCatching { Recordings.waveFile(f).writeBytes(levels.toByteArray()) }
        lastSaved = f
        return f
    }

    /** Stops and throws the recording away. */
    fun discard(context: Context) {
        stop(context)?.let { f ->
            f.delete()
            Recordings.waveFile(f).delete()
            lastSaved = null
        }
    }
}
