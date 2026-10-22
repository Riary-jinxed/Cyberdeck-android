package dev.netrunner.clock.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import dev.netrunner.clock.data.AlarmStore
import dev.netrunner.clock.data.ClockPrefs
import dev.netrunner.clock.ui.hhmm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime

/**
 * Runs only while something rings: plays the tone, vibrates, keeps the wake screen up, and
 * stops by itself after a few minutes. Nothing runs in the background otherwise.
 */
class RingService : Service() {

    enum class Kind { Alarm, Timer }

    /** [qr] non-empty: only a scan of that code stops it (the wake screen checks it, then calls [stopNow]). */
    data class Ring(val kind: Kind, val id: Int, val label: String, val since: Long, val hour: Int, val minute: Int, val qr: String = "")

    private val handler = Handler(Looper.getMainLooper())
    private var player: TonePlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> start(intent)
            ACTION_SNOOZE -> snooze()
            ACTION_DISMISS -> if (state.value?.qr.isNullOrEmpty()) finish()
            else -> if (state.value == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val kind = Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: Kind.Alarm.name)
        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val alarm = if (kind == Kind.Alarm) AlarmStore.get(this, id) else null
        val now = LocalTime.now()
        val ring = Ring(kind, id, label, System.currentTimeMillis(), alarm?.hour ?: now.hour, alarm?.minute ?: now.minute, alarm?.qrCode.orEmpty())

        val title = if (kind == Kind.Alarm) "Alarme ${hhmm(ring.hour, ring.minute)}" else "Minuteur terminé"
        val notification = Notifs.ring(this, title, label.ifEmpty { if (kind == Kind.Alarm) "Debout, runner." else "Temps écoulé." }, kind == Kind.Alarm, canStop = ring.qr.isEmpty())
        startForeground(Notifs.ID_RING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        stopSound()
        state.value = ring
        // A QR alarm is meant to get you out of bed: it keeps going for an hour, not a few minutes.
        val minutes = if (ring.qr.isEmpty()) ClockPrefs.ringMinutes(this) else maxOf(ClockPrefs.ringMinutes(this), 60)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netrunner:ring")
            .apply { acquire(minutes * 60_000L + 5_000L) }

        player = TonePlayer(this).apply {
            play(
                tone = alarm?.tone ?: intent.getStringExtra(EXTRA_TONE),
                startMs = alarm?.toneStart ?: 0L,
                endMs = alarm?.toneEnd ?: 0L,
                crescendoMs = if (kind == Kind.Alarm) ClockPrefs.crescendoSeconds(this@RingService) * 1000L else 0L,
            )
        }
        if (intent.getBooleanExtra(EXTRA_VIBRATE, true)) vibrator().vibrate(VibrationEffect.createWaveform(PATTERN, 0))

        handler.postDelayed({
            Notifs.missed(this, title)
            finish()
        }, minutes * 60_000L)
    }

    private fun snooze() {
        val ring = state.value ?: return finish()
        when (ring.kind) {
            Kind.Alarm -> {
                val minutes = ClockPrefs.snoozeMinutes(this)
                Clockwork.snooze(this, ring.id, minutes)
                Toast.makeText(this, "Répit de $minutes min. Je te relance.", Toast.LENGTH_SHORT).show()
            }
            Kind.Timer -> Clockwork.newTimer(this, 60, ring.label)
        }
        finish()
    }

    private fun finish() {
        stopSound()
        state.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSound() {
        handler.removeCallbacksAndMessages(null)
        player?.stop()
        player = null
        vibrator().cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        stopSound()
        state.value = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "dev.netrunner.clock.RING"
        const val ACTION_SNOOZE = "dev.netrunner.clock.SNOOZE"
        const val ACTION_DISMISS = "dev.netrunner.clock.DISMISS"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_TONE = "tone"
        private const val EXTRA_VIBRATE = "vibrate"
        private val PATTERN = longArrayOf(0, 600, 400, 600, 400, 200, 200, 200, 1200)

        private val state = MutableStateFlow<Ring?>(null)

        /** What is ringing now, for the wake screen. */
        val ringing: StateFlow<Ring?> get() = state

        fun ring(context: Context, kind: Kind, id: Int, label: String, tone: String?, vibrate: Boolean) {
            context.startForegroundService(
                Intent(context, RingService::class.java).setAction(ACTION_RING)
                    .putExtra(EXTRA_KIND, kind.name).putExtra(Scheduler.EXTRA_ID, id)
                    .putExtra(EXTRA_LABEL, label).putExtra(EXTRA_TONE, tone).putExtra(EXTRA_VIBRATE, vibrate)
            )
        }

        /** The running service, to stop it directly from the wake screen (same process, main thread). */
        private var instance: RingService? = null

        /**
         * Stops the ring now. Calls the service directly instead of sending it an intent, then
         * stops it outright, which Android allows even from the background.
         */
        fun stopNow(context: Context) {
            instance?.finish()
            state.value = null
            context.stopService(Intent(context, RingService::class.java))
        }

        fun snoozeNow(context: Context) {
            val service = instance
            if (service != null) service.snooze() else command(context, ACTION_SNOOZE)
        }

        fun command(context: Context, action: String) {
            if (state.value == null) return
            context.startService(Intent(context, RingService::class.java).setAction(action))
        }
    }
}
