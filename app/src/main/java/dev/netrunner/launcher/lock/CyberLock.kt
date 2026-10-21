package dev.netrunner.launcher.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.netrunner.launcher.notify.NotificationHub

/**
 * Cyberdeck lock screen. Android does not let apps replace the real lock screen, so Netrunner
 * puts its own screen on top of it each time the display turns off. Unlocking still goes
 * through the system (fingerprint, face or PIN), so security is unchanged.
 */
object CyberLock {
    private const val PREFS = "netrunner"
    private const val KEY = "cyber_lock"
    private const val KEY_HANDOFF = "cyber_lock_handoff"

    fun isEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /**
     * Hand over to the system lock as soon as the display wakes, after a short animation. The
     * system then starts face unlock (and the fingerprint) on its own; the code is only a fallback.
     */
    fun handoff(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HANDOFF, false)

    fun setHandoff(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HANDOFF, on).apply()
    }

    /** Starting a screen from the background needs "display over other apps". */
    fun canShow(context: Context) = Settings.canDrawOverlays(context)

    fun overlaySettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** What is still missing before the lock screen can work, or null when ready. */
    fun missingStep(context: Context): Intent? = when {
        !NotificationHub.isAccessGranted(context) ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        !canShow(context) -> overlaySettings(context)
        else -> null
    }

    /**
     * Listens for the screen turning off. Hosted by the notification listener, which the system
     * keeps running anyway, so the lock screen costs no extra background service.
     */
    fun register(context: Context): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF && isEnabled(c) && canShow(c)) {
                    runCatching {
                        c.startActivity(
                            Intent(c, LockActivity::class.java).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                            )
                        )
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return receiver
    }
}
