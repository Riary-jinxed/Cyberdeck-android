package dev.netrunner.aska

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsWindow(aska: Aska, onClose: () -> Unit, onQuit: () -> Unit) {
    val settings by aska.settings.collectAsState()
    var handle by remember { mutableStateOf(settings.handle) }
    var city by remember { mutableStateOf(settings.city) }
    var ollama by remember { mutableStateOf(settings.ollamaUrl) }
    var deck by remember { mutableStateOf(settings.deckLink) }
    var autostartFailed by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = onClose,
        state = rememberWindowState(size = DpSize(560.dp, 760.dp)),
        title = "As//ka : réglages",
        icon = remember { androidx.compose.ui.graphics.painter.BitmapPainter(sprite("chibi_happy")) },
    ) {
        Column(
            Modifier.fillMaxSize().background(Cp.Background).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            T("AS//KA", Cp.Pink, 22.sp, TitleFont)

            Section("ELLE ET TOI")
            Field("Ton nom (comme elle t'appelle)", handle) { handle = it }
            Field("Ta ville (pour sa tenue météo, vide pour aucune)", city) { city = it }

            Section("NOYAU (OLLAMA)")
            CoreSection(aska)

            Section("COMPORTEMENT")
            T("Remarques spontanées", Cp.Dim, 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chatter.entries.forEach { c -> Chip(c.label, settings.chatter == c) { aska.update { it.copy(chatter = c) } } }
            }
            T("Taille", Cp.Dim, 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Petite" to 0.75f, "Normale" to 1f, "Grande" to 1.3f).forEach { (label, s) ->
                    Chip(label, settings.scale == s) { aska.update { it.copy(scale = s) } }
                }
            }
            Toggle("Réagir au clavier (elle compte les touches, sans jamais savoir lesquelles)", settings.keyboard) { on -> aska.update { it.copy(keyboard = on) } }
            Toggle("Mode jeu : plus petite, et pas de remarques", settings.game) { on -> aska.update { it.copy(game = on) } }
            Toggle("Lancer As//ka au démarrage", settings.autostart, enabled = Autostart.available) { on ->
                autostartFailed = !Autostart.set(on)
                if (!autostartFailed) aska.update { it.copy(autostart = on) }
            }
            if (!Autostart.available) T("Disponible une fois As//ka installée.", Cp.Dim, 13.sp)
            if (autostartFailed) T("Le lancement au démarrage n'a pas pu être réglé.", Cp.Red, 13.sp)

            Section("DECK LINK")
            Field("Dossier de Deck Link (vide : recherche automatique)", deck) { deck = it }
            T(
                DeckLink.home(settings.deckLink)?.let { "Kit trouvé dans $it" } ?: "Deck Link introuvable sur ce PC pour l'instant.",
                Cp.Dim, 13.sp,
            )

            Section("RACCOURCIS")
            T("Ctrl+Alt+A : lui parler, même en jeu.  Double-clic : lui parler.  Clic : l'embêter.  Clic droit : ces réglages.  Glisser : la déplacer.", Cp.Text, 14.sp)
            if (!Activity.hooked) T("Le clavier global n'est pas accessible sur cette session (Wayland ?) : ni rebonds, ni raccourci.", Cp.Yellow, 13.sp)

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("ENREGISTRER", Cp.Pink) {
                    aska.update { it.copy(handle = handle.trim().ifEmpty { "V" }, city = city.trim(), ollamaUrl = ollama.trim().ifEmpty { "http://localhost:11434" }, deckLink = deck.trim()) }
                    onClose()
                }
                Button("EFFACER SA MÉMOIRE", Cp.Dim) { aska.clearMemory() }
                Button("QUITTER", Cp.Red, onQuit)
            }
        }
    }
}

/** Ollama and her model, installed from here, with what they take on the disk. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoreSection(aska: Aska) {
    val settings by aska.settings.collectAsState()
    val models by aska.models.collectAsState()
    val online by aska.online.collectAsState()
    val setup by aska.setup.collectAsState()
    val busy = setup?.working == true
    val free = remember(setup, models) { Core.freeSpace(Core.modelsDir()) }
    // Follows an install made outside (a terminal on Linux) while the settings stay open.
    LaunchedEffect(Unit) {
        while (true) {
            aska.refreshModels()
            delay(5_000)
        }
    }

    T(
        when {
            online && models.isNotEmpty() -> "Ollama tourne, ${models.size} modèle(s) installé(s)."
            online -> "Ollama tourne, mais aucun modèle n'est installé."
            Core.binary() != null -> "Ollama est installé mais ne répond pas. Lance-le depuis le menu Démarrer."
            else -> "Ollama n'est pas installé."
        },
        Cp.Text, 14.sp,
    )
    T("Espace libre sur le disque des modèles : ${Core.gigabytes(free)}", Cp.Dim, 13.sp)

    if (!online && Core.binary() == null) {
        if (isWindows) {
            T("Ollama prend au moins ${Core.gigabytes(Core.OLLAMA_BYTES)} sur le disque, plus le modèle choisi ensuite.", Cp.Dim, 13.sp)
            if (!busy) Button("INSTALLER OLLAMA", Cp.Pink) { aska.installOllama() }
        } else {
            T("Dans un terminal (le mot de passe administrateur est demandé) :", Cp.Dim, 13.sp)
            T(Core.LINUX_COMMAND, Cp.Cyan, 13.sp, MonoFont)
        }
    }

    if (online) {
        T("Modèles Gemma 4 à télécharger :", Cp.Dim, 14.sp)
        Core.choices.forEach { c ->
            val installed = models.any { it == c.tag }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    T("${c.label} · ${Core.gigabytes(c.bytes)}", Cp.Text, 15.sp)
                    T(c.note, Cp.Dim, 13.sp)
                }
                when {
                    installed -> T("INSTALLÉ", Cp.Cyan, 12.sp, TitleFont)
                    busy -> {}
                    c.bytes > free -> T("PAS ASSEZ DE PLACE", Cp.Red, 12.sp, TitleFont)
                    else -> Button("TÉLÉCHARGER", Cp.Pink) { aska.downloadModel(c.tag) }
                }
            }
        }
        if (models.isNotEmpty()) {
            T("Modèle utilisé :", Cp.Dim, 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Auto", settings.model.isBlank()) { aska.update { it.copy(model = "") } }
                models.forEach { m -> Chip(m, settings.model == m) { aska.update { it.copy(model = m) } } }
            }
        }
    }

    setup?.let { s ->
        T(s.label + (s.progress?.let { " ${(it * 100).toInt()} %" } ?: ""), if (s.failed) Cp.Red else Cp.Yellow, 14.sp)
        s.progress?.let { p ->
            Box(Modifier.fillMaxWidth().height(6.dp).background(Cp.Panel)) {
                Box(Modifier.fillMaxWidth(p).height(6.dp).background(Cp.Pink))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    T(title, Cp.Cyan, 13.sp, TitleFont, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        T(label, Cp.Dim, 14.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Cp.Text, fontSize = 15.sp, fontFamily = HudFont),
            cursorBrush = SolidColor(Cp.Pink),
            modifier = Modifier.fillMaxWidth().hudPanel(Cp.Dim, Cp.Panel, 6.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    T(
        label, if (selected) Cp.Background else Cp.Text, 14.sp,
        modifier = Modifier.hudPanel(if (selected) Cp.Pink else Cp.Dim, if (selected) Cp.Pink else Cp.Panel, 6.dp)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Toggle(label: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        T(if (on) "[■]" else "[ ]", if (enabled) Cp.Pink else Cp.Dim, 15.sp, MonoFont)
        T(label, if (enabled) Cp.Text else Cp.Dim, 15.sp)
    }
}

@Composable
private fun Button(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    T(label, color, 13.sp, TitleFont, modifier = Modifier.hudPanel(color, Cp.Panel, 8.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}
