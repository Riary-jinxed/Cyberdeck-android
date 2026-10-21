package dev.netrunner.launcher.system

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Screen time, unlocks and most used apps, read from Android's own usage history
 * ("usage access" permission). Everything is computed on the phone and nothing is stored.
 */
object UsageReport {
    data class AppUsage(val packageName: String, val label: String, val foregroundMs: Long, val opens: Int)

    data class Day(val date: LocalDate, val screenMs: Long, val unlocks: Int, val opens: Int, val apps: List<AppUsage>)

    /** The last [DAYS] days, oldest first; the last one is today. */
    data class Report(val days: List<Day>) {
        val today: Day get() = days.last()

        /** Apps over the whole period, most used first. */
        val weekApps: List<AppUsage>
            get() = days.flatMap { it.apps }
                .groupBy { it.packageName }
                .map { (pkg, list) -> AppUsage(pkg, list.first().label, list.sumOf { it.foregroundMs }, list.sumOf { it.opens }) }
                .sortedByDescending { it.foregroundMs }
    }

    const val DAYS = 7

    fun granted(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun openAccessSettings(context: Context) {
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(direct) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Replays the usage events of the last [DAYS] days. Slow-ish: call it off the main thread. */
    fun load(context: Context): Report? {
        if (!granted(context)) return null
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dates = (DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val begin = dates.first().atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        val time = dates.associateWith { mutableMapOf<String, Long>() }
        val opens = dates.associateWith { mutableMapOf<String, Int>() }
        val unlocks = dates.associateWith { intArrayOf(0) }
        fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

        // Adds a foreground stretch, split at midnight when it crosses one.
        fun add(pkg: String, from: Long, to: Long) {
            var a = from
            while (a < to) {
                val day = dayOf(a)
                val end = minOf(to, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
                time[day]?.merge(pkg, end - a, Long::plus)
                a = end
            }
        }

        replay(manager, begin, now, context.packageName,
            onStretch = ::add,
            onOpen = { pkg, t -> opens[dayOf(t)]?.merge(pkg, 1, Int::plus) },
            onUnlock = { t -> unlocks[dayOf(t)]?.let { it[0]++ } },
        )

        val pm = context.packageManager
        val labels = mutableMapOf<String, String>()
        fun label(pkg: String) = labels.getOrPut(pkg) {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }

        val own = context.packageName
        return Report(
            dates.map { date ->
                val perApp = time.getValue(date)
                val opened = opens.getValue(date)
                // The launcher itself is the home screen, not "screen time".
                val apps = (perApp.keys + opened.keys).filter { it != own }.map { pkg ->
                    AppUsage(pkg, label(pkg), perApp[pkg] ?: 0L, opened[pkg] ?: 0)
                }.filter { it.foregroundMs >= 1_000 || it.opens > 0 }.sortedByDescending { it.foregroundMs }
                Day(date, apps.sumOf { it.foregroundMs }, unlocks.getValue(date)[0], apps.sumOf { it.opens }, apps)
            }
        )
    }

    /** Foreground time per app since midnight, and the app in front right now (null: none or home). */
    class Today(val foregroundMs: Map<String, Long>, val current: String?)

    fun today(context: Context): Today? {
        if (!granted(context)) return null
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val begin = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val time = mutableMapOf<String, Long>()
        val current = replay(manager, begin, System.currentTimeMillis(), context.packageName,
            onStretch = { pkg, a, b -> time.merge(pkg, b - a, Long::plus) },
            onOpen = { _, _ -> },
            onUnlock = { },
        )
        return Today(time, current)
    }

    /**
     * Walks the usage events between [begin] and [now]: reports each foreground stretch, each app
     * opening and each unlock. Returns the app still in front at the end, if any.
     */
    private fun replay(
        manager: UsageStatsManager,
        begin: Long,
        now: Long,
        own: String,
        onStretch: (pkg: String, from: Long, to: Long) -> Unit,
        onOpen: (pkg: String, time: Long) -> Unit,
        onUnlock: (time: Long) -> Unit,
    ): String? {
        var current: String? = null
        var currentClass: String? = null
        var since = 0L
        val events = manager.queryEvents(begin, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val t = e.timeStamp
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (pkg != current) {
                        current?.let { onStretch(it, since, t) }
                        if (pkg != own) onOpen(pkg, t)
                        current = pkg
                        since = t
                    }
                    currentClass = e.className
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    // A paused activity only ends the stretch if it is the one in front.
                    if (pkg == current && e.className == currentClass) {
                        onStretch(pkg, since, t)
                        current = null
                    }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    current?.let { onStretch(it, since, t) }
                    current = null
                }
                UsageEvents.Event.KEYGUARD_HIDDEN -> onUnlock(t)
            }
        }
        current?.let { onStretch(it, since, now) }
        return current?.takeIf { it != own }
    }
}
