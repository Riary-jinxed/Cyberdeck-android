package dev.netrunner.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Faction: follow the launcher's theme, or pick one for this app. */
@Composable
fun FactionSection() {
    val context = LocalContext.current
    SectionTitle("FACTION", Cp.Yellow)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Suivre le launcher", color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp)
            Text(
                when {
                    !Cp.follow -> "Thème propre à cette appli."
                    Cp.linked -> "Même thème que le launcher, mis à jour à chaque retour dans l'appli."
                    else -> "Launcher introuvable ou trop ancien : thème local en attendant."
                },
                color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp,
            )
        }
        Switch(checked = Cp.follow, onCheckedChange = { Cp.setFollow(context, it) }, colors = hudSwitch())
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Faction.entries.forEach { f ->
            Box(
                Modifier.size(36.dp).background(f.palette.yellow, hudShape(6.dp))
                    .clickable { Cp.select(context, f) },
                contentAlignment = Alignment.Center,
            ) { if (f == Cp.faction) Text("◆", color = f.palette.background, fontSize = 15.sp) }
        }
    }
    Text(
        Cp.faction.label + if (Cp.follow) "" else " · choisi ici",
        color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
    )
}

/** Version line, check and install from the app's own GitHub releases. */
@Composable
fun UpdateSection(updater: Updater) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var release by remember { mutableStateOf<Updater.Release?>(null) }

    Column {
        SectionTitle("MISE À JOUR", Cp.Yellow)
        Text("Version ${updater.currentVersion(context)}", color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp))
        if (status.isNotEmpty()) Text(status, color = Cp.Cyan, fontFamily = HudFont, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        val r = release
        if (r == null) {
            HudButton("VÉRIFIER", color = Cp.Cyan, modifier = Modifier.fillMaxWidth()) {
                status = "Recherche…"
                scope.launch {
                    status = runCatching { updater.check(context) }.fold(
                        onSuccess = { found -> release = found; if (found == null) "À jour." else "Version ${found.version} disponible." },
                        onFailure = { "Réseau injoignable." },
                    )
                }
            }
        } else {
            HudButton("INSTALLER ${r.version}", filled = true, modifier = Modifier.fillMaxWidth()) {
                if (!updater.canInstall(context)) {
                    context.startActivity(updater.installPermissionIntent(context))
                    return@HudButton
                }
                scope.launch {
                    runCatching {
                        val apk = updater.download(context, r) { p -> status = "Téléchargement ${(p * 100).toInt()} %" }
                        updater.install(context, apk)
                    }.onFailure { status = "Téléchargement interrompu." }
                }
            }
        }
    }
}
