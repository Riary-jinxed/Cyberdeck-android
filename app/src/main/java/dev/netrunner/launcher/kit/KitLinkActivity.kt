package dev.netrunner.launcher.kit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.netrunner.launcher.MainActivity

/**
 * Entry point for the deck's other apps (Scanner, Notes), guarded by the signature permission
 * dev.netrunner.permission.KIT: KIT_ADD opens a sheet for a scanned code, KIT_OPEN the sheet of
 * a code linked from a note. It only hands the code to the KIT panel and closes.
 */
class KitLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent?.getStringExtra(EXTRA_CODE)?.trim()?.take(MAX_CODE)
        if (!code.isNullOrEmpty() && intent?.action in setOf(ACTION_ADD, ACTION_OPEN)) {
            KitStore.request = KitStore.Request(code, intent.getStringExtra(EXTRA_FORMAT)?.trim()?.take(40))
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PANEL, "kit")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    companion object {
        const val ACTION_ADD = "dev.netrunner.action.KIT_ADD"
        const val ACTION_OPEN = "dev.netrunner.action.KIT_OPEN"
        const val EXTRA_CODE = "code"
        const val EXTRA_FORMAT = "format"
        private const val MAX_CODE = 200
    }
}
