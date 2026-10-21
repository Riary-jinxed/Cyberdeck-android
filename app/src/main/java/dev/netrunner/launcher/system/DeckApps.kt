package dev.netrunner.launcher.system

import android.content.Context
import android.content.Intent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * The other Netrunner apps published from the same repository (clock, calculator, files…).
 * Each one has its own releases, tagged "<id>-v<version>" with an "<id>-<version>.apk" asset, so a
 * new app shows up here as soon as its first release is out, with nothing to change in the launcher.
 */
object DeckApps {
    private const val RELEASES = "https://api.github.com/repos/Riary-jinxed/Cyberdeck-android/releases?per_page=100"
    private val TAG = Regex("^([a-z0-9]+)-v(\\d[\\w.-]*)$")

    /** Ids whose package does not follow "dev.netrunner.<id>". */
    private val PACKAGES = mapOf("horloge" to "dev.netrunner.clock")

    data class App(
        val id: String,
        val title: String,
        val release: Updater.Release,
        val packageName: String,
        val installedVersion: String?,
    ) {
        val installed get() = installedVersion != null
        val updatable get() = installedVersion != null && Updater.isNewer(release.version, installedVersion)
    }

    /** Latest release of every companion app. Throws on network errors. */
    suspend fun list(context: Context): List<App> = withContext(Dispatchers.IO) {
        val conn = (URL(RELEASES).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val json = try {
            JSONArray(conn.inputStream.use { it.readBytes().decodeToString() })
        } finally {
            conn.disconnect()
        }
        val latest = mutableMapOf<String, App>()
        for (i in 0 until json.length()) {
            val r = json.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val match = TAG.matchEntire(r.optString("tag_name")) ?: continue
            val (id, version) = match.destructured
            val assets = r.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
            val known = latest[id]
            if (known != null && !Updater.isNewer(version, known.release.version)) continue
            val pkg = packageFor(context, id)
            latest[id] = App(
                id = id,
                title = r.optString("name").substringBefore(" v").ifBlank { id },
                release = Updater.Release(version, r.optString("body").take(300), apk.getString("browser_download_url"), apk.optLong("size")),
                packageName = pkg,
                installedVersion = installedVersion(context, pkg),
            )
        }
        latest.values.sortedBy { it.title }
    }

    suspend fun download(context: Context, app: App, onProgress: (Float) -> Unit): File {
        val file = Updater.download(context, app.release, app.id, onProgress)
        // Learn the real package from the APK, in case an app breaks the naming convention.
        context.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName?.let {
            prefs(context).edit().putString("deck_app_pkg_${app.id}", it).apply()
        }
        return file
    }

    fun open(context: Context, app: App): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun packageFor(context: Context, id: String) =
        prefs(context).getString("deck_app_pkg_$id", null) ?: PACKAGES[id] ?: "dev.netrunner.$id"

    private fun installedVersion(context: Context, pkg: String): String? =
        runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName ?: "0" }.getOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
}
