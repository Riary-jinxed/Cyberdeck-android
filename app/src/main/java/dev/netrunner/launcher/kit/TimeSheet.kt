package dev.netrunner.launcher.kit

import android.content.Context
import android.net.Uri
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Time spent per client and per month, from the closed interventions of the logbook. */
object TimeSheet {
    data class Row(val month: YearMonth, val client: String, val count: Int, val minutes: Long)

    private val zone get() = ZoneId.systemDefault()

    fun minutes(i: Intervention): Long = if (i.open) 0 else ((i.ended - i.started) / 60000).coerceAtLeast(0)

    fun monthOf(i: Intervention): YearMonth = YearMonth.from(Instant.ofEpochMilli(i.started).atZone(zone))

    /** Rows for [month] (all months when null), biggest client first. */
    fun rows(items: List<Intervention>, month: YearMonth?): List<Row> =
        items.filter { !it.open && (month == null || monthOf(it) == month) }
            .groupBy { monthOf(it) to it.client.trim().ifBlank { "(sans client)" } }
            .map { (key, list) -> Row(key.first, key.second, list.size, list.sumOf(::minutes)) }
            .sortedWith(compareByDescending<Row> { it.month }.thenByDescending { it.minutes })

    fun months(items: List<Intervention>): List<YearMonth> = items.filter { !it.open }.map(::monthOf).distinct().sortedDescending()

    fun hours(minutes: Long) = "%d h %02d".format(minutes / 60, minutes % 60)

    /** Decimal hours for spreadsheets, with a French decimal comma. */
    private fun decimal(minutes: Long) = String.format(Locale.FRANCE, "%.2f", minutes / 60.0)

    /** One line per closed intervention of [month] (all when null), ready for invoicing. */
    fun exportCsv(context: Context, uri: Uri, items: List<Intervention>, month: YearMonth?): Boolean = runCatching {
        val day = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(zone)
        val hour = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        fun cell(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
        val text = buildString {
            appendLine("Date;Client;Site;Intervention;Ticket;Début;Fin;Heures;Consommables")
            items.filter { !it.open && (month == null || monthOf(it) == month) }.sortedBy { it.started }.forEach { i ->
                appendLine(
                    listOf(
                        day.format(Instant.ofEpochMilli(i.started)), cell(i.client), cell(i.site), cell(i.title), cell(i.ticket),
                        hour.format(Instant.ofEpochMilli(i.started)), hour.format(Instant.ofEpochMilli(i.ended)), decimal(minutes(i)),
                        cell(i.consumed.joinToString(", ") { "${it.qty} ${it.unit} ${it.name}" }),
                    ).joinToString(";")
                )
            }
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(("﻿" + text).toByteArray()) } ?: error("no stream")
    }.isSuccess
}
