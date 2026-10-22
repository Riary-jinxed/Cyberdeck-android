package dev.netrunner.scanner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.DeckTheme
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.TabBar
import dev.netrunner.deck.cyberGrid
import dev.netrunner.scanner.data.Codes
import dev.netrunner.scanner.data.History

class MainActivity : ComponentActivity() {

    private var granted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setContent {
            DeckTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var result by remember { mutableStateOf<History.Scan?>(null) }
                var history by remember { mutableStateOf(History.all(this)) }
                val haptics = LocalHapticFeedback.current
                val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
                BackHandler(enabled = result != null) { result = null }

                Box(Modifier.fillMaxSize().cyberGrid().systemBarsPadding()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                tab == 1 -> HistoryTab(history) { history = it }
                                tab == 2 -> SettingsTab()
                                !granted -> Gate { ask.launch(Manifest.permission.CAMERA) }
                                else -> {
                                    ScanTab(active = result == null) { raw, format ->
                                        if (result != null) return@ScanTab
                                        val scan = History.Scan(raw, format, System.currentTimeMillis())
                                        result = scan
                                        history = History.add(this@MainActivity, scan)
                                        if (History.vibrate(this@MainActivity)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val content = Codes.parse(raw)
                                        if (History.kitMode(this@MainActivity)) {
                                            addToKit(this@MainActivity, raw, format)
                                        } else if (content is Codes.Content.Url && History.autoOpen(this@MainActivity)) {
                                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(content.url))) }
                                        }
                                    }
                                    result?.let { scan ->
                                        ResultCard(
                                            scan, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp).padding(bottom = 24.dp),
                                            onClose = { result = null },
                                        )
                                    }
                                }
                            }
                        }
                        TabBar(listOf("SCAN", "HISTORIQUE", "CONFIG"), tab) { tab = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun Gate(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("OPTIQUE HORS LIGNE", color = Cp.Red, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 3.sp)
        Text(
            "Le scanner a besoin de la caméra. Les images ne sont ni enregistrées ni envoyées.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.padding(vertical = 14.dp),
        )
        HudButton("AUTORISER", filled = true, modifier = Modifier.fillMaxWidth(), onClick = onGrant)
    }
}
