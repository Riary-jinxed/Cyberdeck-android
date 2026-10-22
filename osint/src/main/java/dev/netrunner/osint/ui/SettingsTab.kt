package dev.netrunner.osint.ui

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
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.UpdateSection
import dev.netrunner.deck.Updater

/** Releases tagged "osint-v…", apart from the other deck apps. */
val OsintUpdater = Updater("osint-v")

@Composable
fun SettingsTab() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("SOURCES", Cp.Yellow)
        Text(
            "Services publics, gratuits et sans compte. Seul le domaine ou l'IP recherché leur est envoyé.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        Text(
            "WHOIS · rdap.org (registres officiels)\n" +
                "DNS · dns.google (DNS over HTTPS)\n" +
                "CERTIFICATS · crt.sh\n" +
                "IP / ASN · ipwho.is et rdap.org\n" +
                "ARCHIVES · web.archive.org\n" +
                "WEB, TLS · le site lui-même (une page, une poignée de main)",
            color = Cp.Dim, fontFamily = MonoFont, fontSize = 13.sp,
        )
        Spacer(Modifier.height(18.dp))
        SectionTitle("PÉRIMÈTRE", Cp.Yellow)
        Text(
            "Recherche passive sur l'infrastructure et les documents. Pas de scan de ports ni de requêtes répétées vers une cible, " +
                "et pas de recherche de personnes (nom, téléphone, e-mail). Les photos et fichiers sont analysés sur le téléphone ; " +
                "seule l'empreinte d'un fichier part dans le lien VirusTotal.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(18.dp))
        FactionSection()
        Spacer(Modifier.height(18.dp))
        UpdateSection(OsintUpdater)
        Spacer(Modifier.height(24.dp))
    }
}
