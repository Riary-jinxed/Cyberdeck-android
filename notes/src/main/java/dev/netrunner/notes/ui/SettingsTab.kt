package dev.netrunner.notes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.netrunner.notes.data.Note
import dev.netrunner.notes.data.NoteStore
import dev.netrunner.notes.data.PdfExport

/** Releases tagged "notes-v…", apart from the other deck apps. */
val NotesUpdater = Updater("notes-v")

@Composable
fun SettingsTab() {
    val context = LocalContext.current
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            val ok = PdfExport.write(context, uri, NoteStore.all(context).sortedWith(Note.order))
            Toast.makeText(context, if (ok) "PDF enregistré" else "Échec de l'export PDF", Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("EXPORT", Cp.Yellow)
        Text(
            "Toutes les notes dans un seul PDF (épinglées d'abord). Une note seule s'exporte depuis son bouton PDF.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        HudButton("EXPORTER TOUT EN PDF", color = Cp.Cyan) { pdf.launch(PdfExport.fileName(emptyList())) }
        Spacer(Modifier.height(18.dp))
        SectionTitle("ASTUCES", Cp.Yellow)
        Text(
            "• Appui long sur une note pour l'épingler en haut.\n" +
                "• Dans une liste, « Suivant » au clavier ajoute un élément ; les cases cochées descendent.\n" +
                "• LISTE / TEXTE change le type de la note sans perdre son contenu.\n" +
                "• Partage du texte depuis n'importe quelle appli vers Notes pour en faire une note.\n" +
                "• As//ka peut écrire des notes : elles portent la mention AS//KA.\n" +
                "• LIER AU KIT ajoute un lien kit:CODE ; le bouton ◆ KIT ouvre la fiche dans le kit de terrain du launcher.\n" +
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
