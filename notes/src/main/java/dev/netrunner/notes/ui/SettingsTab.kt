package dev.netrunner.notes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.FactionSection
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.UpdateSection
import dev.netrunner.deck.Updater

/** Releases tagged "notes-v…", apart from the other deck apps. */
val NotesUpdater = Updater("notes-v")

@Composable
fun SettingsTab() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("ASTUCES", Cp.Yellow)
        Text(
            "• Appui long sur une note pour l'épingler en haut.\n" +
                "• Dans une liste, « Suivant » au clavier ajoute un élément ; les cases cochées descendent.\n" +
                "• LISTE / TEXTE change le type de la note sans perdre son contenu.\n" +
                "• Partage du texte depuis n'importe quelle appli vers Notes pour en faire une note.\n" +
                "• As//ka peut écrire des notes : elles portent la mention AS//KA.\n" +
                "• Les notes restent sur le téléphone.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(18.dp))
        FactionSection()
        Spacer(Modifier.height(18.dp))
        UpdateSection(NotesUpdater)
        Spacer(Modifier.height(24.dp))
    }
}
