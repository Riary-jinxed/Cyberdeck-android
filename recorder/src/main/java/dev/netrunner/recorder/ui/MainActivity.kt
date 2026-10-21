package dev.netrunner.recorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.DeckTheme
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.TabBar
import dev.netrunner.deck.cyberGrid

class MainActivity : ComponentActivity() {

    private var granted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        setContent {
            DeckTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var version by rememberSaveable { mutableIntStateOf(0) }
                val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                }
                // The notification shows the recording in progress; it is optional.
                val permissions = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray()
                Box(Modifier.fillMaxSize().cyberGrid().systemBarsPadding()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                tab == 2 -> SettingsTab()
                                tab == 1 -> ArchiveTab(version) { version++ }
                                !granted -> Gate { ask.launch(permissions) }
                                else -> RecordTab(onSaved = { version++ })
                            }
                        }
                        TabBar(listOf("REC", "ARCHIVES", "CONFIG"), tab) { tab = it; if (it == 1) version++ }
                    }
                }
            }
        }
    }
}

@Composable
private fun Gate(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("MICRO COUPÉ", color = Cp.Red, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 3.sp)
        Text(
            "Le dictaphone a besoin du micro. Les enregistrements restent sur le téléphone.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.padding(vertical = 14.dp),
        )
        HudButton("AUTORISER", filled = true, modifier = Modifier.fillMaxWidth(), onClick = onGrant)
    }
}
