package dev.netrunner.music.ui

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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import dev.netrunner.music.data.Library
import dev.netrunner.music.data.MusicPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private var granted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        setContent {
            DeckTheme {
                val player = rememberPlayerState()
                var tab by rememberSaveable { mutableIntStateOf(1) }
                var scan by remember { mutableIntStateOf(0) }
                var style by remember { mutableStateOf(MusicPrefs.style(this)) }
                var glow by remember { mutableStateOf(MusicPrefs.glow(this)) }
                val tracks by produceState<List<Library.Track>?>(null, granted, scan) {
                    value = if (granted) withContext(Dispatchers.IO) { runCatching { Library.load(this@MainActivity) }.getOrDefault(emptyList()) } else null
                }
                val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                }
                // The media notification is optional; asked together with the music.
                val permissions = buildList {
                    add(permission)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray()

                Box(Modifier.fillMaxSize().cyberGrid().systemBarsPadding()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                tab == 2 -> SettingsTab(
                                    style, glow,
                                    onStyle = { style = it; MusicPrefs.setStyle(this@MainActivity, it) },
                                    onGlow = { glow = it; MusicPrefs.setGlow(this@MainActivity, it) },
                                    onRescan = { scan++ },
                                )
                                tab == 0 -> NowPlayingTab(player, style, glow) { tab = 1 }
                                !granted -> Gate { ask.launch(permissions) }
                                else -> LibraryTab(tracks, player) { tab = 0 }
                            }
                        }
                        TabBar(listOf("LECTURE", "BIBLIOTHÈQUE", "CONFIG"), tab) { tab = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun Gate(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("BIBLIOTHÈQUE VERROUILLÉE", color = Cp.Red, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 2.sp)
        Text(
            "Le lecteur a besoin d'accéder aux fichiers audio du téléphone. Rien n'est envoyé ailleurs.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.padding(vertical = 14.dp),
        )
        HudButton("AUTORISER", filled = true, modifier = Modifier.fillMaxWidth(), onClick = onGrant)
    }
}
