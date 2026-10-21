package dev.netrunner.launcher.system

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.widget.Toast

/**
 * Where the home widgets lead: the deck's own apps when they are installed, otherwise the
 * system ones, as before.
 */
object DeckLinks {
    private const val WEATHER = "dev.netrunner.weather"
    private const val CLOCK = "dev.netrunner.clock"
    private const val AGENDA = "dev.netrunner.agenda"

    private fun installed(context: Context, pkg: String) =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun start(context: Context, intent: Intent) =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    /** Météo Netrunner; it has no system equivalent, so a hint when it is missing. */
    fun openWeather(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(WEATHER)
        if (intent == null || !start(context, intent)) {
            Toast.makeText(context, "Installe Météo depuis Applis du deck (glisser à gauche)", Toast.LENGTH_LONG).show()
        }
    }

    /** Horloge Netrunner, or the system alarms. */
    fun openClock(context: Context) {
        context.packageManager.getLaunchIntentForPackage(CLOCK)?.let { if (start(context, it)) return }
        start(context, Intent(AlarmClock.ACTION_SHOW_ALARMS))
    }

    /** An agenda event, in Agenda Netrunner when installed. */
    fun openEvent(context: Context, eventId: Long) {
        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId))
        if (installed(context, AGENDA) && start(context, Intent(intent).setPackage(AGENDA))) return
        start(context, intent)
    }

    /** A new event ("contract"), in Agenda Netrunner when installed. */
    fun newEvent(context: Context) {
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        if (installed(context, AGENDA) && start(context, Intent(intent).setPackage(AGENDA))) return
        start(context, intent)
    }
}
