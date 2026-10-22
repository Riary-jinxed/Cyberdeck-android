package dev.netrunner.scanner.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The last scans, newest first, in the app's preferences. */
object History {
    data class Scan(val raw: String, val format: String, val time: Long)

    private const val SIZE = 100

    private fun prefs(context: Context) = context.getSharedPreferences("scanner", Context.MODE_PRIVATE)

    fun all(context: Context): List<Scan> = runCatching {
        val a = JSONArray(prefs(context).getString("history", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Scan(o.getString("r"), o.optString("f"), o.optLong("t")) } }
    }.getOrDefault(emptyList())

    /** Adds [scan] on top; the same content scanned again moves up instead of repeating. */
    fun add(context: Context, scan: Scan): List<Scan> = save(context, (listOf(scan) + all(context).filter { it.raw != scan.raw }).take(SIZE))

    fun remove(context: Context, scan: Scan): List<Scan> = save(context, all(context).filter { it != scan })

    fun clear(context: Context): List<Scan> = save(context, emptyList())

    private fun save(context: Context, list: List<Scan>): List<Scan> {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("r", it.raw).put("f", it.format).put("t", it.time)) }
        prefs(context).edit().putString("history", a.toString()).apply()
        return list
    }

    fun vibrate(context: Context) = prefs(context).getBoolean("vibrate", true)
    fun setVibrate(context: Context, on: Boolean) = prefs(context).edit().putBoolean("vibrate", on).apply()

    /** Open links straight away instead of showing the card first. */
    fun autoOpen(context: Context) = prefs(context).getBoolean("auto_open", false)
    fun setAutoOpen(context: Context, on: Boolean) = prefs(context).edit().putBoolean("auto_open", on).apply()

    /** Inventory mode: every code goes straight to the launcher's field kit. */
    fun kitMode(context: Context) = prefs(context).getBoolean("kit_mode", false)
    fun setKitMode(context: Context, on: Boolean) = prefs(context).edit().putBoolean("kit_mode", on).apply()
}
