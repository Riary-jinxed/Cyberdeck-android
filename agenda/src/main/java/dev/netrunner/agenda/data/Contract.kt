package dev.netrunner.agenda.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** One calendar event instance or one As//ka reminder, shown as a "contract". */
data class Contract(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String? = null,
    val description: String? = null,
    val calendarId: Long = -1,
    val calendarName: String = "",
    val color: Int = 0,
    val recurring: Boolean = false,
    val source: Source = Source.CALENDAR,
    val done: Boolean = false,
) {
    enum class Source { CALENDAR, ASKA }
}

/** Dates, groupings and labels, free of Android so they can be tested anywhere. */
object ContractTime {
    private val FR = Locale.FRANCE
    private val hm = DateTimeFormatter.ofPattern("HH:mm", FR)

    /** All-day events are stored at UTC midnight: their dates are read in UTC, not in the local zone. */
    private fun zoneOf(c: Contract, zone: ZoneId): ZoneId = if (c.allDay) ZoneOffset.UTC else zone

    fun startDate(c: Contract, zone: ZoneId): LocalDate = Instant.ofEpochMilli(c.begin).atZone(zoneOf(c, zone)).toLocalDate()

    /** Every day the contract touches (an all-day event's end is exclusive). */
    fun days(c: Contract, zone: ZoneId): List<LocalDate> {
        val z = zoneOf(c, zone)
        val first = Instant.ofEpochMilli(c.begin).atZone(z).toLocalDate()
        val lastInstant = if (c.end > c.begin) c.end - 1 else c.begin
        val last = Instant.ofEpochMilli(lastInstant).atZone(z).toLocalDate()
        val out = mutableListOf<LocalDate>()
        var d = first
        while (!d.isAfter(last) && out.size < 366) { out += d; d = d.plusDays(1) }
        return out
    }

    /** Contracts by day within [from]..[to], each day sorted with all-day first, then by start time. */
    fun group(contracts: List<Contract>, zone: ZoneId, from: LocalDate, to: LocalDate): Map<LocalDate, List<Contract>> {
        val map = sortedMapOf<LocalDate, MutableList<Contract>>()
        for (c in contracts) for (d in days(c, zone)) {
            if (d.isBefore(from) || d.isAfter(to)) continue
            map.getOrPut(d) { mutableListOf() } += c
        }
        return map.mapValues { (_, list) -> list.sortedWith(compareBy<Contract>({ !it.allDay }, { it.begin }, { it.title })) }
    }

    /** EN COURS, DANS 12 MIN, DANS 3 H, TERMINÉ, or empty when far away. */
    fun status(c: Contract, now: Long): String {
        if (c.source == Contract.Source.ASKA) return if (c.done || c.begin < now) "PASSÉ" else relative(c.begin - now)
        return when {
            c.end <= now -> "TERMINÉ"
            c.begin <= now -> "EN COURS"
            c.allDay -> ""
            else -> relative(c.begin - now)
        }
    }

    private fun relative(ms: Long): String {
        val min = (ms + 59_999) / 60_000
        return when {
            min < 60 -> "DANS $min MIN"
            min < 24 * 60 -> "DANS ${min / 60} H" + (min % 60).let { if (it >= 5) " ${"%02d".format(it)}" else "" }
            else -> ""
        }
    }

    fun timeRange(c: Contract, zone: ZoneId): String {
        if (c.source == Contract.Source.ASKA) return Instant.ofEpochMilli(c.begin).atZone(zone).format(hm)
        if (c.allDay) return "JOURNÉE"
        val b = Instant.ofEpochMilli(c.begin).atZone(zone)
        val e = Instant.ofEpochMilli(c.end).atZone(zone)
        return if (b.toLocalDate() == e.toLocalDate()) "${b.format(hm)} – ${e.format(hm)}" else "${b.format(hm)} → ${shortDate(e.toLocalDate())} ${e.format(hm)}"
    }

    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "AUJOURD'HUI"
        today.plusDays(1) -> "DEMAIN"
        today.minusDays(1) -> "HIER"
        else -> longDate(date)
    }

    /** MERCREDI 23 OCTOBRE (with the year when it is not the current one). */
    fun longDate(date: LocalDate, today: LocalDate = LocalDate.now()): String {
        val base = "${date.dayOfWeek.getDisplayName(TextStyle.FULL, FR)} ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, FR)}"
        return (if (date.year != today.year) "$base ${date.year}" else base).uppercase(FR)
    }

    /** 23/10 */
    fun shortDate(date: LocalDate): String = "%02d/%02d".format(date.dayOfMonth, date.monthValue)

    fun monthTitle(year: Int, month: Int): String =
        "${java.time.Month.of(month).getDisplayName(TextStyle.FULL, FR)} $year".uppercase(FR)

    /** Monday-first weeks covering the month, padded with the neighbouring days. */
    fun monthGrid(year: Int, month: Int): List<LocalDate> {
        val first = LocalDate.of(year, month, 1)
        val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
        val last = first.plusMonths(1).minusDays(1)
        val end = last.plusDays((7 - last.dayOfWeek.value).toLong())
        val out = mutableListOf<LocalDate>()
        var d = start
        while (!d.isAfter(end)) { out += d; d = d.plusDays(1) }
        return out
    }
}
