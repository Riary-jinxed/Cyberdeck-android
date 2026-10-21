package dev.netrunner.calc.data

import android.content.Context
import dev.netrunner.calc.engine.ProgCalc
import org.json.JSONArray
import org.json.JSONObject

/** Small settings and the calculation history, in the app's preferences. */
object CalcPrefs {
    data class Entry(val expression: String, val result: String)

    private const val HISTORY_SIZE = 50

    private fun prefs(context: Context) = context.getSharedPreferences("calc", Context.MODE_PRIVATE)

    fun tab(context: Context) = prefs(context).getInt("tab", 0)
    fun setTab(context: Context, tab: Int) = prefs(context).edit().putInt("tab", tab).apply()

    fun degrees(context: Context) = prefs(context).getBoolean("degrees", true)
    fun setDegrees(context: Context, on: Boolean) = prefs(context).edit().putBoolean("degrees", on).apply()

    fun progMode(context: Context): ProgCalc.Mode {
        val p = prefs(context)
        val width = ProgCalc.Width.entries.firstOrNull { it.name == p.getString("width", null) } ?: ProgCalc.Width.QWORD
        return ProgCalc.Mode(width, p.getBoolean("signed", true))
    }

    fun setProgMode(context: Context, mode: ProgCalc.Mode) =
        prefs(context).edit().putString("width", mode.width.name).putBoolean("signed", mode.signed).apply()

    fun progBase(context: Context) =
        ProgCalc.Base.entries.firstOrNull { it.name == prefs(context).getString("base", null) } ?: ProgCalc.Base.HEX

    fun setProgBase(context: Context, base: ProgCalc.Base) = prefs(context).edit().putString("base", base.name).apply()

    /** Newest first. */
    fun history(context: Context): List<Entry> = runCatching {
        val json = JSONArray(prefs(context).getString("history", "[]"))
        (0 until json.length()).map { json.getJSONObject(it).let { o -> Entry(o.getString("e"), o.getString("r")) } }
    }.getOrDefault(emptyList())

    fun addHistory(context: Context, entry: Entry): List<Entry> {
        val list = (listOf(entry) + history(context).filter { it != entry }).take(HISTORY_SIZE)
        save(context, list)
        return list
    }

    fun clearHistory(context: Context) = save(context, emptyList())

    private fun save(context: Context, list: List<Entry>) {
        val json = JSONArray()
        list.forEach { json.put(JSONObject().put("e", it.expression).put("r", it.result)) }
        prefs(context).edit().putString("history", json.toString()).apply()
    }
}
