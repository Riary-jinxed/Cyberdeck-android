package dev.netrunner.launcher.system

import android.app.NotificationManager
import android.content.Context

/**
 * Focus mode for an intervention: "Ne pas déranger" in priority mode (the user's starred
 * contacts, repeat callers and alarms still get through, as set in Android), restored on exit.
 * The focus screen itself (checklist, journal, photo, timer) lives in the field kit.
 */
object Focus {
    private const val KEY_PREV = "focus_prev_filter"
    private const val KEY_SET = "focus_set_filter"

    fun canSilence(context: Context) =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /** Returns true when Do Not Disturb was switched on. */
    fun engage(context: Context): Boolean {
        val c = context.applicationContext
        val nm = c.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.isNotificationPolicyAccessGranted) return false
        val current = nm.currentInterruptionFilter
        // Already quieter (ghost mode, or DND set by hand): leave it as it is.
        if (current != NotificationManager.INTERRUPTION_FILTER_ALL) return true
        prefs(c).edit()
            .putInt(KEY_PREV, current)
            .putInt(KEY_SET, NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .apply()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        return true
    }

    fun release(context: Context) {
        val c = context.applicationContext
        val p = prefs(c)
        val set = p.getInt(KEY_SET, -1)
        if (set < 0) return
        val nm = c.getSystemService(NotificationManager::class.java)
        // Only undo our own change: if the user picked another mode since, keep theirs.
        if (nm != null && nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter == set) {
            nm.setInterruptionFilter(p.getInt(KEY_PREV, NotificationManager.INTERRUPTION_FILTER_ALL))
        }
        p.edit().remove(KEY_PREV).remove(KEY_SET).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}
