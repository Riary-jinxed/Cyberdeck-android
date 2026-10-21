package dev.netrunner.clock.data

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** A countdown. Running: [endAt] is the wall-clock end. Paused: [remainingMs] is what is left. */
data class CountdownTimer(
    val id: Int,
    val totalMs: Long,
    val label: String = "",
    val endAt: Long = 0L,
    val remainingMs: Long = totalMs,
    val running: Boolean = false,
) {
    fun remaining(now: Long = System.currentTimeMillis()) =
        if (running) (endAt - now).coerceAtLeast(0L) else remainingMs

    fun start(now: Long = System.currentTimeMillis()) = copy(running = true, endAt = now + remainingMs)
    fun pause(now: Long = System.currentTimeMillis()) = copy(running = false, remainingMs = remaining(now))
    fun reset() = copy(running = false, remainingMs = totalMs, endAt = 0L)
    fun plus(ms: Long) = if (running) copy(endAt = endAt + ms, totalMs = totalMs + ms)
    else copy(remainingMs = remainingMs + ms, totalMs = totalMs + ms)

    fun toJson() = JSONObject().put("id", id).put("total", totalMs).put("label", label)
        .put("endAt", endAt).put("remaining", remainingMs).put("running", running)

    companion object {
        fun fromJson(o: JSONObject) = CountdownTimer(
            o.getInt("id"), o.getLong("total"), o.optString("label"),
            o.optLong("endAt"), o.optLong("remaining"), o.optBoolean("running"),
        )
    }
}

object TimerStore {
    private fun prefs(context: Context) = context.getSharedPreferences("clock_timers", Context.MODE_PRIVATE)

    fun all(context: Context): List<CountdownTimer> {
        val raw = prefs(context).getString("timers", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { CountdownTimer.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedBy { it.id }
    }

    fun get(context: Context, id: Int) = all(context).firstOrNull { it.id == id }

    @Synchronized
    fun save(context: Context, t: CountdownTimer) = write(context, all(context).filter { it.id != t.id } + t)

    @Synchronized
    fun delete(context: Context, id: Int) = write(context, all(context).filter { it.id != id })

    fun newId(context: Context): Int {
        val p = prefs(context)
        val id = p.getInt("next_id", 1)
        p.edit().putInt("next_id", id + 1).apply()
        return id
    }

    private fun write(context: Context, list: List<CountdownTimer>) {
        prefs(context).edit().putString("timers", JSONArray(list.map { it.toJson() }).toString()).apply()
        listeners.forEach { it() }
    }

    val listeners = mutableSetOf<() -> Unit>()
}

/** Stopwatch on the monotonic clock: no background work, it is only read when shown. */
object Stopwatch {
    data class State(val running: Boolean, val startedAt: Long, val accumulated: Long, val laps: List<Long>) {
        fun elapsed(now: Long = SystemClock.elapsedRealtime()) = accumulated + if (running) now - startedAt else 0L
    }

    private fun prefs(context: Context) = context.getSharedPreferences("clock_stopwatch", Context.MODE_PRIVATE)

    fun load(context: Context): State {
        val p = prefs(context)
        val laps = p.getString("laps", "")!!.split(',').mapNotNull { it.toLongOrNull() }
        val state = State(p.getBoolean("running", false), p.getLong("started", 0L), p.getLong("acc", 0L), laps)
        // elapsedRealtime restarts at boot: a stopwatch left running across a reboot is stopped.
        return if (state.running && state.startedAt > SystemClock.elapsedRealtime()) state.copy(running = false) else state
    }

    fun save(context: Context, s: State) {
        prefs(context).edit().putBoolean("running", s.running).putLong("started", s.startedAt)
            .putLong("acc", s.accumulated).putString("laps", s.laps.joinToString(",")).apply()
    }

    fun toggle(s: State, now: Long = SystemClock.elapsedRealtime()) =
        if (s.running) s.copy(running = false, accumulated = s.elapsed(now)) else s.copy(running = true, startedAt = now)

    fun lap(s: State) = s.copy(laps = s.laps + s.elapsed())
    fun reset() = State(false, 0L, 0L, emptyList())
}
