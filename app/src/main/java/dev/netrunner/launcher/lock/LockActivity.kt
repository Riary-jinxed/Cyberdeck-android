package dev.netrunner.launcher.lock

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    companion object {
        /** Long enough to see the cyberdeck screen, short enough for face unlock to feel instant. */
        private const val HANDOFF_DELAY_MS = 700L
    }

    // The system unlocked (fingerprint, face, PIN): get out of the way.
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    // Face unlock only runs on the system lock, not under this screen: hand over once awake.
    private val handler = Handler(Looper.getMainLooper())
    private val handoff = Runnable { unlock() }
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (CyberLock.handoff(context)) handler.postDelayed(handoff, HANDOFF_DELAY_MS)
        }
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
        ContextCompat.registerReceiver(
            this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED,
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
        handler.removeCallbacks(handoff)
        runCatching { unregisterReceiver(unlocked) }
        runCatching { unregisterReceiver(screenOn) }
        super.onDestroy()
    }

    private fun unlock() {
        handler.removeCallbacks(handoff)
        if (isFinishing) return
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
