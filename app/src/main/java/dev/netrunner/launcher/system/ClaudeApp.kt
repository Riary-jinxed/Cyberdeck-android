package dev.netrunner.launcher.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens the official Claude app, signed in with the user's own account. */
object ClaudeApp {
    const val PACKAGE = "com.anthropic.claude"

    fun isInstalled(context: Context) =
        context.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    /** Returns false when the app is missing and the Play Store (or claude.ai) was opened instead. */
    fun open(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(store)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return false
    }
}
