package dev.netrunner.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

enum class AppCategory(val label: String) {
    Comms("COMMS"),
    Media("MÉDIA"),
    Games("JEUX"),
    Nav("NAV"),
    Tools("OUTILS"),
    Other("AUTRES"),
}

data class AppEntry(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap,
    val category: AppCategory = AppCategory.Other,
) {
    val key: String get() = component.flattenToString()
    val packageName: String get() = component.packageName
}

/** A group of quickhacks shown as one tile; it sits in the favorites list as "dir:<id>". */
data class Folder(val id: String, val name: String, val keys: List<String>) {
    val slot: String get() = "$PREFIX$id"

    companion object {
        const val PREFIX = "dir:"
    }
}

/** Reads installed apps through LauncherApps and stores the pinned ones. */
class AppRepository(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val prefs = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)
    private val usagePrefs = context.getSharedPreferences("netrunner_usage", Context.MODE_PRIVATE)

    fun loadApps(): List<AppEntry> {
        val user = Process.myUserHandle()
        return launcherApps.getActivityList(null, user)
            .filter { it.componentName.packageName != context.packageName }
            .map { info ->
                AppEntry(
                    label = info.label.toString(),
                    component = info.componentName,
                    user = user,
                    icon = info.getIcon(0).toBitmap(ICON_PX, ICON_PX).asImageBitmap(),
                    category = categoryOf(info.componentName.packageName, info.applicationInfo),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun favorites(): List<String> =
        prefs.getString(KEY_FAVORITES, "").orEmpty().split('|').filter { it.isNotBlank() }

    fun saveFavorites(keys: List<String>) {
        prefs.edit().putString(KEY_FAVORITES, keys.joinToString("|")).apply()
    }

    /** Quickhack folders: one line per folder, "id \t name \t key|key|…". */
    fun folders(): List<Folder> =
        prefs.getString(KEY_FOLDERS, "").orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) null
            else Folder(parts[0], parts[1], parts[2].split('|').filter { it.isNotBlank() })
        }

    fun saveFolders(folders: List<Folder>) {
        prefs.edit().putString(
            KEY_FOLDERS,
            folders.joinToString("\n") { f -> "${f.id}\t${f.name.replace('\t', ' ').replace('\n', ' ')}\t${f.keys.joinToString("|")}" },
        ).apply()
    }

    /** How many times each app was opened from Netrunner, for the "frequent" sort. */
    fun usage(): Map<String, Int> = usagePrefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()

    fun launch(app: AppEntry) {
        usagePrefs.edit().putInt(app.key, usagePrefs.getInt(app.key, 0) + 1).apply()
        runCatching { launcherApps.startMainActivity(app.component, app.user, null, null) }
            .onFailure { Log.w(TAG, "Cannot launch ${app.key}", it) }
    }

    fun openAppInfo(app: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
            .onFailure { Log.w(TAG, "Cannot open info for ${app.key}", it) }
    }

    fun registerCallback(callback: LauncherApps.Callback) = launcherApps.registerCallback(callback)

    fun unregisterCallback(callback: LauncherApps.Callback) = launcherApps.unregisterCallback(callback)

    /** Android's own app category when the developer set one, otherwise a guess from the package name. */
    private fun categoryOf(pkg: String, info: ApplicationInfo): AppCategory {
        val p = pkg.lowercase()
        fun has(vararg words: String) = words.any { p.contains(it) }
        return when {
            p == "com.google.android.gm" || has("whatsapp", "telegram", "messag", "mms", "sms", "dialer", "contacts", "signal", "discord",
                "facebook.orca", "snapchat", "instagram", "skype", "teams", "mail", "outlook", "thunderbird", "slack") -> AppCategory.Comms
            info.category == ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.Comms
            info.category == ApplicationInfo.CATEGORY_GAME -> AppCategory.Games
            info.category == ApplicationInfo.CATEGORY_MAPS || has("maps", "waze", "uber", "citymapper", "navig") -> AppCategory.Nav
            info.category in setOf(
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO,
                ApplicationInfo.CATEGORY_IMAGE, ApplicationInfo.CATEGORY_NEWS,
            ) || has("music", "spotify", "deezer", "youtube", "netflix", "twitch", "camera", "gallery", "photo", "video", "podcast") -> AppCategory.Media
            info.category in setOf(ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_ACCESSIBILITY) ||
                has("settings", "calculator", "clock", "calendar", "files", "filemanager", "notes", "drive", "docs",
                    "termux", "securitycenter", "scanner", "compass", "weather", "vpn", "authenticator") -> AppCategory.Tools
            else -> AppCategory.Other
        }
    }

    private companion object {
        const val TAG = "Netrunner"
        const val KEY_FAVORITES = "favorites"
        const val KEY_FOLDERS = "folders"
        const val ICON_PX = 128
    }
}
