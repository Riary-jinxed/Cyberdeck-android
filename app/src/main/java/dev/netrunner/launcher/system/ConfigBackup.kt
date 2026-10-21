package dev.netrunner.launcher.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import dev.netrunner.launcher.MainActivity
import org.json.JSONObject

/**
 * Exports every Netrunner setting (faction, favorites, folders, gestures, profiles, usage counters…)
 * to a JSON file the user picks, and restores it. Widgets are not included: Android ties them to
 * the install.
 */
object ConfigBackup {
    private val files = listOf("netrunner", "netrunner_usage")
    private val skipped = setOf("widgets")

    fun export(context: Context, uri: Uri): Boolean = runCatching {
        val root = JSONObject().put("app", "netrunner").put("format", 1)
        files.forEach { name ->
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                if (k in skipped || k.startsWith("widget_h_") || v == null) return@forEach
                val typed = when (v) {
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", v)
                    else -> null
                }
                typed?.let { values.put(k, it) }
            }
            root.put(name, values)
        }
        // Field kit data (inventory and checklists) travels with the config.
        java.io.File(context.filesDir, "kit.json").takeIf { it.exists() }?.let { root.put("kit", JSONObject(it.readText())) }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(root.toString(2).toByteArray()) } ?: error("no stream")
    }.isSuccess

    fun restore(context: Context, uri: Uri): Boolean = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("no stream")
        val root = JSONObject(text)
        require(root.optString("app") == "netrunner")
        files.forEach { name ->
            val values = root.optJSONObject(name) ?: return@forEach
            val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            values.keys().forEach { k ->
                if (k in skipped) return@forEach
                val item = values.getJSONObject(k)
                when (item.getString("t")) {
                    "b" -> edit.putBoolean(k, item.getBoolean("v"))
                    "i" -> edit.putInt(k, item.getInt("v"))
                    "l" -> edit.putLong(k, item.getLong("v"))
                    "f" -> edit.putFloat(k, item.getDouble("v").toFloat())
                    "s" -> edit.putString(k, item.getString("v"))
                }
            }
            edit.commit()
        }
        root.optJSONObject("kit")?.let { java.io.File(context.filesDir, "kit.json").writeText(it.toString()) }
    }.isSuccess

    /** Restarts the launcher so every screen reads the restored settings (and the boot plays again). */
    fun restart(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        Process.killProcess(Process.myPid())
    }
}
