package dev.netrunner.scanner.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.FactionSection
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.UpdateSection
import dev.netrunner.deck.Updater
import dev.netrunner.deck.hudSwitch
import dev.netrunner.scanner.data.History

/** Releases tagged "scanner-v…", apart from the other deck apps. */
val ScannerUpdater = Updater("scanner-v")

@Composable
fun SettingsTab() {
    val context = LocalContext.current
    var vibrate by remember { mutableStateOf(History.vibrate(context)) }
    var autoOpen by remember { mutableStateOf(History.autoOpen(context)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("SCAN", Cp.Yellow)
        Setting("Vibrer à la détection", "Un petit retour quand un code est lu.", vibrate) { vibrate = it; History.setVibrate(context, it) }
        Setting("Ouvrir les liens directement", "Sans passer par la fiche du code. Prudence avec les QR codes inconnus.", autoOpen) {
            autoOpen = it; History.setAutoOpen(context, it)
        }
        Text(
            "La lecture se fait sur le téléphone, sans connexion. Seuls « Rechercher » et l'ouverture d'un lien vont sur Internet.",
            color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(18.dp))
        FactionSection()
        Spacer(Modifier.height(18.dp))
        UpdateSection(ScannerUpdater)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Setting(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp)
            Text(detail, color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp)
        }
        Switch(checked = on, onCheckedChange = onChange, colors = hudSwitch())
    }
}
