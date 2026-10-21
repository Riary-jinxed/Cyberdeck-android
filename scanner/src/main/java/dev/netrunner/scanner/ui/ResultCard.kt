package dev.netrunner.scanner.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.hudPanel
import dev.netrunner.scanner.data.Codes
import dev.netrunner.scanner.data.Codes.Content
import dev.netrunner.scanner.data.History

/** What a scan contains, readable, with its actions. */
@Composable
fun ResultCard(scan: History.Scan, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    val content = Codes.parse(scan.raw)
    Column(modifier.hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.8f), cut = 10.dp).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "// ${content.label}", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                letterSpacing = 2.sp, modifier = Modifier.weight(1f),
            )
            Text(scan.format, color = Cp.Dim, fontFamily = MonoFont, fontSize = 12.sp)
        }
        SelectionContainer {
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                describe(content).forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        if (k.isNotEmpty()) Text(k, color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.weight(0.3f))
                        Text(v, color = Cp.Text, fontFamily = MonoFont, fontSize = 15.sp, modifier = Modifier.weight(if (k.isNotEmpty()) 0.7f else 1f))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            actions(content, scan.raw).forEachIndexed { i, a -> Chip(a.label, if (i == 0) Cp.Yellow else Cp.Cyan) { a.run(context) } }
            if (onClose != null) Chip("NOUVEAU SCAN", Cp.Dim, onClose)
        }
    }
}

/** Label/value lines for the card ("" label = the whole line is the value). */
fun describe(c: Content): List<Pair<String, String>> = when (c) {
    is Content.Url -> listOf("" to c.url)
    is Content.Wifi -> listOf(
        "Réseau" to c.ssid,
        "Sécurité" to if (c.security == "NOPASS") "ouvert" else c.security,
        "Mot de passe" to c.password.ifEmpty { "—" },
    ) + if (c.hidden) listOf("Masqué" to "oui") else emptyList()
    is Content.Phone -> listOf("Numéro" to c.number)
    is Content.Sms -> listOf("Numéro" to c.number, "Message" to c.body.ifEmpty { "—" })
    is Content.Email -> listOf("À" to c.address) +
        (if (c.subject.isNotEmpty()) listOf("Objet" to c.subject) else emptyList()) +
        (if (c.body.isNotEmpty()) listOf("Texte" to c.body) else emptyList())
    is Content.Geo -> listOf("Position" to "${c.lat}, ${c.lon}") + if (c.query.isNotEmpty()) listOf("Lieu" to c.query) else emptyList()
    is Content.Contact -> listOf("Nom" to c.name.ifEmpty { "—" }) +
        (if (c.org.isNotEmpty()) listOf("Société" to c.org) else emptyList()) +
        c.phones.map { "Tél." to it } + c.emails.map { "E-mail" to it }
    is Content.Product -> listOf("Code" to c.code)
    is Content.Text -> listOf("" to c.text)
}
