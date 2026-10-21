package dev.netrunner.agenda.data

import android.content.Context

object AgendaPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("agenda", Context.MODE_PRIVATE)

    /** Calendars the user switched off in CONFIG. */
    fun hidden(context: Context): Set<Long> =
        prefs(context).getStringSet("hidden", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    fun setHidden(context: Context, ids: Set<Long>) =
        prefs(context).edit().putStringSet("hidden", ids.map { it.toString() }.toSet()).apply()

    fun defaultCalendar(context: Context): Long = prefs(context).getLong("calendar", -1)
    fun setDefaultCalendar(context: Context, id: Long) = prefs(context).edit().putLong("calendar", id).apply()

    fun reminder(context: Context): Int? = prefs(context).getInt("reminder", 15).takeIf { it >= 0 }
    fun setReminder(context: Context, minutes: Int?) = prefs(context).edit().putInt("reminder", minutes ?: -1).apply()

    fun showAska(context: Context) = prefs(context).getBoolean("aska", true)
    fun setShowAska(context: Context, on: Boolean) = prefs(context).edit().putBoolean("aska", on).apply()
}
