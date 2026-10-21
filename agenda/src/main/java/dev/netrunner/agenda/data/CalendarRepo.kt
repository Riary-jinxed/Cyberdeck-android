package dev.netrunner.agenda.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import java.util.TimeZone

/** Reads and writes the Android calendar (CalendarContract), so Google Agenda and others stay in sync. */
object CalendarRepo {

    data class Calendar(val id: Long, val name: String, val account: String, val color: Int, val writable: Boolean, val visible: Boolean)

    /** What the editor saves; [eventId] null for a new contract. */
    data class Draft(
        val eventId: Long?,
        val calendarId: Long,
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val location: String,
        val description: String,
        /** Minutes before the start, null for no reminder. */
        val reminder: Int?,
    )

    fun granted(context: Context) = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun calendars(context: Context): List<Calendar> {
        val out = mutableListOf<Calendar>()
        context.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE),
            null, null, "${Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                out += Calendar(
                    id = c.getLong(0),
                    name = c.getString(1) ?: "Agenda",
                    account = c.getString(2) ?: "",
                    color = c.getInt(3),
                    writable = c.getInt(4) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                    visible = c.getInt(5) == 1,
                )
            }
        }
        return out
    }

    /** Event instances (recurrences expanded) overlapping [from]..[to], from the calendars not [hidden]. */
    fun instances(context: Context, from: Long, to: Long, hidden: Set<Long>): List<Contract> {
        val names = calendars(context).associate { it.id to it.name }
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val out = mutableListOf<Contract>()
        context.contentResolver.query(
            uri,
            arrayOf(
                Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
                Instances.EVENT_LOCATION, Instances.DESCRIPTION, Instances.CALENDAR_ID, Instances.DISPLAY_COLOR, Instances.RRULE,
            ),
            "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val calendarId = c.getLong(7)
                if (calendarId in hidden) continue
                out += Contract(
                    eventId = c.getLong(0),
                    title = c.getString(1)?.ifBlank { null } ?: "Contrat sans nom",
                    begin = c.getLong(2),
                    end = c.getLong(3),
                    allDay = c.getInt(4) == 1,
                    location = c.getString(5)?.ifBlank { null },
                    description = c.getString(6)?.ifBlank { null },
                    calendarId = calendarId,
                    calendarName = names[calendarId] ?: "",
                    color = c.getInt(8),
                    recurring = !c.getString(9).isNullOrBlank(),
                )
            }
        }
        return out
    }

    /** The event itself (not an instance), for the editor. */
    fun event(context: Context, id: Long): Contract? {
        context.contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, id),
            arrayOf(Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.CALENDAR_ID, Events.RRULE, Events.DISPLAY_COLOR),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val begin = c.getLong(1)
            return Contract(
                eventId = id,
                title = c.getString(0) ?: "",
                begin = begin,
                end = if (c.isNull(2)) begin + 3_600_000 else c.getLong(2),
                allDay = c.getInt(3) == 1,
                location = c.getString(4),
                description = c.getString(5),
                calendarId = c.getLong(6),
                recurring = !c.getString(7).isNullOrBlank(),
                color = c.getInt(8),
            )
        }
        return null
    }

    /** First reminder of an event, in minutes, or null. */
    fun reminder(context: Context, eventId: Long): Int? =
        context.contentResolver.query(
            Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), "${Reminders.MINUTES} ASC",
        )?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }

    /** Inserts or updates; returns the event id. */
    fun save(context: Context, d: Draft): Long {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, d.calendarId)
            put(Events.TITLE, d.title.trim())
            put(Events.EVENT_LOCATION, d.location.trim())
            put(Events.DESCRIPTION, d.description.trim())
            put(Events.DTSTART, d.begin)
            put(Events.DTEND, d.end)
            put(Events.ALL_DAY, if (d.allDay) 1 else 0)
            // All-day events live in UTC (midnight to midnight), timed ones in the phone's zone.
            put(Events.EVENT_TIMEZONE, if (d.allDay) "UTC" else TimeZone.getDefault().id)
            put(Events.HAS_ALARM, if (d.reminder != null) 1 else 0)
        }
        val resolver = context.contentResolver
        val id = if (d.eventId == null) {
            val uri: Uri = resolver.insert(Events.CONTENT_URI, values) ?: error("Agenda refusé par Android")
            ContentUris.parseId(uri)
        } else {
            resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, d.eventId), values, null, null)
            resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(d.eventId.toString()))
            d.eventId
        }
        if (d.reminder != null) {
            resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                put(Reminders.EVENT_ID, id)
                put(Reminders.MINUTES, d.reminder)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
        return id
    }

    fun delete(context: Context, eventId: Long) {
        context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
    }
}
