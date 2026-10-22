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
import androidx.compose.foundation.ScrollState
import androidx.activity.compose.BackHandler
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
import dev.netrunner.launcher.system.MusicTone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import dev.netrunner.launcher.system.ConfigBackup
import dev.netrunner.launcher.system.Gestures
import dev.netrunner.launcher.system.NightWatch
import dev.netrunner.launcher.system.Profiles
import dev.netrunner.launcher.system.Updater
import dev.netrunner.launcher.system.DeckApps
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
    var music by remember { mutableStateOf<MusicTone.Source?>(null) }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            music = MusicTone.probe(context, uri)
            if (music == null) Toast.makeText(context, "Fichier audio illisible", Toast.LENGTH_SHORT).show()
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
    // Home grid of categories; each opens its own page, back returns to the grid.
    var page by remember { mutableStateOf<ConfigPage?>(null) }
    BackHandler(enabled = page != null) { page = null }
    val scroll = remember(page) { ScrollState(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.98f))
            .scanlines()
            .systemBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PanelHeader(
            title = page?.let { "// CONFIG ▸ ${it.label}" } ?: "// CONFIG DU DECK",
            accent = Cp.Yellow,
            onClose = { if (page != null) page = null else onClose() },
        )

        val current = page
        if (current == null) {
            ConfigHome(missing = missing, onOpen = { page = it })
        } else when (current) {
            ConfigPage.Profile -> {
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
            }
            ConfigPage.Look -> {
                Section("APPARENCE")
                Label("Faction")
                // Two rows of three: the faction names stay readable.
                Faction.entries.filter { it != Faction.Ghost }.chunked(3).forEach { row ->
                    Chips(options = row.map { it.label }, selected = row.indexOf(Cp.faction)) { i ->
                        UiSound.play(context, UiSound.Cue.Glitch)
                        Cp.select(context, row[i])
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    "« ${Cp.faction.motto} »",
                    color = Cp.Dim,
                    fontFamily = HudFont,
                    fontSize = 11.sp,
                )
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
                Label("Glitchs")
                Chips(GlitchMode.entries.map { it.label }, GlitchMode.entries.indexOf(Cp.glitchMode)) { i ->
                    Cp.setGlitchMode(context, GlitchMode.entries[i])
                }
                Text(
                    "Auto : selon la faction (Arasaka, Militech, Barghest et Ghost restent nets). Les glitchs coupent aussi en mode nuit et en éco.",
                    color = Cp.Dim,
                    fontFamily = HudFont,
                    fontSize = 11.sp,
                )
                HandleField()
                Toggle("Icônes néon", "Icônes des applis teintées à la couleur de la faction", DeckSettings.neonIcons) {
                    DeckSettings.setNeonIcons(context, !DeckSettings.neonIcons)
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

                Section("MODE ÉCO RIPPERDOC")
                Text(
                    "Coupe le fond animé, les glitchs, l'animation de démarrage et le visualiseur. Auto : sous ${dev.netrunner.launcher.system.Ripperdoc.LOW_BATTERY} % ou avec l'économiseur d'Android.",
                    color = Cp.Dim,
                    fontFamily = HudFont,
                    fontSize = 11.sp,
                )
                Chips(
                    dev.netrunner.launcher.system.Ripperdoc.Mode.entries.map { it.label },
                    dev.netrunner.launcher.system.Ripperdoc.Mode.entries.indexOf(dev.netrunner.launcher.system.Ripperdoc.mode),
                ) { i ->
                    dev.netrunner.launcher.system.Ripperdoc.setMode(context, dev.netrunner.launcher.system.Ripperdoc.Mode.entries[i])
                }
            }
            ConfigPage.Sound -> {
                Section("INTERFACE")
                Toggle("Sons d'interface", "Bips, glitchs et vibrations (muets en mode silencieux)", remember(tick) { UiSound.isEnabled(context) }) {
                    UiSound.setEnabled(context, !UiSound.isEnabled(context))
                    tick++
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

                Section("SONNERIES ET SONS")
                Text(
                    "▶ écoute · ► installe dans les sons du téléphone et l'active par défaut",
                    color = Cp.Dim,
                    fontFamily = HudFont,
                    fontSize = 11.sp,
                )
                DisposableEffect(Unit) {
                    onDispose {
                        Tones.stopPreview()
                        MusicTone.stopPreview()
                    }
                }
                val source = music
                if (source == null) {
                    Action("Importer une musique", "Un morceau du téléphone, coupé à l'extrait voulu, en sonnerie ou notification") {
                        pickMusic.launch(arrayOf("audio/*"))
                    }
                } else {
                    MusicCutter(source, onClose = { music = null })
                }
                Tones.Kind.entries.forEach { kind ->
                    Label(kind.label)
                    Tones.all.filter { it.kind == kind }.forEach { tone -> ToneRow(tone) { tick++ } }
                }
            }
            ConfigPage.Gestures -> {
                Section("GESTES")
                Gestures.Gesture.entries.forEach { g -> GestureRow(g) }

                Section("TUILES DU VOLET")
                DeckTiles.all.forEach { entry ->
                    Action(entry.label, "Ajouter la tuile dans le volet de réglages rapides") {
                        if (!DeckTiles.requestAdd(context, entry)) {
                            Toast.makeText(context, "Ouvre le volet ▸ crayon ▸ ajoute la tuile Netrunner", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            ConfigPage.Aska -> {
                Section("AS//KA")
                Toggle("Widget sur l'accueil", "Sa carte avec son humeur et sa phrase du jour, en haut des widgets", DeckSettings.askaWidget) {
                    DeckSettings.setAskaWidget(context, !DeckSettings.askaWidget)
                }
                Toggle("Sigil à côté de l'horloge", "Son emblème animé sur l'accueil. Masqué, elle reste joignable par le hub et les gestes", DeckSettings.askaSigil) {
                    DeckSettings.setAskaSigil(context, !DeckSettings.askaSigil)
                }
                Toggle("Carte du matin", "Après l'alarme, elle t'accueille avec le programme du jour", DeckSettings.askaMorning) {
                    DeckSettings.setAskaMorning(context, !DeckSettings.askaMorning)
                }
                Toggle("Sur l'écran verrouillé", "Elle dort en bas du verrou et marmonne (batterie, heure tardive)", DeckSettings.askaLock) {
                    DeckSettings.setAskaLock(context, !DeckSettings.askaLock)
                }
                Toggle(
                    "Rendez-vous adultes (18+)",
                    "As//ka, 26 ans, peut devenir sensuelle et explicite en rendez-vous si tu le lui demandes. Texte uniquement",
                    DeckSettings.adultDates,
                ) { DeckSettings.setAdultDates(context, !DeckSettings.adultDates) }
            }
            ConfigPage.Security -> {
                Section("SÉCURITÉ")
                val lockOn = remember(tick) { CyberLock.isEnabled(context) }
                Toggle("Verrou cyber", "Écran cyberdeck par-dessus le verrou système", lockOn) {
                    CyberLock.setEnabled(context, !lockOn)
                    if (!lockOn) CyberLock.missingStep(context)?.let { context.startActivity(it) }
                    tick++
                }
                if (lockOn) {
                    val handoffOn = remember(tick) { CyberLock.handoff(context) }
                    Toggle("Passer la main au visage", "À l'allumage, rend la main au verrou système : visage ou empreinte, sans glisser", handoffOn) {
                        CyberLock.setHandoff(context, !handoffOn)
                        tick++
                    }
                }
                val ghostOn = remember(tick) { Ghost.isOn(context) }
                Toggle("Mode furtif", "Silence, HUD GHOST, radios à couper (aussi : double-tap sur l'horloge)", ghostOn) {
                    Ghost.toggle(context)
                    tick++
                }
            }
            ConfigPage.Keyboard -> {
                Section("SYSTÈME")
                Action("Applis par défaut", "Écran d'accueil, téléphone, SMS") {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                Action("Claviers", "Activer ou choisir le clavier Netrunner") {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                val autocorrect = remember(tick) { dev.netrunner.launcher.keyboard.KeyboardPrefs.autocorrect(context) }
                Toggle(
                    "Correction automatique",
                    "Le clavier corrige une faute évidente à l'espace (⌫ juste après annule). Les suggestions restent affichées dans tous les cas.",
                    autocorrect,
                ) {
                    dev.netrunner.launcher.keyboard.KeyboardPrefs.setAutocorrect(context, !autocorrect)
                    tick++
                }
            }
            ConfigPage.Apps -> {
                Section("APPLIS DU DECK")
                DeckAppsSection()
            }
            ConfigPage.Backup -> {
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
            }
            ConfigPage.Diagnostic -> {
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
            }
        }
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

private fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/** Picks the excerpt of an imported song, then installs it as ringtone or notification sound. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicCutter(source: MusicTone.Source, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val total = source.durationMs.toFloat()
    var range by remember(source) { mutableStateOf(0f..minOf(total, 30_000f)) }
    var playing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val start = range.start.toLong()
    val end = range.endInclusive.toLong()

    fun install(kind: Tones.Kind) {
        if (!Tones.canSetDefault(context)) {
            Toast.makeText(context, "Autorise « Modifier les paramètres système » puis réessaie", Toast.LENGTH_LONG).show()
            context.startActivity(Tones.writeSettingsIntent(context))
            return
        }
        MusicTone.stopPreview()
        playing = false
        busy = true
        scope.launch {
            val ok = MusicTone.install(context, source, start, end, kind)
            busy = false
            if (ok) {
                UiSound.play(context, UiSound.Cue.Confirm)
                Toast.makeText(context, "${kind.label} : ${source.title}", Toast.LENGTH_SHORT).show()
                onClose()
            } else {
                Toast.makeText(context, "Impossible de découper ce fichier", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.7f), cut = 8.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(source.title.uppercase(), color = Cp.Yellow, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            "EXTRAIT ${clock(start)} → ${clock(end)} · ${(end - start) / 1000} s (max ${MusicTone.MAX_MS / 1000} s)",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 12.sp,
        )
        RangeSlider(
            value = range,
            onValueChange = { new ->
                // Keep the excerpt between 1 s and the maximum, moving whichever end was not dragged.
                val max = MusicTone.MAX_MS.toFloat()
                range = when {
                    new.endInclusive - new.start > max ->
                        if (new.start != range.start) new.start..(new.start + max) else (new.endInclusive - max)..new.endInclusive
                    new.endInclusive - new.start < 1_000f -> range
                    else -> new
                }
                if (playing) {
                    MusicTone.stopPreview()
                    playing = false
                }
            },
            valueRange = 0f..total,
            colors = SliderDefaults.colors(
                thumbColor = Cp.Yellow,
                activeTrackColor = Cp.Yellow,
                inactiveTrackColor = Cp.Dim.copy(alpha = 0.4f),
            ),
        )
        if (busy) {
            Text("DÉCOUPAGE EN COURS…", color = Cp.Cyan, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CutterButton(if (playing) "■ STOP" else "▶ ÉCOUTER", Cp.Yellow) {
                    if (playing) {
                        MusicTone.stopPreview()
                    } else {
                        MusicTone.preview(context, source, start, end)
                    }
                    playing = !playing
                }
                CutterButton("SONNERIE ►", Cp.Cyan) { install(Tones.Kind.Ringtone) }
                CutterButton("NOTIF ►", Cp.Cyan) { install(Tones.Kind.Notification) }
                CutterButton("✕", Cp.Red) {
                    MusicTone.stopPreview()
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun CutterButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontFamily = HudFont,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
    )
}

/** Companion apps from the same repository: install, update or open them from here. */
@Composable
private fun DeckAppsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<DeckApps.App>?>(null) }
    var state by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<Pair<String, Float>?>(null) }
    fun refresh() {
        state = "Recherche…"
        scope.launch {
            runCatching { DeckApps.list(context) }.fold(
                onSuccess = {
                    apps = it
                    state = if (it.isEmpty()) "Aucune appli publiée pour l'instant" else null
                },
                onFailure = { state = "Réseau injoignable" },
            )
        }
    }
    // Back from Android's installer: the installed versions changed.
    LifecycleResumeEffect(Unit) {
        if (apps != null) refresh()
        onPauseOrDispose { }
    }
    val list = apps
    if (list == null) {
        Action("Voir les applis du deck", state ?: "Horloge, calculatrice, fichiers… et les prochaines") { refresh() }
        return
    }
    state?.let { Text("> $it", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp) }
    list.forEach { app ->
        val progress = downloading?.takeIf { it.first == app.id }?.second
        val detail = when {
            progress != null -> "Téléchargement ${(progress * 100).toInt()} %"
            app.updatable -> "v${app.installedVersion} → v${app.release.version}"
            app.installed -> "v${app.installedVersion} · à jour"
            else -> "v${app.release.version} · ${"%.1f".format(app.release.size / 1_048_576f)} Mo"
        }
        val verb = when {
            app.updatable -> "METTRE À JOUR ►"
            app.installed -> "OUVRIR ►"
            else -> "INSTALLER ►"
        }
        Row(
            Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = (if (app.updatable) Cp.Yellow else Cp.Cyan).copy(alpha = 0.6f), cut = 8.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(app.title.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(detail, color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
            }
            Text(
                verb,
                color = if (app.installed && !app.updatable) Cp.Cyan else Cp.Yellow,
                fontFamily = HudFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(enabled = downloading == null) {
                        when {
                            app.installed && !app.updatable -> DeckApps.open(context, app)
                            !Updater.canInstall(context) -> {
                                Toast.makeText(context, "Autorise Netrunner à installer des applis, puis réessaie", Toast.LENGTH_LONG).show()
                                context.startActivity(Updater.installPermissionIntent(context))
                            }
                            else -> scope.launch {
                                downloading = app.id to 0f
                                val file = runCatching { DeckApps.download(context, app) { downloading = app.id to it } }.getOrNull()
                                downloading = null
                                if (file != null) Updater.install(context, file) else state = "Échec du téléchargement"
                            }
                        }
                    }
                    .padding(vertical = 6.dp),
            )
        }
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

/** Config categories, shown as a grid of tiles on the config home. */
private enum class ConfigPage(val label: String, val glyph: String, val detail: String) {
    Profile("PROFILS", "◉", "Profil actif et sa sonnerie"),
    Look("APPARENCE", "◢", "Faction, fond, glitchs, démarrage, nuit, éco"),
    Sound("SONS", "♪", "Sons d'interface, spectre audio, sonneries"),
    Gestures("GESTES", "⇄", "Gestes de l'accueil, tuiles du volet"),
    Aska("AS//KA", "✦", "Widget, sigil, carte du matin"),
    Security("SÉCURITÉ", "⛨", "Verrou cyber, mode furtif"),
    Keyboard("CLAVIER & SYSTÈME", "⌨", "Clavier, correction, applis par défaut"),
    Apps("APPLIS DU DECK", "▦", "Applis Netrunner à installer"),
    Backup("SAUVEGARDE & MAJ", "⟳", "Exporter, restaurer, mettre à jour"),
    Diagnostic("DIAGNOSTIC", "✓", "Autorisations et réglages HyperOS"),
}

@Composable
private fun ConfigHome(missing: Int, onOpen: (ConfigPage) -> Unit) {
    ConfigPage.entries.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { p ->
                val alert = p == ConfigPage.Diagnostic && missing > 0
                val color = if (alert) Cp.Red else Cp.Cyan
                Column(
                    Modifier
                        .weight(1f)
                        .height(104.dp)
                        .hudPanel(borderColor = color.copy(alpha = 0.6f), cut = 10.dp)
                        .clickable { onOpen(p) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.glyph, color = if (alert) Cp.Red else Cp.Yellow, fontFamily = HudFont, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        if (alert) Text("$missing ✕", color = Cp.Red, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(p.label, color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(p.detail, color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, maxLines = 2)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    Text(
        "◀ retour ferme une page · ✕ ferme la config",
        color = Cp.Dim,
        fontFamily = HudFont,
        fontSize = 11.sp,
    )
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
