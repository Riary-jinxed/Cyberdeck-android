package dev.netrunner.launcher.system

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides Android's status bar so only the Netrunner HUD shows. A swipe from the top edge
 * still brings it back for a moment. The gesture bar at the bottom stays.
 */
fun hideSystemBars(activity: Activity) {
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.statusBars())
}
