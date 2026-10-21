package dev.netrunner.launcher.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.netrunner.launcher.lock.CyberLock
import dev.netrunner.launcher.system.Diagnostics
import dev.netrunner.launcher.system.Ghost
import dev.netrunner.launcher.system.Tones
import dev.netrunner.launcher.system.ConfigBackup
import dev.netrunner.launcher.system.Gestures
import dev.netrunner.launcher.system.NightWatch
import dev.netrunner.launcher.system.Profiles
import dev.netrunner.launcher.system.Updater
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.netrunner.launcher.system.UiSound
import dev.netrunner.launcher.tiles.DeckTiles
import dev.netrunner.launcher.wallpaper.BreachWallpaperService
import android.widget.Toast
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction

/** Netrunner settings and the permission diagnostic, all in one place. */
@Composable
fun SettingsScreen(onClose: () -> Unit, onConfigChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = ConfigBackup.export(context, uri)
            Toast.makeText(context, if (ok) "Config sauvegardée" else "Échec de la sauvegarde", Toast.LENGTH_SHORT).show()
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (ConfigBackup.restore(context, uri)) {
                Toast.makeText(context, "Config restaurée, redémarrage du deck…", Toast.LENGTH_SHORT).show()
                ConfigBackup.restart(context)
            } else {
                Toast.makeText(context, "Fichier de config illisible", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var updateState by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var downloading by remember { mutableStateOf<Float?>(null) }
    // Bumped on resume and after each change, so states and checks are read again.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val checks = remember(tick) { Diagnostics.run(context) }
    val missing = checks.count { it.ok == false && !it.optional }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.98f))
            .scanlines()
            .systemBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PanelHeader(title = "// CONFIG DU DECK", accent = Cp.Yellow, onClose = onClose)

        Section("PROFIL")
        Chips(Profiles.Profile.entries.map { it.label }, Profiles.Profile.entries.indexOf(Profiles.current)) { i ->
            if (Profiles.switchTo(context, Profiles.Profile.entries[i])) {
                UiSound.play(context, UiSound.Cue.Glitch)
                onConfigChanged()
                tick++
            }
        }
        Text(
            "Chaque profil garde ses quickhacks, dossiers, faction, fond, sons d'interface et mode de sonnerie.",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 11.sp,
        )
        val ringer = remember(tick) { Profiles.ringerFor(context, Profiles.current) }
        Action(
            "Sonnerie du profil",
            if (ringer == android.media.AudioManager.RINGER_MODE_NORMAL) "Son activé (touche pour passer en vibreur)" else "Vibreur (touche pour activer le son)",
        ) {
            Profiles.toggleRinger(context, Profiles.current)
            tick++
        }

        Section("APPARENCE")
        Label("Faction")
        Chips(
            options = Faction.entries.filter { it != Faction.Ghost }.map { it.label },
            selected = Faction.entries.filter { it != Faction.Ghost }.indexOf(Cp.faction),
        ) { i ->
            UiSound.play(context, UiSound.Cue.Glitch)
            Cp.select(context, Faction.entries.filter { it != Faction.Ghost }[i])
        }
        Label("Fond animé")
        Chips(WallpaperMode.entries.map { it.label }, WallpaperMode.entries.indexOf(Wallpaper.mode)) { i ->
            Wallpaper.select(context, WallpaperMode.entries[i])
        }
        Action("Fond d'écran système", "Met la grille de breach animée partout, y compris sur l'écran verrouillé") {
            runCatching { context.startActivity(BreachWallpaperService.pickerIntent(context)) }
        }
        Label("Animation de démarrage")
        Chips(BootStyle.entries.map { it.label }, BootStyle.entries.indexOf(DeckSettings.bootStyle)) { i ->
            DeckSettings.setBootStyle(context, BootStyle.entries[i])
        }
        HandleField()
        Toggle("Icônes néon", "Icônes des applis teintées à la couleur de la faction", DeckSettings.neonIcons) {
            DeckSettings.setNeonIcons(context, !DeckSettings.neonIcons)
        }
        Toggle("Sons d'interface", "Bips, glitchs et vibrations (muets en mode silencieux)", remember(tick) { UiSound.isEnabled(context) }) {
            UiSound.setEnabled(context, !UiSound.isEnabled(context))
            tick++
        }

        Section("MODE NUIT NETWATCH")
        Toggle(
            "Mode nuit",
            "Interface assombrie, sans fond animé ni glitchs de ${NightWatch.startHour}h à ${NightWatch.endHour}h",
            NightWatch.enabled,
        ) { NightWatch.setEnabled(context, !NightWatch.enabled) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Action("Début : ${NightWatch.startHour}h", "+1 h") { NightWatch.shiftStart(context) } }
            Box(Modifier.weight(1f)) { Action("Fin : ${NightWatch.endHour}h", "+1 h") { NightWatch.shiftEnd(context) } }
        }

        Section("MUSIQUE")
        val micOn = remember(tick) {
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        Toggle(
            "Vrai spectre audio",
            "Le visualiseur lit le son réellement joué (Android demande l'accès au micro pour ça, rien n'est enregistré). Sinon : animation simulée.",
            micOn,
        ) {
            if (!micOn) askMic.launch(android.Manifest.permission.RECORD_AUDIO)
            else context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            tick++
        }

        Section("GESTES")
        Gestures.Gesture.entries.forEach { g -> GestureRow(g) }

        Section("SONNERIES ET SONS")
        Text(
            "▶ écoute · ► installe dans les sons du téléphone et l'active par défaut",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 11.sp,
        )
        DisposableEffect(Unit) { onDispose { Tones.stopPreview() } }
        Tones.Kind.entries.forEach { kind ->
            Label(kind.label)
            Tones.all.filter { it.kind == kind }.forEach { tone -> ToneRow(tone) { tick++ } }
        }

        Section("TUILES DU VOLET")
        DeckTiles.all.forEach { entry ->
            Action(entry.label, "Ajouter la tuile dans le volet de réglages rapides") {
                if (!DeckTiles.requestAdd(context, entry)) {
                    Toast.makeText(context, "Ouvre le volet ▸ crayon ▸ ajoute la tuile Netrunner", Toast.LENGTH_LONG).show()
                }
            }
        }

        Section("SÉCURITÉ")
        val lockOn = remember(tick) { CyberLock.isEnabled(context) }
        Toggle("Verrou cyber", "Écran cyberdeck par-dessus le verrou système", lockOn) {
            CyberLock.setEnabled(context, !lockOn)
            if (!lockOn) CyberLock.missingStep(context)?.let { context.startActivity(it) }
            tick++
        }
        val ghostOn = remember(tick) { Ghost.isOn(context) }
        Toggle("Mode furtif", "Silence, HUD GHOST, radios à couper (aussi : double-tap sur l'horloge)", ghostOn) {
            Ghost.toggle(context)
            tick++
        }

        Section("SAUVEGARDE")
        Action("Exporter la config", "Réglages, faction, quickhacks, dossiers, gestes et profils dans un fichier") {
            exportFile.launch("netrunner-config.json")
        }
        Action("Restaurer une config", "Recharge un fichier exporté, puis redémarre le deck") {
            importFile.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        }

        Section("MISE À JOUR")
        val installed = remember { Updater.currentVersion(context) }
        val r = release
        when {
            downloading != null -> Text(
                "> TÉLÉCHARGEMENT ${((downloading ?: 0f) * 100).toInt()}%",
                color = Cp.Yellow,
                fontFamily = MonoFont,
                fontSize = 13.sp,
            )
            r != null -> Action("Installer la v${r.version}", "Version actuelle : $installed · ${(r.size / 1_048_576f).let { "%.1f".format(it) }} Mo") {
                if (!Updater.canInstall(context)) {
                    Toast.makeText(context, "Autorise Netrunner à installer des applis, puis réessaie", Toast.LENGTH_LONG).show()
                    context.startActivity(Updater.installPermissionIntent(context))
                } else {
                    scope.launch {
                        downloading = 0f
                        val file = runCatching { Updater.download(context, r) { downloading = it } }.getOrNull()
                        downloading = null
                        if (file != null) Updater.install(context, file) else updateState = "Échec du téléchargement"
                    }
                }
            }
            else -> Action("Chercher une mise à jour", updateState ?: "Version installée : $installed") {
                updateState = "Recherche…"
                scope.launch {
                    updateState = runCatching { Updater.check(context) }.fold(
                        onSuccess = { found ->
                            release = found
                            if (found == null) "À jour (v$installed)" else null
                        },
                        onFailure = { "Réseau injoignable" },
                    )
                }
            }
        }
        r?.notes?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, maxLines = 6)
        }

        Section("SYSTÈME")
        Action("Applis par défaut", "Écran d'accueil, téléphone, SMS") {
            context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Action("Claviers", "Activer ou choisir le clavier Netrunner") {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        Section(if (missing == 0) "DIAGNOSTIC : SYSTÈMES NOMINAUX" else "DIAGNOSTIC : $missing POINT(S) À CORRIGER")
        checks.forEach { check ->
            val (mark, color) = when (check.ok) {
                true -> "✓" to Cp.Cyan
                false -> (if (check.optional) "○" to Cp.Dim else "✕" to Cp.Red)
                null -> "?" to Cp.Yellow
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = color.copy(alpha = 0.5f), cut = 8.dp)
                    .clickable(enabled = check.ok != true) { check.fix(context) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(mark, color = color, fontFamily = HudFont, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(check.title.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(check.detail, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp)
                }
                if (check.ok != true) Text("►", color = color, fontFamily = HudFont, fontSize = 14.sp)
            }
        }
        Text(
            "✓ OK   ✕ requis   ○ optionnel   ? à vérifier à la main (HyperOS)",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun GestureRow(gesture: Gestures.Gesture) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        Action(gesture.label, Gestures.actionFor(gesture).label) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Gestures.Action.entries.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            action.label.uppercase(),
                            color = if (action == Gestures.actionFor(gesture)) Cp.Yellow else Cp.Cyan,
                            fontFamily = HudFont,
                            fontSize = 13.sp,
                        )
                    },
                    onClick = {
                        Gestures.bind(context, gesture, action)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun HandleField() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(DeckSettings.handle) }
    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), cut = 8.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PSEUDO  ", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(16) },
            singleLine = true,
            textStyle = TextStyle(color = Cp.Yellow, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            cursorBrush = SolidColor(Cp.Yellow),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { DeckSettings.setHandle(context, text) }),
            modifier = Modifier.weight(1f),
        )
        Text(
            "OK",
            color = Cp.Cyan,
            fontFamily = HudFont,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable {
                DeckSettings.setHandle(context, text)
                UiSound.play(context, UiSound.Cue.Confirm)
            }.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ToneRow(tone: Tones.Tone, onChanged: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 8.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "▶",
            color = Cp.Yellow,
            fontFamily = HudFont,
            fontSize = 18.sp,
            modifier = Modifier.clickable { Tones.preview(tone) }.padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
        )
        Text(tone.title.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(
            "DÉFINIR ►",
            color = Cp.Cyan,
            fontFamily = HudFont,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable {
                    if (!Tones.canSetDefault(context)) {
                        Toast.makeText(context, "Autorise « Modifier les paramètres système » puis réessaie", Toast.LENGTH_LONG).show()
                        context.startActivity(Tones.writeSettingsIntent(context))
                    } else if (Tones.install(context, tone)) {
                        UiSound.play(context, UiSound.Cue.Confirm)
                        Toast.makeText(context, "${tone.kind.label} : ${tone.title}", Toast.LENGTH_SHORT).show()
                        onChanged()
                    } else {
                        Toast.makeText(context, "Échec de l'installation du son", Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        "// $title",
        color = Cp.Yellow,
        fontFamily = HudFont,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                color = if (on) Cp.Background else Cp.Cyan,
                fontFamily = HudFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.7f), fill = if (on) Cp.Cyan else Cp.Panel, cut = 6.dp)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onToggle: () -> Unit) {
    SettingRow(title, detail, if (on) "ON" else "OFF", if (on) Cp.Yellow else Cp.Dim, onToggle)
}

@Composable
private fun Action(title: String, detail: String, onClick: () -> Unit) {
    SettingRow(title, detail, "►", Cp.Cyan, onClick)
}

@Composable
private fun SettingRow(title: String, detail: String, state: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = color.copy(alpha = 0.6f), cut = 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp)
        }
        Text(state, color = color, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
