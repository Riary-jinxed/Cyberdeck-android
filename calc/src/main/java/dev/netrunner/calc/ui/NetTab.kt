package dev.netrunner.calc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.calc.engine.Subnet
import dev.netrunner.deck.Cp
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.hudFieldColors
import dev.netrunner.deck.hudPanel

/** IPv4 subnet calculator: details, binary view, splitting, and the prefix for a host count. */
@Composable
fun NetTab() {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("192.168.1.10/24") }
    var hosts by rememberSaveable { mutableStateOf("50") }
    val info = remember(input) { Subnet.parse(input) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("SOUS-RÉSEAU IPv4", Cp.Yellow)
        Spacer(Modifier.height(8.dp))
        TextField(
            input, { input = it }, singleLine = true, colors = hudFieldColors(),
            label = { Text("Adresse/préfixe ou adresse masque", fontFamily = HudFont) },
            textStyle = TextStyle(fontFamily = MonoFont, fontSize = 18.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        if (info == null) {
            Text("Format attendu : 192.168.1.10/24 ou 10.0.0.1 255.255.255.0", color = Cp.Red, fontFamily = HudFont, fontSize = 14.sp)
        } else {
            Details(info)
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("BESOIN D'HÔTES", Cp.Yellow)
        TextField(
            hosts, { hosts = it.filter(Char::isDigit).take(10) }, singleLine = true, colors = hudFieldColors(),
            label = { Text("Nombre d'hôtes", fontFamily = HudFont) },
            textStyle = TextStyle(fontFamily = MonoFont, fontSize = 18.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        hosts.toLongOrNull()?.takeIf { it in 1..(1L shl 32) }?.let { n ->
            val p = Subnet.prefixForHosts(n)
            Text(
                "/$p, masque ${Subnet.text(Subnet.of(0, p).mask)}, ${Subnet.of(0, p).hosts} hôtes utilisables",
                color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Details(info: Subnet.Info) {
    val context = LocalContext.current
    var split by rememberSaveable { mutableIntStateOf(26) }
    Column {
        Column(Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f)).padding(12.dp)) {
            Subnet.report(info).forEach { (label, value) -> Line(label, value) { copy(context, value) } }
        }
        Text("Appui sur une ligne pour la copier.", color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(16.dp))
        SectionTitle("BINAIRE", Cp.Yellow)
        Column(Modifier.fillMaxWidth().padding(top = 6.dp).hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.3f)).padding(12.dp)) {
            listOf("Adresse" to info.address, "Masque" to info.mask, "Réseau" to info.network).forEach { (label, ip) ->
                Text(label, color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp)
                Text(Subnet.binary(ip), color = Cp.Text, fontFamily = MonoFont, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("DÉCOUPAGE", Cp.Yellow)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("En sous-réseaux /", color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.weight(1f))
            HudButton("−", color = Cp.Cyan) { split = (split - 1).coerceAtLeast(info.prefix + 1) }
            Text("$split", color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 12.dp))
            HudButton("+", color = Cp.Cyan) { split = (split + 1).coerceAtMost(32) }
        }
        if (split <= info.prefix || split > 32) {
            Text("Choisis un préfixe plus long que /${info.prefix}.", color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp)
        } else {
            val count = 1L shl (split - info.prefix)
            val parts = Subnet.split(info, split)
            Text(
                "$count sous-réseaux de ${Subnet.of(0, split).hosts} hôtes" + if (count > parts.size) " (${parts.size} premiers)" else "",
                color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp,
            )
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.3f)).padding(12.dp)) {
                parts.forEach { p ->
                    val text = "${Subnet.text(p.network)}/${p.prefix}"
                    Line(text, "${Subnet.text(p.firstHost)} – ${Subnet.text(p.lastHost)}") { copy(context, text) }
                }
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp)) {
        Text(label, color = Cp.Dim, fontFamily = HudFont, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Cp.Text, fontFamily = MonoFont, fontSize = 15.sp)
    }
}
