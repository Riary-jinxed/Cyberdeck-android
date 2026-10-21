package dev.netrunner.gallery.ui

import android.content.Intent
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import dev.netrunner.gallery.data.MediaRepo

class MainActivity : ComponentActivity() {

    private lateinit var state: GalleryState
    private var granted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        state = GalleryState(this)
        granted = MediaRepo.granted(this)
        read(intent)
        setContent {
            DeckTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    granted = MediaRepo.granted(this)
                    state.refresh()
                }
                val askAccess = { ask.launch(MediaRepo.permissions()) }
                DisposableEffect(granted) {
                    if (granted) state.start()
                    onDispose { if (granted) state.stop() }
                }
                LaunchedEffect(granted, state.version) { if (granted) state.reload() }
                Box(Modifier.fillMaxSize().cyberGrid().systemBarsPadding()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                tab == 2 -> SettingsTab(state, askAccess)
                                !granted -> Gate(askAccess)
                                tab == 0 -> PhotosTab(state)
                                else -> AlbumsTab(state) { state.album = it; tab = 0 }
                            }
                        }
                        TabBar(listOf("PHOTOS", "ALBUMS", "CONFIG"), tab) { tab = it }
                    }
                }
                state.viewer?.let { (items, start) -> Viewer(state, items, start) { state.viewer = null } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    /** A photo opened from another app: shown alone in the viewer. */
    private fun read(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val media = MediaRepo.Media(
            id = -1, uri = uri, name = uri.lastPathSegment ?: "image", bucket = "", taken = System.currentTimeMillis(),
            mime = intent.type ?: "image/*", width = 0, height = 0, size = 0, duration = 0,
        )
        state.viewer = listOf(media) to 0
    }
}

@Composable
private fun Gate(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("ARCHIVES VERROUILLÉES", color = Cp.Red, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 2.sp)
        Text(
            "La galerie a besoin de lire tes photos et vidéos. Elles restent sur le téléphone.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.padding(vertical = 14.dp),
        )
        HudButton("AUTORISER", filled = true, modifier = Modifier.fillMaxWidth(), onClick = onGrant)
    }
}
