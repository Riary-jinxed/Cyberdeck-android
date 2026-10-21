package dev.netrunner.files.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.netrunner.deck.DeckTheme
import dev.netrunner.deck.TabBar
import dev.netrunner.deck.cyberGrid
import dev.netrunner.files.system.Storage

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeckTheme {
                val explorer = remember { Explorer(this) }
                var tab by rememberSaveable { mutableIntStateOf(0) }
                // Re-checked on each return, e.g. from the "All files access" page.
                val resumed by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
                val access = remember(resumed) { Storage.hasAccess() }
                Box(Modifier.fillMaxSize().cyberGrid().systemBarsPadding()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                tab == 2 -> SettingsTab()
                                !access -> AccessGate { Storage.requestAccess(this@MainActivity) }
                                tab == 0 -> ExplorerTab(explorer)
                                else -> SearchTab(explorer) { tab = 0 }
                            }
                        }
                        TabBar(listOf("EXPLORER", "RECHERCHE", "CONFIG"), tab) { tab = it }
                    }
                }
            }
        }
    }
}
