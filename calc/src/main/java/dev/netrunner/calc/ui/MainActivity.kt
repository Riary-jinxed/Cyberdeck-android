package dev.netrunner.calc.ui

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.netrunner.calc.data.CalcPrefs
import dev.netrunner.deck.DeckTheme
import dev.netrunner.deck.TabBar
import dev.netrunner.deck.cyberGrid

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeckTheme {
                var tab by rememberSaveable { mutableIntStateOf(CalcPrefs.tab(this)) }
                Box(Modifier.fillMaxSize().cyberGrid().systemBarsPadding()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (tab) {
                                TAB_SCI -> SciTab()
                                TAB_PROG -> ProgTab()
                                TAB_NET -> NetTab()
                                else -> SettingsTab()
                            }
                        }
                        TabBar(listOf("SCIENTIFIQUE", "PROG", "RÉSEAU", "CONFIG"), tab) {
                            tab = it
                            CalcPrefs.setTab(this@MainActivity, it)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val TAB_SCI = 0
        const val TAB_PROG = 1
        const val TAB_NET = 2
    }
}
