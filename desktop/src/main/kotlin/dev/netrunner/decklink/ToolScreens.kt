package dev.netrunner.decklink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop

// ---------------------------------------------------------------------------------- alerts

@Composable
fun AlertsScreen(alerts: List<Alerts.Alert>) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T(
            "Disque à moins de ${Alerts.DISK_FREE_GB} Go ou ${Alerts.DISK_FREE_PERCENT} % libres, PC sans rapport depuis ${Alerts.QUIET_DAYS} jours, " +
                "mises à jour Windows en attente ou absentes depuis ${Alerts.UPDATE_DAYS} jours, garantie finissant sous ${Alerts.WARRANTY_DAYS} jours, " +
                "consommable sous son seuil. Deck Link prévient aussi dans la zone de notification.",
            Cp.Dim, 13.sp, MonoFont, FontWeight.Normal,
        )
        if (alerts.isEmpty()) {
            T("> rien à signaler", Cp.Cyan, 15.sp, MonoFont, FontWeight.Normal)
            return@Column
        }
        val kinds = mapOf(Alerts.Kind.Fleet to "PARC", Alerts.Kind.Inventory to "INVENTAIRE", Alerts.Kind.Stock to "CONSOMMABLES")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(alerts, key = { it.key }) { a ->
                val color = if (a.level == Alerts.Level.Urgent) Cp.Red else Cp.Yellow
                Row(
                    Modifier.fillMaxWidth().hudPanel(color.copy(alpha = 0.5f), cut = 4.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(kinds[a.kind].orEmpty(), Cp.Dim, 11.sp, weight = FontWeight.Bold, modifier = Modifier.width(110.dp))
                    T(a.subject, color, 14.sp, modifier = Modifier.width(220.dp), maxLines = 1)
                    T(a.text, Cp.Text, 14.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- exports

@Composable
fun ExportsScreen(state: LinkState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Section("EXCEL")
        T(
            "Un classeur avec une feuille par table : inventaire, consommables, clients, contrats, devis, câbles, interventions, PC du parc et leurs logiciels.",
            Cp.Text, 14.sp, MonoFont, FontWeight.Normal,
        )
        Button("EXPORTER EN EXCEL ►", Cp.Yellow, filled = true) { state.exportExcel() }
        Spacer(Modifier.height(6.dp))
        Section("PDF")
        T(
            "Rapport d'intervention : CARNET › choisis l'intervention › RAPPORT PDF.\n" +
                "Étiquettes QR : INVENTAIRE › ÉTIQUETTES QR (pour les équipements affichés).",
            Cp.Text, 14.sp, MonoFont, FontWeight.Normal,
        )
        Spacer(Modifier.height(6.dp))
        Button("OUVRIR LE DOSSIER DES EXPORTS", Cp.Cyan) { runCatching { Desktop.getDesktop().open(Paths.exports.apply { mkdirs() }) } }
        T("${Paths.exports}", Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
    }
}

// ---------------------------------------------------------------------------------- backups

@Composable
fun BackupsScreen(state: LinkState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T(
            "Une copie datée de tout le kit (inventaire, carnet et photos, clients, consommables, câbles, adressage, couverture) est prise après chaque synchro " +
                "qui apporte du nouveau. Les ${Backups.KEEP} plus récentes sont gardées.",
            Cp.Text, 14.sp, MonoFont, FontWeight.Normal,
        )
        T(
            "Pour revenir en arrière : sur le téléphone, KIT › OUTILS › PC › RESTAURER UNE SAUVEGARDE DU PC. " +
                "Le fichier .zip se restaure aussi à la main (KIT › OUTILS › SAUVEGARDE › RESTAURER UN KIT).",
            Cp.Dim, 13.sp, MonoFont, FontWeight.Normal,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("SAUVEGARDER MAINTENANT ►", Cp.Yellow, filled = true) { state.backupNow() }
            Button("OUVRIR LE DOSSIER", Cp.Cyan) { runCatching { Desktop.getDesktop().open(Paths.backups.apply { mkdirs() }) } }
        }
        val list = state.backupList
        if (list.isEmpty()) {
            T("> aucune sauvegarde pour l'instant", Cp.Cyan, 15.sp, MonoFont, FontWeight.Normal)
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(list, key = { it.file.name }) { b ->
                Row(
                    Modifier.fillMaxWidth().hudPanel(Cp.Cyan.copy(alpha = 0.3f), cut = 4.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(LinkState.time(b.created), Cp.Text, 15.sp, modifier = Modifier.width(170.dp))
                    T("${b.files} fichiers · ${size(b.bytes)}", Cp.Cyan, 13.sp, MonoFont, FontWeight.Normal, Modifier.weight(1f))
                    T(b.file.name, Cp.Dim, 12.sp, MonoFont, FontWeight.Normal)
                }
            }
        }
    }
}

private fun size(bytes: Long) = when {
    bytes >= 1L shl 30 -> "%.1f Go".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f Mo".format(bytes / (1L shl 20).toDouble())
    else -> "${(bytes + 1023) / 1024} Ko"
}
