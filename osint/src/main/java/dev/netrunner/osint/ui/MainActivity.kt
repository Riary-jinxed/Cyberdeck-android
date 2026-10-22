package dev.netrunner.osint.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.netrunner.deck.DeckTheme
import dev.netrunner.deck.TabBar
import dev.netrunner.deck.cyberGrid
import dev.netrunner.osint.data.Target

class MainActivity : ComponentActivity() {

    private lateinit var state: OsintState
    private var tab by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        state = OsintState(this)
        if (savedInstanceState == null) read(intent)
        setContent {
            DeckTheme {
                Box(Modifier.fillMaxSize().cyberGrid().systemBarsPadding()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (tab) {
                                0 -> ReconTab(state)
                                1 -> FileTab(state)
                                2 -> CaseTab(state)
                                else -> SettingsTab()
                            }
                        }
                        TabBar(listOf("CIBLE", "FICHIER", "CARNET", "CONFIG"), tab) { tab = it }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    /** A domain, an IP or a link shared from another app becomes the target. */
    private fun read(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        // A shared text often wraps the link in a sentence: take the first word that parses.
        val word = text.split(Regex("\\s+")).firstOrNull { Target.parse(it) != null } ?: return
        state.query = word
        tab = 0
    }
}
