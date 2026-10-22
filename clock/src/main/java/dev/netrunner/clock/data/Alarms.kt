package dev.netrunner.clock.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar

/**
 * One alarm. [days] uses java.util.Calendar day numbers (SUNDAY = 1 … SATURDAY = 7), like the
 * AlarmClock intents As//ka sends; empty means it rings once.
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(),
    val label: String = "",
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    /** Ringtone URI, null for the phone's default alarm sound. */
    val tone: String? = null,
    /** Excerpt of the tone to loop, in ms (end 0 = to the end of the file). */
    val toneStart: Long = 0L,
    val toneEnd: Long = 0L,
    /** When set, the alarm only stops by scanning this QR or barcode. */
    val qrCode: String = "",
    /** Wall-clock time of a pending snooze, 0 when none. */
    val snoozedUntil: Long = 0L,
) {
    val repeats get() = days.isNotEmpty()

    /** Next time it rings after [now], snooze included, or null when it is off. */
    fun nextTrigger(now: Long = System.currentTimeMillis()): Long? {
        if (!enabled) return null
        if (snoozedUntil > now) return snoozedUntil
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        for (d in 0..7) {
            val date = today.plusDays(d.toLong())
            if (repeats && toCalendarDay(date.dayOfWeek) !in days) continue
            val at = ZonedDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, minute, 0, 0, zone)
                .toInstant().toEpochMilli()
            if (at > now) return at
        }
        return null
    }

    fun toJson() = JSONObject()
        .put("id", id).put("hour", hour).put("minute", minute)
        .put("days", JSONArray(days.sorted())).put("label", label)
        .put("enabled", enabled).put("vibrate", vibrate)
        .put("tone", tone ?: "").put("toneStart", toneStart).put("toneEnd", toneEnd)
        .put("qr", qrCode).put("snoozedUntil", snoozedUntil)

    companion object {
        fun fromJson(o: JSONObject) = Alarm(
            id = o.getInt("id"),
            hour = o.getInt("hour"),
            minute = o.getInt("minute"),
            days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
            label = o.optString("label"),
            enabled = o.optBoolean("enabled", true),
            vibrate = o.optBoolean("vibrate", true),
            tone = o.optString("tone").ifEmpty { null },
            toneStart = o.optLong("toneStart"),
            toneEnd = o.optLong("toneEnd"),
            qrCode = o.optString("qr"),
            snoozedUntil = o.optLong("snoozedUntil"),
        )

        fun toCalendarDay(d: DayOfWeek) = d.value % 7 + 1

        /** Monday first, the French way. */
        val WEEK = listOf(
            Calendar.MONDAY to "L", Calendar.TUESDAY to "M", Calendar.WEDNESDAY to "M", Calendar.THURSDAY to "J",
            Calendar.FRIDAY to "V", Calendar.SATURDAY to "S", Calendar.SUNDAY to "D",
        )

        fun daysLabel(days: Set<Int>): String = when (days) {
            emptySet<Int>() -> "UNE FOIS"
            WEEK.map { it.first }.toSet() -> "TOUS LES JOURS"
            WEEK.take(5).map { it.first }.toSet() -> "EN SEMAINE"
            setOf(Calendar.SATURDAY, Calendar.SUNDAY) -> "WEEK-END"
            else -> WEEK.filter { it.first in days }.joinToString(" ") { (d, _) ->
                listOf("", "DIM", "LUN", "MAR", "MER", "JEU", "VEN", "SAM")[d]
            }
        }
    }
}

/** Alarms saved as one JSON list. Small, read in full every time. */
object AlarmStore {
    private const val PREFS = "clock"
    private const val KEY = "alarms"

    fun all(context: Context): List<Alarm> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { Alarm.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    fun get(context: Context, id: Int) = all(context).firstOrNull { it.id == id }

    @Synchronized
    fun save(context: Context, alarm: Alarm) {
        val list = all(context).filter { it.id != alarm.id } + alarm
        write(context, list)
    }

    @Synchronized
    fun delete(context: Context, id: Int) = write(context, all(context).filter { it.id != id })

    fun newId(context: Context): Int {
        val p = prefs(context)
        val id = p.getInt("next_id", 1)
        p.edit().putInt("next_id", id + 1).apply()
        return id
    }

    private fun write(context: Context, list: List<Alarm>) {
        prefs(context).edit().putString(KEY, JSONArray(list.map { it.toJson() }).toString()).apply()
        listeners.forEach { it() }
    }

    /** UI refresh hooks (same process only). */
    val listeners = mutableSetOf<() -> Unit>()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Clock-wide settings. */
object ClockPrefs {
    private fun p(context: Context) = context.getSharedPreferences("clock", Context.MODE_PRIVATE)

    fun snoozeMinutes(context: Context) = p(context).getInt("snooze", 9)
    fun setSnoozeMinutes(context: Context, v: Int) = p(context).edit().putInt("snooze", v).apply()

    /** Volume rises from quiet to full over this many seconds (0 = full at once). */
    fun crescendoSeconds(context: Context) = p(context).getInt("crescendo", 30)
    fun setCrescendoSeconds(context: Context, v: Int) = p(context).edit().putInt("crescendo", v).apply()

    /** Rings stop by themselves after this many minutes. */
    fun ringMinutes(context: Context) = p(context).getInt("ring_minutes", 10)
    fun setRingMinutes(context: Context, v: Int) = p(context).edit().putInt("ring_minutes", v).apply()

    /** After a morning alarm, go back to the home screen so As//ka can greet. */
    fun homeAfterMorning(context: Context) = p(context).getBoolean("home_after_morning", true)
    fun setHomeAfterMorning(context: Context, v: Boolean) = p(context).edit().putBoolean("home_after_morning", v).apply()
}
