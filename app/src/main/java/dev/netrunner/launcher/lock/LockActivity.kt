package dev.netrunner.launcher.lock

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import dev.netrunner.launcher.system.hideSystemBars
import dev.netrunner.launcher.ui.LockScreen
import dev.netrunner.launcher.ui.NetrunnerTheme

/** Shown over the system lock screen; swiping up hands over to the system unlock. */
class LockActivity : ComponentActivity() {

    // The system unlocked (fingerprint, face, PIN): get out of the way.
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        enableEdgeToEdge()
        // Back does nothing here, like on a real lock screen.
        onBackPressedDispatcher.addCallback(this) { }
        ContextCompat.registerReceiver(
            this, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED,
        )
        setContent {
            NetrunnerTheme {
                LockScreen(onUnlock = ::unlock)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars(this)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlocked) }
        super.onDestroy()
    }

    private fun unlock() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
            })
        } else {
            finish()
        }
    }
}
