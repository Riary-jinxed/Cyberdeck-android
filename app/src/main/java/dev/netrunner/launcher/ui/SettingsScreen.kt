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
fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
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
