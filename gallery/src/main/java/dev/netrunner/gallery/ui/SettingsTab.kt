package dev.netrunner.gallery.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.FactionSection
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.UpdateSection
import dev.netrunner.deck.Updater
import dev.netrunner.deck.hudSwitch
import dev.netrunner.gallery.data.MediaRepo

/** Releases tagged "gallery-v…", apart from the other deck apps. */
val GalleryUpdater = Updater("gallery-v")

@Composable
fun SettingsTab(state: GalleryState, onAskAccess: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("AFFICHAGE", Cp.Yellow)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Filtre holo", color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp)
                Text("Teinte de la faction, lignes de balayage et vignettage, sur la grille et à l'ouverture.", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp)
            }
            Switch(checked = state.holo, onCheckedChange = { state.toggleHolo() }, colors = hudSwitch())
        }
        Text(
            "Le filtre ne modifie jamais tes photos. « EXPORT HOLO » enregistre une copie filtrée dans Images/Netrunner.",
            color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp),
        )
        if (MediaRepo.partial(context)) {
            Spacer(Modifier.height(12.dp))
            SectionTitle("ACCÈS PARTIEL", Cp.Red)
            Text("Android ne montre que les photos choisies. Autorise l'accès complet pour tout voir.", color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp)
            HudButton("CHOISIR L'ACCÈS", color = Cp.Cyan, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), onClick = onAskAccess)
        }
        Spacer(Modifier.height(18.dp))
        FactionSection()
        Spacer(Modifier.height(18.dp))
        UpdateSection(GalleryUpdater)
        Spacer(Modifier.height(24.dp))
    }
}
