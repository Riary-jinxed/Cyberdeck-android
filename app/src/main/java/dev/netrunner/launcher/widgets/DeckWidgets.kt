package dev.netrunner.launcher.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf

/** Android widgets (calendar, Maps, Keep…) hosted on the launcher's widget page. */
object DeckWidgets {
    private const val HOST_ID = 0x4E52

    private var host: AppWidgetHost? = null
    val ids = mutableStateListOf<Int>()
    /** Height step per widget: 0 = provider size, 1 and 2 = taller. */
    val heights = mutableStateMapOf<Int, Int>()
    private var loaded = false

    fun host(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }

    fun manager(context: Context): AppWidgetManager = AppWidgetManager.getInstance(context)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        val saved = p.getString("widgets", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
        // Drop ids Android no longer knows (app uninstalled, data cleared).
        val alive = saved.filter { manager(context).getAppWidgetInfo(it) != null }
        ids.clear()
        ids.addAll(alive)
        alive.forEach { heights[it] = p.getInt("widget_h_$it", 0) }
        if (alive != saved) save(context)
    }

    fun startListening(context: Context) = runCatching { host(context).startListening() }
    fun stopListening(context: Context) = runCatching { host(context).stopListening() }

    /** Installed widget providers, grouped by app label then widget label. */
    fun providers(context: Context): List<Pair<String, AppWidgetProviderInfo>> {
        val pm = context.packageManager
        return manager(context).installedProviders.map { info ->
            val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }
                .getOrDefault(info.provider.packageName)
            "$app · ${info.loadLabel(pm)}" to info
        }.sortedBy { it.first.lowercase() }
    }

    fun allocate(context: Context): Int = host(context).allocateAppWidgetId()

    fun bindIfAllowed(context: Context, id: Int, info: AppWidgetProviderInfo): Boolean =
        runCatching { manager(context).bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)

    fun add(context: Context, id: Int) {
        if (id !in ids) ids += id
        save(context)
    }

    fun discard(context: Context, id: Int) {
        runCatching { host(context).deleteAppWidgetId(id) }
    }

    fun remove(context: Context, id: Int) {
        discard(context, id)
        ids.remove(id)
        heights.remove(id)
        save(context)
    }

    fun move(context: Context, id: Int, delta: Int) {
        val i = ids.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in ids.indices) return
        ids.removeAt(i)
        ids.add(j, id)
        save(context)
    }

    fun cycleHeight(context: Context, id: Int) {
        val next = ((heights[id] ?: 0) + 1) % 3
        heights[id] = next
        prefs(context).edit().putInt("widget_h_$id", next).apply()
    }

    private fun save(context: Context) {
        prefs(context).edit().putString("widgets", ids.joinToString(",")).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}
