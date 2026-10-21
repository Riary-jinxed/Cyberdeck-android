package dev.netrunner.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppEntry(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap,
) {
    val key: String get() = component.flattenToString()
    val packageName: String get() = component.packageName
}

/** Reads installed apps through LauncherApps and stores the pinned ones. */
class AppRepository(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val prefs = context.getSharedPreferences("netrunner", Context.MODE_PRIVATE)

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
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun favorites(): List<String> =
        prefs.getString(KEY_FAVORITES, "").orEmpty().split('|').filter { it.isNotBlank() }

    fun saveFavorites(keys: List<String>) {
        prefs.edit().putString(KEY_FAVORITES, keys.joinToString("|")).apply()
    }

    fun launch(app: AppEntry) {
        runCatching { launcherApps.startMainActivity(app.component, app.user, null, null) }
            .onFailure { Log.w(TAG, "Cannot launch ${app.key}", it) }
    }

    fun openAppInfo(app: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
            .onFailure { Log.w(TAG, "Cannot open info for ${app.key}", it) }
    }

    fun registerCallback(callback: LauncherApps.Callback) = launcherApps.registerCallback(callback)

    fun unregisterCallback(callback: LauncherApps.Callback) = launcherApps.unregisterCallback(callback)

    private companion object {
        const val TAG = "Netrunner"
        const val KEY_FAVORITES = "favorites"
        const val ICON_PX = 128
    }
}
