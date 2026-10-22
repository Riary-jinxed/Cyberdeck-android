package dev.netrunner.launcher.ui

import android.Manifest
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.annotation.DrawableRes
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.aska.AlarmAction
import dev.netrunner.launcher.aska.AskaEars
import dev.netrunner.launcher.aska.AskaBrain
import dev.netrunner.launcher.aska.AskaDate
import dev.netrunner.launcher.aska.AskaReminders
import dev.netrunner.launcher.aska.AskaModels
import dev.netrunner.launcher.aska.AskaPet
import dev.netrunner.launcher.aska.AskaPetCalls
import dev.netrunner.launcher.aska.AskaPiper
import dev.netrunner.launcher.aska.AskaVoice
import dev.netrunner.launcher.system.SystemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Prompts = listOf(
    "Quoi de neuf ?",
    "Mon programme du jour",
    "Prochaine alarme ?",
    "Réveille-moi à 7h30",
    "Allume la lampe",
    "Minuteur 10 min",
    "Lis mes notes",
    "Prépare mon intervention",
    "Qui es-tu ?",
    "Que sais-tu sur moi ?",
    "Raconte-moi Night City",
    "Un macaron ?",
    "Bonne nuit As//ka",
)

@Composable
fun AskaScreen(brain: AskaBrain, onClose: () -> Unit) {
    // The model only stays in memory while this panel is open and on screen (and a short while after).
    val onScreen = rememberScreenActive()
    DisposableEffect(onScreen) {
        if (onScreen) brain.wake()
        onDispose { if (onScreen) brain.sleepSoon() }
    }
    var showModels by remember { mutableStateOf(false) }
    // Her nest (Tamagotchi), opened by touching her chibi.
    var nest by remember { mutableStateOf(false) }
    // Her notification was touched: straight to her nest.
    LaunchedEffect(AskaPetCalls.openNest) {
        if (AskaPetCalls.openNest) {
            nest = true
            AskaPetCalls.openNest = false
        }
    }
    // Her reminders, opened from the strip above the chat's input.
    var reminders by remember { mutableStateOf(false) }
    val noModel = brain.state == AskaBrain.State.NoModel

    Column(
        Modifier
            .fillMaxSize()
            .dragonScales()
            .dateSky(brain.date)
            .scanlines()
            .systemBarsPadding()
            .displayCutoutPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AskaAvatar(
                Modifier.size(52.dp),
                intensity = if (brain.state == AskaBrain.State.Thinking || brain.voice.speaking) 2.5f else 1f,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                GlitchText(
                    "AS//KA",
                    style = TextStyle(fontFamily = DisplayFont, fontSize = 22.sp, fontWeight = FontWeight.Black),
                    color = AskaColors.Pink,
                )
                Text(
                    statusLabel(brain),
                    color = if (brain.state == AskaBrain.State.NoModel) Cp.Red else AskaColors.HotPink.copy(alpha = 0.8f),
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
            if (nest || reminders) {
                HeaderButton("◂ CHAT", Cp.Cyan) {
                    nest = false
                    reminders = false
                }
            } else {
                HeaderButton(if (showModels && !noModel) "◂ CHAT" else "NOYAU", Cp.Cyan) { showModels = !showModels }
            }
            if (!nest && !reminders && !showModels && !noModel) {
                HeaderButton(if (brain.voice.enabled) "VOIX ●" else "VOIX ○", if (brain.voice.enabled) AskaColors.Pink else Cp.Dim) {
                    brain.voice.toggle()
                }
                HeaderButton("PURGER", Cp.Dim) { brain.purge() }
            }
            Text(
                "[ X ]",
                color = Cp.Red,
                fontFamily = MonoFont,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(onClick = onClose)
                    .padding(8.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(AskaColors.Pink, AskaColors.Magenta, Color.Transparent)))
        )
        when {
            nest -> AskaNest(brain.pet, Modifier.weight(1f)) { scene ->
                brain.startDate(scene)
                nest = false
            }
            reminders -> ReminderBay(Modifier.weight(1f))
            showModels || noModel -> ModelBay(brain, Modifier.weight(1f))
            else -> Chat(brain, Modifier.weight(1f), onChibi = { nest = true }, onReminders = { reminders = true })
        }
    }
}

/** A photo she sent during a date: small in the chat, full screen on touch. */
@Composable
private fun DatePhoto(@DrawableRes photo: Int) {
    var zoom by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    Image(
        painterResource(photo),
        contentDescription = "Photo d'As//ka",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(width = 180.dp, height = 262.dp)
            .hudPanel(borderColor = AskaColors.Pink.copy(alpha = 0.8f), fill = AskaColors.Void, cut = 8.dp)
            .padding(2.dp)
            .clickable { zoom = true },
    )
    if (zoom) {
        Dialog(onDismissRequest = { zoom = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Image(
                painterResource(photo),
                contentDescription = "Photo d'As//ka",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { zoom = false }
                    .padding(12.dp),
            )
        }
    }
}

/** Over the chat during a rendez-vous: where they are, and the way home. */
@Composable
private fun DateBanner(scene: AskaDate.Scene, onEnd: () -> Unit) {
    val glow = Color(scene.glow)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .hudPanel(borderColor = glow.copy(alpha = 0.7f), fill = Color(scene.sky).copy(alpha = 0.85f), cut = 8.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("♥ RENDEZ-VOUS · ${scene.glyph} ${scene.label}", color = glow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(
            "RENTRER ◂",
            color = Cp.Text,
            fontFamily = HudFont,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .hudPanel(borderColor = glow.copy(alpha = 0.6f), fill = Color.Transparent, cut = 5.dp)
                .clickable(onClick = onEnd)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun statusLabel(brain: AskaBrain): String = when (brain.state) {
    AskaBrain.State.NoModel -> "NOYAU ABSENT · INSTALLE UN MODÈLE"
    AskaBrain.State.Asleep -> "EN VEILLE · 100 % LOCAL"
    AskaBrain.State.Waking -> "ÉVEIL DU NOYAU…"
    AskaBrain.State.Ready -> "EN LIGNE · ${brain.backend ?: "CPU"} · 100 % LOCAL"
    AskaBrain.State.Thinking -> "CALCUL EN COURS…"
}

@Composable
private fun HeaderButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontFamily = HudFont,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(start = 6.dp)
            .hudPanel(borderColor = color.copy(alpha = 0.7f), fill = Color.Transparent, cut = 6.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun Chat(brain: AskaBrain, modifier: Modifier, onChibi: () -> Unit, onReminders: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val lines = brain.lines
    val last = lines.lastOrNull()
    LaunchedEffect(lines.size, last?.text?.length) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex, scrollOffset = Int.MAX_VALUE / 2)
    }
    val thinking = brain.state == AskaBrain.State.Thinking

    fun send(text: String) {
        brain.send(text)
        input = ""
    }

    // Her ears: hold a conversation out loud.
    val context = LocalContext.current
    val ears = remember { AskaEars(context) }
    val earsAvailable = remember { ears.available }
    DisposableEffect(Unit) { onDispose { ears.stop() } }
    var earsError by remember { mutableStateOf<String?>(null) }
    fun listen() {
        earsError = null
        brain.voice.stop()
        ears.listen(onResult = { send(it) }, onError = { earsError = it })
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else earsError = "Sans accès au micro, je ne t'entends pas."
    }

    Column(modifier) {
        brain.date?.let { scene -> DateBanner(scene, onEnd = brain::endDate) }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(lines, key = { it.id }) { line -> Bubble(brain, line) }
        }
        ReminderStrip(onReminders)
        Row(verticalAlignment = Alignment.Bottom) {
            // Touching her chibi opens her nest.
            Box(
                Modifier
                    .size(width = 92.dp, height = 104.dp)
                    .clickable(onClick = onChibi)
                    .padding(end = 6.dp)
            ) {
                AskaPad(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(18.dp),
                    color = Color(brain.pet.looks.getValue(AskaPet.Slot.Pad).argb),
                )
                AskaChibi(
                    brain,
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = 6.dp),
                    animated = rememberScreenActive(),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (brain.date?.let { if (AskaDate.photo(it) != null) it.prompts + "Une photo de toi ?" else it.prompts } ?: Prompts).forEach { prompt ->
                        Text(
                            prompt,
                            color = AskaColors.HotPink,
                            fontFamily = HudFont,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .hudPanel(borderColor = AskaColors.Pink.copy(alpha = 0.5f), fill = AskaColors.Panel, cut = 6.dp)
                                .clickable(enabled = !thinking) { send(prompt) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▸", color = AskaColors.Pink, fontFamily = MonoFont, fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        maxLines = 4,
                        textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp),
                        cursorBrush = SolidColor(AskaColors.Pink),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send(input) }),
                        modifier = Modifier
                            .weight(1f)
                            .hudPanel(borderColor = AskaColors.Pink.copy(alpha = 0.6f), fill = AskaColors.Panel, cut = 8.dp)
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        decorationBox = { field ->
                            when {
                                ears.listening -> Text(
                                    ears.heard.ifEmpty { "◉ Je t'écoute…" },
                                    color = AskaColors.HotPink,
                                    fontFamily = HudFont,
                                    fontSize = 15.sp,
                                )
                                input.isEmpty() -> Text(earsError ?: "Parle à As//ka…", color = if (earsError != null) Cp.Red else Cp.Dim, fontFamily = HudFont, fontSize = 15.sp)
                            }
                            if (!ears.listening) field()
                        },
                    )
                    if (earsAvailable && !thinking) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (ears.listening) "■" else "◉",
                            color = if (ears.listening) AskaColors.Void else AskaColors.Pink,
                            fontFamily = MonoFont,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .hudPanel(
                                    borderColor = AskaColors.Pink,
                                    fill = if (ears.listening) AskaColors.HotPink else AskaColors.Panel,
                                    cut = 8.dp,
                                )
                                .clickable {
                                    when {
                                        ears.listening -> ears.finish()
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> listen()
                                        else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                                .padding(horizontal = 11.dp, vertical = 10.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (thinking) "■ STOP" else "◢ ENVOI",
                        color = AskaColors.Void,
                        fontFamily = HudFont,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .hudPanel(borderColor = AskaColors.Pink, fill = if (thinking) Cp.Red else AskaColors.Pink, cut = 8.dp)
                            .clickable { if (thinking) brain.stop() else send(input) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Bubble(brain: AskaBrain, line: AskaBrain.Line) {
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!line.fromUser) {
            AskaAvatar(Modifier.size(30.dp), animated = false)
            Spacer(Modifier.width(6.dp))
        }
        Column(
            Modifier
                .widthIn(max = 320.dp)
                // Touching one of her lines reads it aloud again.
                .clickable(enabled = !line.fromUser && line.done && line.text.isNotEmpty()) {
                    brain.voice.speak(line.text, force = true)
                }
                .hudPanel(
                    borderColor = if (line.fromUser) Cp.Cyan.copy(alpha = 0.5f) else AskaColors.Pink.copy(alpha = 0.75f),
                    fill = if (line.fromUser) Cp.Panel.copy(alpha = 0.9f) else AskaColors.Panel.copy(alpha = 0.92f),
                    cut = 8.dp,
                )
                .padding(10.dp),
        ) {
            Text(
                if (line.fromUser) "${DeckSettings.handle.uppercase()} //" else "AS//KA ◆",
                color = if (line.fromUser) Cp.Cyan else AskaColors.Pink,
                fontFamily = MonoFont,
                fontSize = 10.sp,
            )
            if (line.text.isEmpty() && !line.done) {
                TypingDots()
            } else {
                Text(
                    line.text + if (line.done) "" else " ▌",
                    color = Cp.Text,
                    fontFamily = HudFont,
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                )
            }
            line.photo?.let { photo -> DatePhoto(photo) }
            line.action?.let { action ->
                Spacer(Modifier.height(8.dp))
                Text(action.summary, color = AskaColors.HotPink, fontFamily = MonoFont, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "◢ EXÉCUTER",
                        color = AskaColors.Void,
                        fontFamily = HudFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .hudPanel(borderColor = AskaColors.Pink, fill = AskaColors.Pink, cut = 6.dp)
                            .clickable {
                                // A reminder rings as a notification: ask for the right to post one.
                                if (action is AlarmAction.Remind && Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                brain.confirm(line)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Text(
                        "ANNULER",
                        color = Cp.Dim,
                        fontFamily = HudFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .hudPanel(borderColor = Cp.Dim, fill = Color.Transparent, cut = 6.dp)
                            .clickable { brain.decline(line) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** The next reminder, above the input: touch to see them all. */
@Composable
private fun ReminderStrip(onOpen: () -> Unit) {
    val context = LocalContext.current
    val revision = AskaReminders.revision.intValue
    val upcoming = remember(revision) { AskaReminders.upcoming(context) }
    val next = upcoming.firstOrNull() ?: return
    Text(
        "◷ ${next.whenLabel.uppercase()} · ${next.text}" + if (upcoming.size > 1) "  (+${upcoming.size - 1})" else "",
        color = AskaColors.HotPink,
        fontFamily = MonoFont,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
    )
}

/** All her reminders, with a button to erase each one. */
@Composable
private fun ReminderBay(modifier: Modifier) {
    val context = LocalContext.current
    val revision = AskaReminders.revision.intValue
    val all = remember(revision) { AskaReminders.list(context).sortedWith(compareBy({ it.done }, { it.at })) }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("RAPPELS", color = AskaColors.Pink, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (all.isEmpty()) {
            Text(
                "Aucun rappel. Dis-lui « rappelle-moi d'appeler le client à 15h », « demain à 9h » ou « dans 20 minutes ».",
                color = Cp.Dim,
                fontFamily = HudFont,
                fontSize = 13.sp,
            )
        }
        all.forEach { reminder ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = AskaColors.Pink.copy(alpha = if (reminder.done) 0.2f else 0.5f), fill = AskaColors.Panel)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (reminder.done) "✓ " else "◷ ") + reminder.whenLabel.uppercase(),
                        color = if (reminder.done) Cp.Dim else AskaColors.HotPink,
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    )
                    Text(reminder.text, color = if (reminder.done) Cp.Dim else Cp.Text, fontFamily = HudFont, fontSize = 14.sp)
                }
                BayButton("EFFACER", Cp.Dim) { AskaReminders.remove(context, reminder.id) }
            }
        }
    }
}

/** Install, pick or delete As//ka's model. */
@Composable
private fun ModelBay(brain: AskaBrain, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Bumped to re-read what is on disk after a change.
    var revision by remember { mutableIntStateOf(0) }
    var download by remember { mutableStateOf(AskaModels.pollDownload(context)) }
    var importProgress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var mobileData by remember { mutableStateOf(false) }
    val ramGb = remember { (SystemInfo.memory(context).totalBytes / 1_000_000_000.0).let { Math.round(it).toInt() } }

    fun changed() {
        revision++
        brain.modelChanged()
    }

    // Progress, once a second, only while this screen is shown and a download runs.
    LaunchedEffect(download != null) {
        while (download != null) {
            delay(1000)
            val now = AskaModels.pollDownload(context)
            if (now?.status == DownloadManager.STATUS_SUCCESSFUL) {
                message = "Noyau installé. As//ka s'éveille."
                download = null
                changed()
            } else if (now?.status == DownloadManager.STATUS_FAILED) {
                message = "Téléchargement échoué. Vérifie la connexion et l'espace libre, puis réessaie."
                AskaModels.cancelDownload(context)
                download = null
            } else {
                download = now
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importProgress = 0f
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AskaModels.import(context, uri) { importProgress = it } }
            }
            importProgress = null
            message = result.fold({ "Modèle importé et activé." }, { "Import impossible : ${it.message}" })
            if (result.isSuccess) changed()
        }
    }

    // Re-read on each revision.
    val active = remember(revision) { AskaModels.active(context) }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "As//ka pense directement sur ton téléphone : rien ne part sur internet, c'est gratuit, et le modèle n'est chargé " +
                "que pendant que tu lui parles. Il faut d'abord télécharger son noyau (une fois). Ton téléphone : $ramGb Go de RAM.",
            color = Cp.Text,
            fontFamily = HudFont,
            fontSize = 13.sp,
        )
        message?.let { Text(it, color = AskaColors.HotPink, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold) }

        VoiceBay(brain.voice, mobileData)
        PortraitBay()

        AskaModels.catalog.forEach { model ->
            val installed = remember(revision) { AskaModels.isInstalled(context, model.id) }
            val downloading = download?.takeIf { it.modelId == model.id }
            val tight = ramGb < model.minRamGb
            ModelCard(
                title = model.label,
                detail = "${model.tagline}\n${model.sizeMb} Mo" + if (tight) " · ⚠ un peu lourd pour $ramGb Go de RAM" else "",
                active = active == model.id,
                progress = downloading?.let { if (it.total > 0) it.done.toFloat() / it.total else 0f },
                progressLabel = downloading?.let {
                    when (it.status) {
                        DownloadManager.STATUS_PAUSED -> "EN PAUSE (attente du Wi-Fi ou du réseau)"
                        DownloadManager.STATUS_PENDING -> "EN ATTENTE…"
                        else -> "${it.done / 1_000_000} / ${it.total.coerceAtLeast(0) / 1_000_000} Mo"
                    }
                },
            ) {
                when {
                    downloading != null -> BayButton("ANNULER", Cp.Red) {
                        AskaModels.cancelDownload(context)
                        download = null
                    }
                    installed -> {
                        if (active != model.id) BayButton("UTILISER", AskaColors.Pink) {
                            AskaModels.select(context, model.id)
                            changed()
                        }
                        BayButton("SUPPRIMER", Cp.Dim) {
                            AskaModels.delete(context, model.id)
                            changed()
                        }
                    }
                    download == null -> BayButton("TÉLÉCHARGER", AskaColors.Pink) {
                        message = null
                        AskaModels.startDownload(context, model, mobileData)
                        download = AskaModels.pollDownload(context)
                    }
                }
            }
        }

        if (remember(revision) { AskaModels.isInstalled(context, AskaModels.CUSTOM_ID) }) {
            ModelCard(
                title = AskaModels.labelOf(context, AskaModels.CUSTOM_ID),
                detail = "Fichier importé depuis le téléphone.",
                active = active == AskaModels.CUSTOM_ID,
            ) {
                if (active != AskaModels.CUSTOM_ID) BayButton("UTILISER", AskaColors.Pink) {
                    AskaModels.select(context, AskaModels.CUSTOM_ID)
                    changed()
                }
                BayButton("SUPPRIMER", Cp.Dim) {
                    AskaModels.delete(context, AskaModels.CUSTOM_ID)
                    changed()
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clickable { mobileData = !mobileData }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (mobileData) "[■]" else "[ ]", color = AskaColors.Pink, fontFamily = MonoFont, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Autoriser le téléchargement en données mobiles (sinon Wi-Fi uniquement)",
                color = Cp.Text,
                fontFamily = HudFont,
                fontSize = 13.sp,
            )
        }

        if (importProgress != null) {
            Text(
                "IMPORT… ${((importProgress ?: 0f) * 100).toInt()} %",
                color = AskaColors.HotPink,
                fontFamily = MonoFont,
                fontSize = 12.sp,
            )
        } else {
            BayButton("IMPORTER UN FICHIER .litertlm", Cp.Cyan) { picker.launch(arrayOf("*/*")) }
        }
        Text(
            "Autres modèles compatibles : huggingface.co/litert-community (fichiers .litertlm).",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ModelCard(
    title: String,
    detail: String,
    active: Boolean,
    progress: Float? = null,
    progressLabel: String? = null,
    actions: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(
                borderColor = if (active) AskaColors.Pink else AskaColors.Pink.copy(alpha = 0.35f),
                fill = AskaColors.Panel,
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = AskaColors.Pink, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (active) Text("● ACTIF", color = AskaColors.HotPink, fontFamily = MonoFont, fontSize = 11.sp)
        }
        Text(detail, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp)
        if (progress != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(AskaColors.Magenta.copy(alpha = 0.3f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(AskaColors.Pink)
                )
            }
            progressLabel?.let { Text(it, color = AskaColors.HotPink, fontFamily = MonoFont, fontSize = 11.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun BayButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontFamily = HudFont,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .hudPanel(borderColor = color.copy(alpha = 0.8f), fill = Color.Transparent, cut = 6.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Voice packs: system speech run through As//ka's effects. */
@Composable
private fun VoiceBay(voice: AskaVoice, mobileData: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = AskaColors.Pink.copy(alpha = 0.35f), fill = AskaColors.Panel)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VOIX", color = AskaColors.Pink, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            BayButton(if (voice.enabled) "LECTURE AUTO ●" else "LECTURE AUTO ○", if (voice.enabled) AskaColors.Pink else Cp.Dim) { voice.toggle() }
        }
        NeuralVoice(voice, mobileData)
        AskaVoice.Pack.entries.forEach { pack ->
            val selected = pack == voice.pack
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { voice.select(pack) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (selected) "[■]" else "[ ]", color = AskaColors.Pink, fontFamily = MonoFont, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(pack.label, color = if (selected) AskaColors.HotPink else Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(pack.detail, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp)
                }
                Text(
                    "▶",
                    color = AskaColors.Pink,
                    fontFamily = MonoFont,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable {
                            voice.select(pack)
                            voice.speak("Connexion établie. Ici As//ka. Je veille sur ton deck.", force = true)
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        voice.problem?.let { Text(it, color = Cp.Red, fontFamily = HudFont, fontSize = 12.sp) }
        Text(
            "Hors ligne, filtrée façon IA. Les autres voix utilisent la synthèse vocale du téléphone. Touche une réponse d'As//ka pour la réentendre.",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 11.sp,
        )
    }
}

/** Her neural voice: download (once), unpack, delete. */
@Composable
private fun NeuralVoice(voice: AskaVoice, mobileData: Boolean) {
    val context = LocalContext.current
    var download by remember { mutableStateOf(AskaPiper.pollDownload(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    val unpacking = AskaPiper.progress

    // An unpacking ended (maybe while this screen was closed): her voice picks it up.
    val seen = remember { AskaPiper.finished }
    LaunchedEffect(AskaPiper.finished) {
        if (AskaPiper.finished == seen) return@LaunchedEffect
        voice.neuralChanged()
        if (voice.neural) {
            voice.select(AskaVoice.Pack.Ashka)
            voice.speak("Voilà ma vraie voix. Tu me reconnais, runner ?", force = true)
        }
    }
    // A download finished while the panel was closed: unpack it now.
    LaunchedEffect(Unit) {
        if (download == null && !voice.neural && unpacking == null && AskaPiper.hasArchive(context)) AskaPiper.installInBackground(context)
    }
    LaunchedEffect(download != null) {
        while (download != null) {
            delay(1000)
            val now = AskaPiper.pollDownload(context)
            when (now?.third) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    AskaPiper.clearDownload(context)
                    download = null
                    AskaPiper.installInBackground(context)
                }
                DownloadManager.STATUS_FAILED -> {
                    error = "Téléchargement échoué. Vérifie la connexion et l'espace libre, puis réessaie."
                    AskaPiper.cancelDownload(context)
                    download = null
                }
                else -> download = now
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = AskaColors.HotPink.copy(alpha = 0.6f), fill = AskaColors.Void.copy(alpha = 0.6f), cut = 8.dp)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (voice.neural) "VOIX NEURONALE ● ACTIVE" else "VOIX NEURONALE",
                    color = AskaColors.HotPink,
                    fontFamily = HudFont,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (voice.neural) "Sa vraie voix, pour le pack AS//KA. Hors ligne."
                    else "Une vraie voix française, naturelle, hors ligne. ${AskaPiper.SIZE_MB} Mo, une seule fois.",
                    color = Cp.Dim,
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                )
            }
            val d = download
            when {
                unpacking != null -> Unit
                d != null -> BayButton("ANNULER", Cp.Red) {
                    AskaPiper.cancelDownload(context)
                    download = null
                }
                voice.neural -> BayButton("SUPPRIMER", Cp.Dim) {
                    AskaPiper.delete(context)
                    voice.neuralChanged()
                }
                else -> BayButton("TÉLÉCHARGER", AskaColors.Pink) {
                    error = null
                    AskaPiper.startDownload(context, mobileData)
                    download = AskaPiper.pollDownload(context)
                }
            }
        }
        val d = download
        val progress = unpacking ?: d?.let { if (it.second > 0) it.first.toFloat() / it.second else 0f }
        if (progress != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(AskaColors.Pink.copy(alpha = 0.15f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(AskaColors.HotPink)
                )
            }
            Text(
                when {
                    unpacking != null -> "INSTALLATION… ${(unpacking * 100).toInt()} % (environ une minute)"
                    d?.third == DownloadManager.STATUS_PAUSED -> "EN PAUSE (attente du Wi-Fi ou du réseau)"
                    d?.third == DownloadManager.STATUS_PENDING -> "EN ATTENTE…"
                    else -> "${(d?.first ?: 0L) / 1_000_000} / ${(d?.second ?: 0L).coerceAtLeast(0L) / 1_000_000} Mo"
                },
                color = AskaColors.Pink,
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
        (error ?: AskaPiper.error)?.let { Text(it, color = Cp.Red, fontFamily = HudFont, fontSize = 12.sp) }
    }
}

/** Her face: a picture imported from the phone, shown as a hologram. */
@Composable
private fun PortraitBay() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AskaPortrait.import(context, uri) } }
            error = result.exceptionOrNull()?.let { "Import impossible : ${it.message}" }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = AskaColors.Pink.copy(alpha = 0.35f), fill = AskaColors.Panel)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AskaAvatar(Modifier.size(72.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PORTRAIT", color = AskaColors.Pink, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BayButton("IMPORTER UNE IMAGE", Cp.Cyan) { picker.launch("image/*") }
                    if (AskaPortrait.image != null) BayButton("RETIRER", Cp.Dim) { AskaPortrait.clear(context) }
                }
            }
        }
        error?.let { Text(it, color = Cp.Red, fontFamily = HudFont, fontSize = 12.sp) }
        Text(
            "Une image carrée (512 px ou plus), visage centré, fond sombre ou transparent. Elle remplace le sigle et s'affiche en hologramme.",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 11.sp,
        )
    }
}
