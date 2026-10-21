package dev.netrunner.launcher.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Small local battery history (one line per level change, in the app's private files), so the
 * stats screen can show how much the battery drained and charged each day.
 */
object BatteryLog {
    data class Day(val date: LocalDate, val drained: Int, val charged: Int)

    private const val FILE = "battery_log.csv"
    private const val KEEP_DAYS = 8L

    private var lastLevel = -1
    private var lastCharging = false

    /** Appends a sample when the level or the charging state changed. */
    @Synchronized
    fun record(context: Context, state: BatteryState) {
        if (state.level !in 0..100) return
        if (state.level == lastLevel && state.charging == lastCharging) return
        lastLevel = state.level
        lastCharging = state.charging
        runCatching {
            val file = File(context.filesDir, FILE)
            file.appendText("${System.currentTimeMillis()},${state.level},${if (state.charging) 1 else 0}\n")
            // Trim now and then: keeps about a week, a few KB at most.
            if (file.length() > 64_000) prune(file)
        }
    }

    /** Listens to battery changes. Hosted by the notification listener, which always runs. */
    fun register(context: Context): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = record(c, SystemInfo.battery(c))
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        return receiver
    }

    private class Sample(val time: Long, val level: Int, val charging: Boolean)

    private fun read(file: File): List<Sample> = runCatching {
        file.readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size != 3) null else Sample(p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toIntOrNull() ?: 0, p[2] == "1")
        }
    }.getOrDefault(emptyList())

    private fun prune(file: File) {
        val limit = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val kept = read(file).filter { it.time >= limit }
        file.writeText(kept.joinToString("") { "${it.time},${it.level},${if (it.charging) 1 else 0}\n" })
    }

    /** Percent drained and charged per day for [dates]; days without data stay at 0. */
    fun days(context: Context, dates: List<LocalDate>): List<Day> {
        val zone = ZoneId.systemDefault()
        val drained = mutableMapOf<LocalDate, Int>()
        val charged = mutableMapOf<LocalDate, Int>()
        read(File(context.filesDir, FILE)).zipWithNext { a, b ->
            val day = Instant.ofEpochMilli(b.time).atZone(zone).toLocalDate()
            val delta = b.level - a.level
            if (delta < 0) drained.merge(day, -delta, Int::plus)
            if (delta > 0) charged.merge(day, delta, Int::plus)
        }
        return dates.map { Day(it, drained[it] ?: 0, charged[it] ?: 0) }
    }
}
