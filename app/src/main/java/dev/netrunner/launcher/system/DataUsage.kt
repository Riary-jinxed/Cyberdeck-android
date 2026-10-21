package dev.netrunner.launcher.system

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import java.time.LocalDate
import java.time.ZoneId

/**
 * Wi-Fi and mobile data used per day and per app, read from Android's network statistics (same
 * "usage access" permission as the screen time). Read only, nothing leaves the phone.
 */
object DataUsage {
    data class Day(val date: LocalDate, val wifi: Long, val mobile: Long)

    data class App(val label: String, val packageName: String?, val wifi: Long, val mobile: Long) {
        val total get() = wifi + mobile
    }

    class Report(val days: List<Day>, val today: List<App>, val week: List<App>)

    /** Bytes as "1,2 Go", "340 Mo", "12 Ko". */
    fun format(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f Go".format(bytes / 1e9)
        bytes >= 10_000_000L -> "${bytes / 1_000_000} Mo"
        bytes >= 1_000_000L -> "%.1f Mo".format(bytes / 1e6)
        else -> "${bytes / 1000} Ko"
    }

    /** Last [days] days, oldest first. Off the main thread. Null without usage access. */
    fun load(context: Context, days: Int = UsageReport.DAYS): Report? {
        if (!UsageReport.granted(context)) return null
        val manager = context.getSystemService(NetworkStatsManager::class.java) ?: return null
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

        fun device(type: Int, from: Long, to: Long): Long = runCatching {
            manager.querySummaryForDevice(type, null, from, to).let { it.rxBytes + it.txBytes }
        }.getOrDefault(0L)

        val dates = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val perDay = dates.map { d ->
            val from = start(d)
            val to = minOf(now, start(d.plusDays(1)))
            Day(d, device(ConnectivityManager.TYPE_WIFI, from, to), device(ConnectivityManager.TYPE_MOBILE, from, to))
        }
        return Report(perDay, apps(context, manager, start(today), now), apps(context, manager, start(dates.first()), now))
    }

    private fun apps(context: Context, manager: NetworkStatsManager, from: Long, to: Long): List<App> {
        val wifi = perUid(manager, ConnectivityManager.TYPE_WIFI, from, to)
        val mobile = perUid(manager, ConnectivityManager.TYPE_MOBILE, from, to)
        val pm = context.packageManager
        return (wifi.keys + mobile.keys).map { uid ->
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
            val label = when {
                uid == NetworkStats.Bucket.UID_TETHERING -> "Partage de connexion"
                uid == NetworkStats.Bucket.UID_REMOVED -> "Applis désinstallées"
                pkg == null || uid < 10_000 -> "Système Android"
                else -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            }
            App(label, pkg?.takeIf { uid >= 10_000 }, wifi[uid] ?: 0L, mobile[uid] ?: 0L)
        }
            // System uids share one label: merge them.
            .groupBy { it.label }
            .map { (label, list) -> App(label, list.first().packageName, list.sumOf { it.wifi }, list.sumOf { it.mobile }) }
            .filter { it.total > 0 }
            .sortedByDescending { it.total }
    }

    private fun perUid(manager: NetworkStatsManager, type: Int, from: Long, to: Long): Map<Int, Long> {
        val out = mutableMapOf<Int, Long>()
        runCatching {
            val stats = manager.querySummary(type, null, from, to)
            try {
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    out.merge(bucket.uid, bucket.rxBytes + bucket.txBytes, Long::plus)
                }
            } finally {
                stats.close()
            }
        }
        return out
    }
}
