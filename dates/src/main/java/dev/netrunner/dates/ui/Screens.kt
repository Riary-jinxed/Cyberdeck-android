package dev.netrunner.dates.ui

import android.app.DownloadManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.dates.date.AskaDate
import dev.netrunner.dates.date.DateBrain
import dev.netrunner.dates.engine.Models
import dev.netrunner.deck.Cp
import dev.netrunner.deck.FactionSection
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.MonoFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.UpdateSection
import dev.netrunner.deck.Updater
import dev.netrunner.deck.hudPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Her colors, the same as in the launcher. */
object Pink {
    val Neon = Color(0xFFFF2E97)
    val Hot = Color(0xFFFF6EC7)
    val Void = Color(0xFF0A0509)
    val Panel = Color(0xFF150A12)
}

/** Releases tagged "dates-v…", apart from the other deck apps. */
val DatesUpdater = Updater("dates-v")

/** Where to take her: each scene opens as their bond grows, in the launcher. */
@Composable
fun ScenesTab(brain: DateBrain) {
    val profile = brain.profile
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("RENDEZ-VOUS AVEC AS//KA", Pink.Neon)
        if (profile == null) {
            Text(
                "As//ka vit dans le launcher Netrunner : installe-le ou mets-le à jour (même source que cette appli), puis reviens ici.",
                color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp,
            )
            return@Column
        }
        Text(
            "LIEN ${profile.bond.toInt()} / 100" + if (profile.adult) " · 18+ ACTIVÉ DANS LE LAUNCHER" else "",
            color = Pink.Hot, fontFamily = MonoFont, fontSize = 12.sp,
        )
        Text(
            if (AskaDate.Scene.entries.first().unlocked(profile.bond)) "Où l'emmènes-tu ce soir, ${profile.handle} ?"
            else "Elle est encore trop timide… Câlins, macarons et parties dans le launcher la rapprocheront de toi.",
            color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp,
        )
        AskaDate.Scene.entries.forEach { scene ->
            val open = scene.unlocked(profile.bond)
            val glow = Color(scene.glow)
            Row(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = if (open) glow.copy(alpha = 0.8f) else Cp.Dim.copy(alpha = 0.4f), fill = Color(scene.sky).copy(alpha = 0.9f), cut = 8.dp)
                    .clickable(enabled = open) { brain.start(scene) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(scene.glyph, color = if (open) glow else Cp.Dim, fontFamily = MonoFont, fontSize = 18.sp)
                Spacer(Modifier.width(12.dp))
                Text(scene.label, color = if (open) Cp.Text else Cp.Dim, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (open) "◢ Y ALLER" else "◇ LIEN ${scene.bond}", color = if (open) glow else Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
            }
        }
    }
}

/** The date itself: the scene's sky, what they say, and the way home. */
@Composable
fun DateScreen(brain: DateBrain, scene: AskaDate.Scene, onEnd: () -> Unit) {
    val glow = Color(scene.glow)
    val sky = Color(scene.sky)
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val thinking = brain.state == DateBrain.State.Thinking
    LaunchedEffect(brain.lines.size, brain.lines.lastOrNull()?.text?.length) {
        if (brain.lines.isNotEmpty()) listState.scrollToItem(brain.lines.size - 1)
    }
    fun send(text: String) {
        if (text.isBlank() || thinking) return
        brain.send(text)
        input = ""
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(sky.copy(alpha = 0.95f), sky.copy(alpha = 0.7f), glow.copy(alpha = 0.15f))))
            .padding(horizontal = 12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .hudPanel(borderColor = glow.copy(alpha = 0.7f), fill = sky.copy(alpha = 0.85f), cut = 8.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("♥ ${scene.glyph} ${scene.label}", color = glow, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(statusLabel(brain), color = Cp.Dim, fontFamily = MonoFont, fontSize = 10.sp)
            }
            Text(
                "RENTRER ◂",
                color = Cp.Text, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .hudPanel(borderColor = glow.copy(alpha = 0.6f), fill = Color.Transparent, cut = 5.dp)
                    .clickable(onClick = onEnd)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(brain.lines, key = { it.id }) { line -> Bubble(line, brain.profile?.handle ?: "V") }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (if (AskaDate.photo(scene) != null) scene.prompts + "Une photo de toi ?" else scene.prompts).forEach { prompt ->
                Text(
                    prompt,
                    color = Pink.Hot, fontFamily = HudFont, fontSize = 12.sp,
                    modifier = Modifier
                        .hudPanel(borderColor = Pink.Neon.copy(alpha = 0.5f), fill = Pink.Panel, cut = 6.dp)
                        .clickable(enabled = !thinking) { send(prompt) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                maxLines = 5,
                textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp),
                cursorBrush = SolidColor(Pink.Neon),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                modifier = Modifier
                    .weight(1f)
                    .hudPanel(borderColor = Pink.Neon.copy(alpha = 0.6f), fill = Pink.Panel, cut = 8.dp)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                decorationBox = { field ->
                    if (input.isEmpty()) Text("Dis-lui… (*geste* entre astérisques)", color = Cp.Dim, fontFamily = HudFont, fontSize = 15.sp)
                    field()
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (thinking) "■ STOP" else "◢ ENVOI",
                color = Pink.Void, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .hudPanel(borderColor = Pink.Neon, fill = Pink.Neon, cut = 8.dp)
                    .clickable { if (thinking) brain.stop() else send(input) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            )
        }
    }
}

private fun statusLabel(brain: DateBrain): String = when (brain.state) {
    DateBrain.State.NoModel -> "NOYAU ABSENT · ONGLET NOYAU"
    DateBrain.State.Asleep -> "EN VEILLE · 100 % LOCAL"
    DateBrain.State.Waking -> "ÉVEIL DU NOYAU…"
    DateBrain.State.Ready -> "EN LIGNE · ${brain.backend ?: "CPU"} · 100 % LOCAL"
    DateBrain.State.Thinking -> "ELLE RÉFLÉCHIT…"
}

/** One line of the date: her gestures, between asterisks, in pink italics. */
@Composable
private fun Bubble(line: DateBrain.Line, handle: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .hudPanel(
                    borderColor = if (line.fromUser) Cp.Cyan.copy(alpha = 0.5f) else Pink.Neon.copy(alpha = 0.75f),
                    fill = if (line.fromUser) Cp.Panel.copy(alpha = 0.9f) else Pink.Panel.copy(alpha = 0.92f),
                    cut = 8.dp,
                )
                .padding(10.dp),
        ) {
            Text(if (line.fromUser) "${handle.uppercase()} //" else "AS//KA ◆", color = if (line.fromUser) Cp.Cyan else Pink.Neon, fontFamily = MonoFont, fontSize = 10.sp)
            if (line.text.isEmpty() && !line.done) {
                Text("…", color = Pink.Hot, fontFamily = HudFont, fontSize = 15.sp)
            } else {
                Text(styled(line.text + if (line.done) "" else " ▌"), color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp, lineHeight = 19.sp)
            }
            line.photo?.let { Photo(it) }
        }
    }
}

private fun styled(text: String) = buildAnnotatedString {
    text.split('*').forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(color = Pink.Hot, fontStyle = FontStyle.Italic)) { append(part) } else append(part)
    }
}

@Composable
private fun Photo(@DrawableRes photo: Int) {
    Spacer(Modifier.height(8.dp))
    Image(
        painterResource(photo),
        contentDescription = "Photo d'As//ka",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().clip(dev.netrunner.deck.hudShape(8.dp)),
    )
}

/** Her model for the dates: downloaded or imported once, kept apart from the launcher's. */
@Composable
fun ModelTab(brain: DateBrain) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var download by remember { mutableStateOf(Models.pollDownload(context)) }
    var importProgress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var mobileData by remember { mutableStateOf(false) }

    fun changed() {
        revision++
        brain.modelChanged()
    }

    LaunchedEffect(download != null) {
        while (download != null) {
            delay(1000)
            val now = Models.pollDownload(context)
            when (now?.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    message = "Noyau installé."
                    download = null
                    changed()
                }
                DownloadManager.STATUS_FAILED -> {
                    message = "Téléchargement échoué. Vérifie la connexion et l'espace libre, puis réessaie."
                    Models.cancelDownload(context)
                    download = null
                }
                else -> download = now
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importProgress = 0f
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Models.import(context, uri) { importProgress = it } } }
            importProgress = null
            message = result.fold({ "Modèle importé et activé." }, { "Import impossible : ${it.message}" })
            if (result.isSuccess) changed()
        }
    }

    val active = remember(revision) { Models.active(context) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("NOYAU DES RENDEZ-VOUS", Pink.Neon)
        Text(
            "Les rendez-vous ont leur propre modèle, chargé seulement pendant la scène. Tout reste sur le téléphone. " +
                "Tu peux importer un modèle fait pour le jeu de rôle, au format .litertlm.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp,
        )
        message?.let { Text(it, color = Pink.Hot, fontFamily = HudFont, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Models.catalog.forEach { model ->
            val installed = remember(revision) { Models.isInstalled(context, model.id) }
            val downloading = download?.takeIf { it.modelId == model.id }
            ModelCard(
                title = model.label,
                detail = "${model.tagline}\n${model.sizeMb} Mo" + (downloading?.let { " · ${it.done / 1_000_000} / ${it.total.coerceAtLeast(0) / 1_000_000} Mo" } ?: ""),
                active = active == model.id,
            ) {
                when {
                    downloading != null -> HudButton("ANNULER", color = Cp.Red, size = 12.sp) {
                        Models.cancelDownload(context)
                        download = null
                    }
                    installed -> {
                        if (active != model.id) HudButton("UTILISER", color = Pink.Neon, size = 12.sp) {
                            Models.select(context, model.id)
                            changed()
                        }
                        HudButton("SUPPRIMER", color = Cp.Dim, size = 12.sp) {
                            Models.delete(context, model.id)
                            changed()
                        }
                    }
                    download == null -> HudButton("TÉLÉCHARGER", color = Pink.Neon, size = 12.sp) {
                        message = null
                        Models.startDownload(context, model, mobileData)
                        download = Models.pollDownload(context)
                    }
                }
            }
        }
        if (remember(revision) { Models.isInstalled(context, Models.CUSTOM_ID) }) {
            ModelCard(title = Models.labelOf(context, Models.CUSTOM_ID), detail = "Fichier importé depuis le téléphone.", active = active == Models.CUSTOM_ID) {
                if (active != Models.CUSTOM_ID) HudButton("UTILISER", color = Pink.Neon, size = 12.sp) {
                    Models.select(context, Models.CUSTOM_ID)
                    changed()
                }
                HudButton("SUPPRIMER", color = Cp.Dim, size = 12.sp) {
                    Models.delete(context, Models.CUSTOM_ID)
                    changed()
                }
            }
        }
        Row(Modifier.fillMaxWidth().clickable { mobileData = !mobileData }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (mobileData) "[■]" else "[ ]", color = Pink.Neon, fontFamily = MonoFont, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text("Autoriser le téléchargement en données mobiles (sinon Wi-Fi uniquement)", color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp)
        }
        val progress = importProgress
        if (progress != null) {
            Text("IMPORT… ${(progress * 100).toInt()} %", color = Pink.Hot, fontFamily = MonoFont, fontSize = 12.sp)
        } else {
            HudButton("IMPORTER UN FICHIER .litertlm", color = Cp.Cyan, modifier = Modifier.fillMaxWidth()) { picker.launch(arrayOf("*/*")) }
        }
        Text("Autres modèles compatibles : huggingface.co/litert-community (fichiers .litertlm).", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
    }
}

@Composable
private fun ModelCard(title: String, detail: String, active: Boolean, actions: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = if (active) Pink.Neon else Pink.Neon.copy(alpha = 0.35f), fill = Pink.Panel, cut = 8.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text((if (active) "◆ " else "") + title, color = if (active) Pink.Neon else Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
fun SettingsTab() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("À SAVOIR", Pink.Neon)
        Text(
            "• Le niveau de lien, ce qu'As//ka sait de toi et ses souvenirs viennent du launcher, et chaque rendez-vous y retourne.\n" +
                "• Le mode 18+ s'active dans le launcher : Réglages › AS//KA › Rendez-vous adultes.\n" +
                "• Écris tes gestes entre astérisques : *prend sa main*.\n" +
                "• Rien ne quitte le téléphone.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp,
        )
        FactionSection()
        UpdateSection(DatesUpdater)
    }
}
