package dev.netrunner.clock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.netrunner.clock.data.ClockPrefs
import dev.netrunner.clock.system.Reliability
import dev.netrunner.clock.system.Updater
import kotlinx.coroutines.launch

@Composable
fun SettingsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Re-read the checks each time the user comes back from a settings page.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsState()
    val checks = remember(resumed) { Reliability.checks(context) }

    var snooze by remember { mutableIntStateOf(ClockPrefs.snoozeMinutes(context)) }
    var crescendo by remember { mutableIntStateOf(ClockPrefs.crescendoSeconds(context)) }
    var ringFor by remember { mutableIntStateOf(ClockPrefs.ringMinutes(context)) }
    var home by remember { mutableStateOf(ClockPrefs.homeAfterMorning(context)) }
    var faction by remember { mutableStateOf(Cp.faction) }
    var update by remember { mutableStateOf("") }
    var release by remember { mutableStateOf<Updater.Release?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("FIABILITÉ DES ALARMES", Cp.Yellow)
        Text(
            "HyperOS coupe volontiers les applis en fond. Règle chaque ligne une fois pour que les alarmes sonnent toujours.",
            color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        checks.forEach { c ->
            val color = when (c.ok) { true -> Cp.Cyan; false -> Cp.Red; null -> Cp.Yellow }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).hudPanel(borderColor = color.copy(alpha = 0.6f))
                    .clickable { c.fix(context) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(when (c.ok) { true -> "✓"; false -> "✕"; null -> "?" }, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(c.title, color = Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(c.detail, color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp)
                }
                if (c.ok != true) Text("▸", color = color, fontSize = 18.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("REMPLACER L'HORLOGE XIAOMI", Cp.Yellow)
        Text(
            "1. Supprime ou désactive tes alarmes dans l'horloge Xiaomi.\n" +
                "2. La première fois qu'As//ka règle une alarme, Android demande quelle horloge utiliser : choisis Horloge Netrunner, puis « Toujours ».",
            color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        HudButton("OUVRIR L'HORLOGE XIAOMI", color = Cp.Cyan, modifier = Modifier.fillMaxWidth()) { Reliability.openStockClock(context) }

        Spacer(Modifier.height(18.dp))
        SectionTitle("SONNERIE", Cp.Yellow)
        Stepper("Répit", "$snooze min", onMinus = { snooze = (snooze - 1).coerceAtLeast(1); ClockPrefs.setSnoozeMinutes(context, snooze) }) {
            snooze = (snooze + 1).coerceAtMost(30); ClockPrefs.setSnoozeMinutes(context, snooze)
        }
        Stepper("Volume progressif", if (crescendo == 0) "non" else "$crescendo s", onMinus = {
            crescendo = (crescendo - 10).coerceAtLeast(0); ClockPrefs.setCrescendoSeconds(context, crescendo)
        }) { crescendo = (crescendo + 10).coerceAtMost(120); ClockPrefs.setCrescendoSeconds(context, crescendo) }
        Stepper("Arrêt automatique après", "$ringFor min", onMinus = {
            ringFor = (ringFor - 1).coerceAtLeast(1); ClockPrefs.setRingMinutes(context, ringFor)
        }) { ringFor = (ringFor + 1).coerceAtMost(30); ClockPrefs.setRingMinutes(context, ringFor) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Retour à l'accueil après le réveil", color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp)
                Text("Le matin, pour qu'As//ka te dise bonjour.", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp)
            }
            Switch(checked = home, onCheckedChange = { home = it; ClockPrefs.setHomeAfterMorning(context, it) }, colors = hudSwitch())
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("FACTION", Cp.Yellow)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Faction.entries.forEach { f ->
                Box(
                    Modifier.size(40.dp).background(f.palette.yellow, hudShape(6.dp))
                        .clickable { Cp.select(context, f); faction = f },
                    contentAlignment = Alignment.Center,
                ) { if (f == faction) Text("◆", color = f.palette.background, fontSize = 16.sp) }
            }
        }
        Text(faction.label, color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(18.dp))
        SectionTitle("MISE À JOUR", Cp.Yellow)
        Text("Version ${Updater.currentVersion(context)}", color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp))
        if (update.isNotEmpty()) Text(update, color = Cp.Cyan, fontFamily = HudFont, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        val r = release
        if (r == null) {
            HudButton("VÉRIFIER", color = Cp.Cyan, modifier = Modifier.fillMaxWidth()) {
                update = "Recherche…"
                scope.launch {
                    update = runCatching { Updater.check(context) }.fold(
                        onSuccess = { found -> release = found; if (found == null) "À jour." else "Version ${found.version} disponible." },
                        onFailure = { "Réseau injoignable." },
                    )
                }
            }
        } else {
            HudButton("INSTALLER ${r.version}", filled = true, modifier = Modifier.fillMaxWidth()) {
                if (!Updater.canInstall(context)) {
                    context.startActivity(Updater.installPermissionIntent(context))
                    return@HudButton
                }
                scope.launch {
                    runCatching {
                        val apk = Updater.download(context, r) { p -> update = "Téléchargement ${(p * 100).toInt()} %" }
                        Updater.install(context, apk)
                    }.onFailure { update = "Téléchargement interrompu." }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Stepper(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.weight(1f))
        HudButton("−", color = Cp.Cyan, onClick = onMinus)
        Text(value, color = Cp.Yellow, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 12.dp))
        HudButton("+", color = Cp.Cyan, onClick = onPlus)
    }
}
