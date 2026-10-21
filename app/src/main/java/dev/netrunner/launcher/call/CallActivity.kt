package dev.netrunner.launcher.call

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.netrunner.launcher.system.hideSystemBars
import dev.netrunner.launcher.ui.HoloCallScreen
import dev.netrunner.launcher.ui.NetrunnerTheme

/** The holocall screen: shown over the lock screen for incoming and ongoing calls. */
class CallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        setContent {
            NetrunnerTheme {
                HoloCallScreen(onFinished = { finishAndRemoveTask() })
            }
        }
        hideSystemBars(this)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars(this)
        CallNotifications.screenVisible = true
        // Swap the heads-up card for a silent one while our screen is showing.
        CallNotifications.update(this, CallManager.calls.value)
    }

    override fun onPause() {
        CallNotifications.screenVisible = false
        CallNotifications.update(this, CallManager.calls.value)
        super.onPause()
    }
}
