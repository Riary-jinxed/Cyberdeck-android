package dev.netrunner.osint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.hudFieldColors
import dev.netrunner.osint.data.Module
import dev.netrunner.osint.data.Target
import kotlinx.coroutines.launch

/** One field for a domain, an IP or a link; the modules that apply to it; the results below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReconTab(state: OsintState) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val parsed = remember(state.query) { Target.parse(state.query) }
    val modules = Module.entries.filter { m -> parsed?.let { m.applies(it) } ?: true }

    fun run(list: List<Module>) {
        focus.clearFocus()
        scope.launch { state.lookup(list) }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("runner@deck", color = Cp.Yellow, fontFamily = MonoFont, fontSize = 15.sp)
                Text(":~/osint$ ", color = Cp.Dim, fontFamily = MonoFont, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (state.results.isNotEmpty()) Chip("EFFACER", Cp.Dim) { state.clearResults() }
            }
        }
        item {
            TextField(
                state.query, { state.query = it }, singleLine = true, colors = hudFieldColors(),
                placeholder = { Text("domaine, IP ou lien…", fontFamily = MonoFont, color = Cp.Dim) },
                textStyle = TextStyle(fontFamily = MonoFont, fontSize = 17.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { if (parsed != null) run(modules) }),
                modifier = Modifier.fillMaxWidth(),
            )
            val hint = when {
                state.query.isBlank() -> "ex. exemple.fr · 8.8.8.8 · https://site.fr/page"
                parsed == null -> "Ni un domaine, ni une IP, ni un lien http(s)."
                parsed.isIp -> "Adresse IP"
                parsed.kind == Target.Kind.Url -> "Lien · hôte ${parsed.host}"
                else -> "Domaine ${parsed.host}"
            }
            Text(hint, color = if (parsed == null && state.query.isNotBlank()) Cp.Red else Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                modules.forEach { m ->
                    val busy = m in state.running
                    Chip(if (busy) "${m.label}…" else m.label, if (busy) Cp.Yellow else Cp.Cyan) { if (parsed != null) run(listOf(m)) }
                }
            }
            HudButton(
                if (state.running.isNotEmpty()) "ANALYSE EN COURS…" else "TOUT LANCER", Modifier.fillMaxWidth().padding(top = 10.dp), filled = parsed != null,
            ) { if (parsed != null) run(modules) }
        }
        if (state.results.isEmpty() && state.running.isEmpty()) {
            item {
                SectionTitle("MODULES", Cp.Yellow, Modifier.padding(top = 8.dp))
                Text(
                    "WHOIS : registrar, dates, serveurs DNS (RDAP)\n" +
                        "DNS : A, AAAA, MX, NS, TXT, CAA, SPF et DMARC\n" +
                        "CERTIFICATS : sous-domaines vus dans Certificate Transparency\n" +
                        "IP / ASN : opérateur, ASN, plage, localisation approximative\n" +
                        "WEB : redirections, serveur, en-têtes de sécurité\n" +
                        "TLS : protocole, émetteur, validité, noms couverts\n" +
                        "ARCHIVES : captures de la Wayback Machine\n" +
                        "PIVOTS : liens vers VirusTotal, urlscan, Shodan…",
                    color = Cp.Text, fontFamily = MonoFont, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                )
                Message("Tout est passif : on interroge des registres et archives publics, jamais de scan de la cible.")
            }
        }
        items(state.results, key = { it.module }) { r ->
            ReportCard(r, saved = state.isSaved(r), onSave = { state.add(r) })
        }
        item { Text(" ", modifier = Modifier.padding(bottom = 12.dp)) }
    }
}
