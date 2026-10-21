package dev.netrunner.launcher.call

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dev.netrunner.launcher.ui.DialerScreen
import dev.netrunner.launcher.ui.NetrunnerTheme

/** Netrunner's phone app: keypad, recent calls and contacts. Also answers tel: links. */
class DialerActivity : ComponentActivity() {

    private val prefill = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefill.value = numberFrom(intent)
        setContent {
            NetrunnerTheme {
                DialerScreen(prefill = prefill.value, onClose = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        prefill.value = numberFrom(intent)
    }

    private fun numberFrom(intent: Intent?): String =
        intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty()
}
