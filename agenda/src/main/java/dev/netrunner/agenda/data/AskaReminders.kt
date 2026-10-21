package dev.netrunner.agenda.data

import android.content.Context
import android.net.Uri

/**
 * As//ka's reminders, read from the launcher's provider (signature permission
 * dev.netrunner.permission.READ_REMINDERS). Empty when the launcher is missing, too old, or
 * was installed after this app (Android grants the permission at install time).
 */
object AskaReminders {
    private val URI: Uri = Uri.parse("content://dev.netrunner.launcher.reminders")

    fun list(context: Context, from: Long, to: Long): List<Contract> = runCatching {
        val out = mutableListOf<Contract>()
        context.contentResolver.query(URI, arrayOf("_id", "at", "text", "done"), null, null, null)?.use { c ->
            val id = c.getColumnIndex("_id")
            val at = c.getColumnIndex("at")
            val text = c.getColumnIndex("text")
            val done = c.getColumnIndex("done")
            if (at < 0 || text < 0) return@use
            while (c.moveToNext()) {
                val time = c.getLong(at)
                if (time < from || time > to) continue
                out += Contract(
                    eventId = if (id >= 0) c.getLong(id) else -1,
                    title = c.getString(text) ?: "Rappel",
                    begin = time,
                    end = time,
                    allDay = false,
                    calendarName = "As//ka",
                    source = Contract.Source.ASKA,
                    done = done >= 0 && c.getInt(done) == 1,
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    fun available(context: Context): Boolean =
        context.packageManager.resolveContentProvider("dev.netrunner.launcher.reminders", 0) != null &&
            context.checkSelfPermission("dev.netrunner.permission.READ_REMINDERS") == android.content.pm.PackageManager.PERMISSION_GRANTED
}
