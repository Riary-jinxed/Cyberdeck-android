package dev.netrunner.clock.system

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dev.netrunner.clock.alarm.Scheduler

/**
 * What an alarm needs to ring on time on HyperOS / MIUI, with a shortcut to each setting.
 * [ok] is null when Android cannot tell (autostart on Xiaomi has no public API).
 */
object Reliability {

    data class Check(val title: String, val detail: String, val ok: Boolean?, val fix: (Context) -> Unit)

    fun checks(context: Context): List<Check> {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val list = mutableListOf(
            Check("Alarmes exactes", "Sonner à la seconde près, même en veille profonde.", Scheduler.canScheduleExact(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    open(it, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg(it)))
                }
            },
            Check("Notifications", "Indispensables pour l'écran de réveil et les minuteurs.", nm.areNotificationsEnabled()) {
                open(it, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName))
            },
        )
        if (Build.VERSION.SDK_INT >= 34) {
            list += Check("Plein écran", "Afficher le réveil par-dessus l'écran verrouillé.", nm.canUseFullScreenIntent()) {
                open(it, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg(it)))
            }
        }
        list += Check(
            "Batterie sans restriction",
            "Évite que HyperOS endorme l'appli. Choisis « Aucune restriction ».",
            pm.isIgnoringBatteryOptimizations(context.packageName),
        ) { requestBatteryExemption(it) }
        if (isXiaomi()) {
            list += Check("Démarrage auto (Xiaomi)", "Laisse l'appli se relancer après un redémarrage ou un nettoyage.", null) {
                open(
                    it,
                    Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                )
            }
            list += Check("Verrouiller dans les récentes", "Dans les applis récentes, appui long sur l'horloge › cadenas.", null) {}
        }
        return list
    }

    fun isXiaomi() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.lowercase().let { it == "xiaomi" || it == "redmi" || it == "poco" }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption(context: Context) {
        if (isXiaomi()) {
            // HyperOS ignores the standard dialog for its own "Battery saver" setting.
            val miui = Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", context.packageName)
                .putExtra("package_label", "Horloge")
            if (open(context, miui, fallback = false)) return
        }
        open(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg(context)))
    }

    /** The Xiaomi clock's app page, to switch off its alarms or the app itself. */
    fun openStockClock(context: Context) {
        val stock = listOf("com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage")
            .firstOrNull { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess } ?: return
        open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$stock")))
    }

    private fun pkg(context: Context) = Uri.parse("package:${context.packageName}")

    /** Opens a settings page, falling back to the app's own info page. */
    fun open(context: Context, intent: Intent, fallback: Boolean = true): Boolean {
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            if (fallback) runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            false
        }
    }
}
