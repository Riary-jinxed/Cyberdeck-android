package dev.netrunner.music.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.FactionSection
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.UpdateSection
import dev.netrunner.deck.Updater
import dev.netrunner.deck.hudSwitch
import dev.netrunner.music.data.MusicPrefs

/** Releases tagged "music-v…", apart from the other deck apps. */
val MusicUpdater = Updater("music-v")

@Composable
fun SettingsTab(style: MusicPrefs.Style, glow: Boolean, onStyle: (MusicPrefs.Style) -> Unit, onGlow: (Boolean) -> Unit, onRescan: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("VISUALISEUR", Cp.Yellow)
        Row(Modifier.padding(vertical = 6.dp)) {
            MusicPrefs.Style.entries.forEach { s -> Chip(s.label, if (s == style) Cp.Yellow else Cp.Dim) { onStyle(s) } }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Halo néon", color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp)
                Text("Lueur autour des barres. Coupe-la pour économiser un peu de batterie.", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp)
            }
            Switch(checked = glow, onCheckedChange = onGlow, colors = hudSwitch())
        }
        Text(
            "Le visualiseur lit le son décodé par l'appli : pas besoin du micro. Les couleurs suivent la faction choisie.",
            color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        SectionTitle("BIBLIOTHÈQUE", Cp.Yellow)
        Text(
            "Les titres viennent de l'index musical d'Android (dossiers Music, Download…). Un fichier ajouté peut mettre un moment à apparaître.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        Text(
            "RELIRE LA BIBLIOTHÈQUE ►", color = Cp.Cyan, fontFamily = HudFont, fontSize = 15.sp,
            modifier = Modifier.clickable(onClick = onRescan).padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(18.dp))
        FactionSection()
        Spacer(Modifier.height(18.dp))
        UpdateSection(MusicUpdater)
        Spacer(Modifier.height(24.dp))
    }
}
