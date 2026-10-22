package dev.netrunner.launcher.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

data class Contract(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String?)

/** Upcoming calendar events, shown on the HUD as "contracts". */
object Agenda {
    fun granted(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun upcoming(context: Context, hours: Int = 48, limit: Int = 4): List<Contract> {
        val now = System.currentTimeMillis()
        return between(context, now, now + hours * 3_600_000L, limit)
    }

    /** Events overlapping [from, to], oldest first. */
    fun between(context: Context, from: Long, to: Long, limit: Int, match: (Contract) -> Boolean = { true }): List<Contract> {
        if (!granted(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val out = mutableListOf<Contract>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.EVENT_LOCATION,
                ),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val end = c.getLong(3)
                    if (end < from) continue
                    val e = Contract(
                        id = c.getLong(0),
                        title = c.getString(1)?.ifBlank { null } ?: "Contrat sans nom",
                        begin = c.getLong(2),
                        end = end,
                        allDay = c.getInt(4) == 1,
                        location = c.getString(5)?.ifBlank { null },
                    )
                    if (match(e)) out += e
                }
            }
        }
        return out
    }
}
