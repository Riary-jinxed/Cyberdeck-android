package dev.netrunner.launcher.call

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
    }
}
