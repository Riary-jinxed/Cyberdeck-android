package dev.netrunner.launcher.system

import android.content.Context

/**
 * The hub's shortcut row, chosen by the user: built-in shortcut ids ("aska", "stats"…) and apps
 * ("app:" + component key), in display order.
 */
object HubPrefs {
    private const val KEY = "hub_shortcuts"
    const val APP = "app:"

    val Defaults = listOf("claude", "aska", "stats", "kit", "terminal", "sms")

    fun load(context: Context): List<String> {
        val raw = prefs(context).getString(KEY, null) ?: return Defaults
        return raw.split('|').filter { it.isNotBlank() }
    }

    fun save(context: Context, ids: List<String>) {
        prefs(context).edit().putString(KEY, ids.joinToString("|")).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}
