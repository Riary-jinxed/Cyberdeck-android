package dev.netrunner.weather.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.netrunner.deck.DeckTheme
import dev.netrunner.deck.TabBar
import dev.netrunner.deck.cyberGrid
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeckTheme {
                val state = remember { WeatherState(this) }
                val scope = rememberCoroutineScope()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { scope.launch { state.refresh() } }
                LaunchedEffect(Unit) {
                    // First run on the phone's position: ask for it once, otherwise refresh straight away.
                    if (state.selected == null && !state.hasLocation()) ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION) else state.refresh()
                }
                Box(Modifier.fillMaxSize().cyberGrid().systemBarsPadding()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (tab) {
                                0 -> WeatherTab(state) { ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
                                1 -> SatelliteTab(state)
                                2 -> AgriTab(state)
                                3 -> PlacesTab(state) { tab = 0 }
                                else -> SettingsTab()
                            }
                        }
                        TabBar(listOf("MÉTÉO", "SAT", "AGRI", "LIEUX", "CONFIG"), tab) { tab = it }
                    }
                }
            }
        }
    }
}
