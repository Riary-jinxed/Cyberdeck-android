package dev.netrunner.launcher.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import dev.netrunner.launcher.R
import dev.netrunner.launcher.ui.IceActivity
import java.time.LocalDate
import org.json.JSONObject

/**
 * "ICE" limits: a maximum time per day for chosen apps. Once it is used up, a Netwatch-style
 * intrusion screen covers the app (or a notification when Netrunner may not draw over apps).
 * "+5 min" buys a little more time for today; the counters reset at midnight.
 */
object Ice {
    /** Minutes allowed per day, by package. Compose state, so the stats screen updates live. */
    val limits = mutableStateMapOf<String, Int>()
    val choices = listOf(15, 30, 45, 60, 90, 120, 180)
    const val EXTRA_MINUTES = 5

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val json = runCatching { JSONObject(prefs(context).getString(KEY, "{}") ?: "{}") }.getOrElse { JSONObject() }
        json.keys().forEach { pkg -> limits[pkg] = json.optInt(pkg) }
    }

    fun setLimit(context: Context, pkg: String, minutes: Int?) {
        load(context)
        if (minutes == null) limits.remove(pkg) else limits[pkg] = minutes
        prefs(context).edit().putString(KEY, JSONObject(limits.toMap()).toString()).apply()
    }

    /** Extra minutes granted today with "+5 min". */
    fun extra(context: Context, pkg: String): Int = prefs(context).getInt(extraKey(pkg), 0)

    fun grantExtra(context: Context, pkg: String) {
        prefs(context).edit().putInt(extraKey(pkg), extra(context, pkg) + EXTRA_MINUTES).apply()
    }

    private fun extraKey(pkg: String) = "ice_extra_${LocalDate.now()}_$pkg"

    // ------------------------------------------------------------------ watcher

    private const val TICK_MS = 30_000L
    private val handler = Handler(Looper.getMainLooper())
    private var watching: Context? = null

    /** Checks the app in front twice a minute. Hosted by the notification listener, like the cyber lock. */
    fun start(context: Context) {
        if (watching != null) return
        watching = context.applicationContext
        load(context)
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        watching = null
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val context = watching ?: return
            if (limits.isNotEmpty() && context.getSystemService(PowerManager::class.java)?.isInteractive == true) {
                Thread { check(context) }.start()
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun check(context: Context) {
        val today = UsageReport.today(context) ?: return
        val pkg = today.current ?: return
        val limit = limits[pkg] ?: return
        val used = today.foregroundMs[pkg] ?: 0L
        if (used < (limit + extra(context, pkg)) * 60_000L) return
        alert(context, pkg, used)
    }

    private fun alert(context: Context, pkg: String, usedMs: Long) {
        val intent = Intent(context, IceActivity::class.java)
            .putExtra(IceActivity.EXTRA_PACKAGE, pkg)
            .putExtra(IceActivity.EXTRA_USED, usedMs)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Settings.canDrawOverlays(context)) {
            runCatching { context.startActivity(intent) }.onSuccess { return }
        }
        notify(context, pkg, intent)
    }

    private fun notify(context: Context, pkg: String, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Limites ICE", NotificationManager.IMPORTANCE_HIGH))
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val tap = PendingIntent.getActivity(context, pkg.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_lock)
            .setContentTitle("ICE // limite atteinte")
            .setContentText("$label : temps du jour épuisé (${limits[pkg]} min)")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    private const val CHANNEL = "ice"
    private const val NOTIFICATION_ID = 0x1CE
    private const val KEY = "ice_limits"

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}
