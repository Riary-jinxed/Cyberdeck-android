package dev.netrunner.files.ui

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

/** Releases tagged "files-v…", apart from the other deck apps. */
val FilesUpdater = Updater("files-v")

@Composable
fun SettingsTab() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("ASTUCES", Cp.Yellow)
        Text(
            "• Touche un dossier pour y entrer, ou sa flèche ▸ pour le déplier sur place.\n" +
                "• Appui long sur un élément pour le sélectionner, puis touche les autres.\n" +
                "• COPIER ou COUPER, va dans le dossier voulu, puis COLLER ICI.\n" +
                "• Retour remonte d'un dossier.\n" +
                "• La recherche part du dossier ouvert dans l'explorateur.\n" +
                "• Les images, les textes et les zip s'ouvrent en aperçu ; « Ouvrir avec » pour une autre appli.\n" +
                "• COMPRESSER crée un zip de la sélection, EXTRAIRE le décompresse dans un nouveau dossier.\n" +
                "• SUPPR. envoie à la CORBEILLE (bouton dans l'explorateur), vidée automatiquement après 30 jours ; DÉFINITIF efface tout de suite.\n" +
                "• RÉSEAU : ajoute ton NAS (SMB). Touche un fichier pour l'ouvrir, appui long puis TÉLÉCHARGER le copie dans le dossier ouvert de l'explorateur. " +
                "Pour envoyer, COPIER dans l'explorateur puis ENVOYER ICI dans RÉSEAU.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(18.dp))
        FactionSection()
        Spacer(Modifier.height(18.dp))
        UpdateSection(FilesUpdater)
        Spacer(Modifier.height(24.dp))
    }
}
