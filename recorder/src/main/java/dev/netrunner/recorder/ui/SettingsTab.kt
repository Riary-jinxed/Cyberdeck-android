package dev.netrunner.recorder.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.FactionSection
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.UpdateSection
import dev.netrunner.deck.Updater
import dev.netrunner.deck.hudSwitch
import dev.netrunner.recorder.data.RecorderPrefs
import dev.netrunner.recorder.system.Transcriber

/** Releases tagged "recorder-v…", apart from the other deck apps. */
val RecorderUpdater = Updater("recorder-v")

@Composable
fun SettingsTab() {
    val context = LocalContext.current
    var high by remember { mutableStateOf(RecorderPrefs.highQuality(context)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("QUALITÉ", Cp.Yellow)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Haute qualité", color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp)
                Text(
                    if (high) "48 kHz, 192 kbit/s : environ 1,4 Mo par minute." else "44,1 kHz, 96 kbit/s : environ 0,7 Mo par minute, idéal pour la voix.",
                    color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp,
                )
            }
            Switch(checked = high, onCheckedChange = { high = it; RecorderPrefs.setHighQuality(context, it) }, colors = hudSwitch())
        }
        Spacer(Modifier.height(12.dp))
        TranscriptionSection()
        Spacer(Modifier.height(12.dp))
        SectionTitle("STOCKAGE", Cp.Yellow)
        Text(
            "Les enregistrements sont gardés dans le dossier de l'appli (Android/data/dev.netrunner.recorder). " +
                "Ils restent après une mise à jour, mais sont effacés si l'appli est désinstallée : partage ceux à garder.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(18.dp))
        FactionSection()
        Spacer(Modifier.height(18.dp))
        UpdateSection(RecorderUpdater)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TranscriptionSection() {
    val context = LocalContext.current
    val model by Transcriber.model.collectAsStateWithLifecycle()
    var autoNotes by remember { mutableStateOf(RecorderPrefs.autoNotes(context)) }
    LaunchedEffect(Unit) { Transcriber.refresh(context) }

    SectionTitle("TRANSCRIPTION", Cp.Yellow)
    Text(
        "La voix est convertie en texte sur le téléphone (Vosk), sans connexion ni envoi. " +
            "Il faut d'abord télécharger le modèle français, environ ${Transcriber.MODEL_SIZE_MB} Mo.",
        color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
    )
    when (val m = model) {
        is Transcriber.ModelState.Downloading -> {
            Text("TÉLÉCHARGEMENT… ${(m.progress * 100).toInt()} %", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            LinearProgressIndicator(
                progress = { m.progress }, color = Cp.Yellow, trackColor = Cp.Dim.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
            HudButton("ANNULER", color = Cp.Dim) { Transcriber.deleteModel(context) }
        }
        Transcriber.ModelState.Ready -> {
            Text("Modèle français installé.", color = Cp.Cyan, fontFamily = HudFont, fontSize = 14.sp)
            HudButton("SUPPRIMER LE MODÈLE", color = Cp.Red, modifier = Modifier.padding(top = 6.dp)) { Transcriber.deleteModel(context) }
        }
        else -> {
            if (m is Transcriber.ModelState.Failed) Text("Échec : ${m.message}", color = Cp.Red, fontFamily = HudFont, fontSize = 13.sp)
            HudButton("TÉLÉCHARGER LE MODÈLE", filled = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Transcriber.downloadModel(context) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Ranger dans Notes", color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp)
            Text(
                "Chaque transcription terminée devient aussi une note dans l'appli Notes du deck.",
                color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp,
            )
        }
        Switch(checked = autoNotes, onCheckedChange = { autoNotes = it; RecorderPrefs.setAutoNotes(context, it) }, colors = hudSwitch())
    }
}
