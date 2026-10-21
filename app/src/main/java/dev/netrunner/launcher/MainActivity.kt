package dev.netrunner.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import dev.netrunner.launcher.system.hideSystemBars
import dev.netrunner.launcher.ui.HomeScreen
import dev.netrunner.launcher.ui.NetrunnerTheme
import dev.netrunner.launcher.widgets.DeckWidgets

class MainActivity : ComponentActivity() {

    companion object {
        /** Panel to open on arrival (from a quick settings tile): terminal, wifi or settings. */
        const val EXTRA_PANEL = "dev.netrunner.launcher.PANEL"
    }

    private val viewModel: LauncherViewModel by viewModels()

    // Bumped each time the Home button is pressed while we are already in front.
    private val homePresses = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetrunnerTheme {
                HomeScreen(viewModel = viewModel, homePresses = homePresses.intValue)
            }
        }
        openRequestedPanel(intent)
    }

    private fun openRequestedPanel(intent: Intent?): Boolean {
        val panel = when (intent?.getStringExtra(EXTRA_PANEL)) {
            "terminal" -> LauncherViewModel.Panel.Terminal
            "wifi" -> LauncherViewModel.Panel.Wifi
            "settings" -> LauncherViewModel.Panel.Settings
            "messages" -> LauncherViewModel.Panel.Messages
            "kit" -> LauncherViewModel.Panel.Kit
            "aska" -> LauncherViewModel.Panel.Aska
            "nest" -> LauncherViewModel.Panel.Aska.also { dev.netrunner.launcher.aska.AskaPetCalls.openNest = true }
            "stats" -> LauncherViewModel.Panel.Stats
            "hub" -> LauncherViewModel.Panel.Hub
            else -> return false
        }
        intent.removeExtra(EXTRA_PANEL)
        viewModel.openPanel(panel)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!openRequestedPanel(intent)) homePresses.intValue++
    }

    // Hosted widgets only receive updates while the launcher is visible.
    override fun onStart() {
        super.onStart()
        DeckWidgets.startListening(this)
    }

    override fun onStop() {
        super.onStop()
        DeckWidgets.stopListening(this)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars(this)
    }

    // HyperOS sometimes brings the status bar back when focus returns (after the shade, a dialog…).
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars(this)
    }
}
