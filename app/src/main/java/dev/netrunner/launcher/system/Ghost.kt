package dev.netrunner.launcher.system

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import dev.netrunner.launcher.ui.Cp
import dev.netrunner.launcher.ui.Faction

/**
 * Stealth mode. Android no longer lets apps switch Wi-Fi, Bluetooth or location off by
 * themselves, so ghost mode silences the phone (Do Not Disturb, alarms still ring), switches
 * the HUD to the dim GHOST palette, then opens the system connectivity panel for the radios.
 */
object Ghost {
    private const val KEY_ON = "ghost_on"
    private const val KEY_FACTION = "ghost_prev_faction"
    private const val KEY_RINGER = "ghost_prev_ringer"

    fun isOn(context: Context) = prefs(context).getBoolean(KEY_ON, false)

    /** Returns the lines to print: what was done and what is left to the user. */
    fun engage(context: Context, openRadios: Boolean = true): List<String> {
        val c = context.applicationContext
        val report = mutableListOf<String>()
        val audio = c.getSystemService(AudioManager::class.java)
        val notifications = c.getSystemService(NotificationManager::class.java)
        if (!isOn(c)) {
            prefs(c).edit()
                .putString(KEY_FACTION, Cp.faction.id)
                .putInt(KEY_RINGER, audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL)
                .apply()
        }
        if (notifications?.isNotificationPolicyAccessGranted == true) {
            notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            runCatching { audio?.ringerMode = AudioManager.RINGER_MODE_SILENT }
            report += "> sonnerie : SILENCE (alarmes conservées)"
        } else {
            runCatching { audio?.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
            report += "> sonnerie : VIBREUR (autorise « Ne pas déranger » pour le silence total)"
        }
        Cp.select(c, Faction.Ghost)
        prefs(c).edit().putBoolean(KEY_ON, true).apply()
        report += "> HUD : profil GHOST"
        report += "> radios : coupe Wi-Fi / données dans le panneau, Bluetooth et localisation dans le volet"
        if (openRadios) {
            runCatching {
                c.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return report
    }

    fun disengage(context: Context): List<String> {
        val c = context.applicationContext
        if (!isOn(c)) return listOf("> mode furtif déjà inactif")
        val audio = c.getSystemService(AudioManager::class.java)
        val notifications = c.getSystemService(NotificationManager::class.java)
        if (notifications?.isNotificationPolicyAccessGranted == true) {
            notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
        runCatching { audio?.ringerMode = prefs(c).getInt(KEY_RINGER, AudioManager.RINGER_MODE_NORMAL) }
        Cp.select(c, Faction.of(prefs(c).getString(KEY_FACTION, null)))
        prefs(c).edit().putBoolean(KEY_ON, false).apply()
        return listOf("> sonnerie restaurée", "> HUD : profil ${Cp.faction.label}", "> pense à rallumer Wi-Fi et Bluetooth")
    }

    fun toggle(context: Context) = if (isOn(context)) disengage(context) else engage(context)

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}
