package dev.netrunner.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.sms.SmsMessage
import dev.netrunner.launcher.sms.SmsStore
import dev.netrunner.launcher.sms.SmsThread
import dev.netrunner.launcher.system.UiSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** In-game style messenger: the contact list, then a conversation with dialogue-style quick replies. */
@Composable
fun MessagesScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<SmsThread?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version++ }

    DisposableEffect(version) {
        val stop = SmsStore.observe(context) { version++ }
        onDispose { stop() }
    }
    BackHandler(enabled = open != null) { open = null }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.98f))
            .scanlines()
            .systemBarsPadding()
            .displayCutoutPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        val thread = open
        PanelHeader(
            title = if (thread == null) "// MESSAGERIE CHIFFRÉE" else "// CANAL : ${(thread.name ?: thread.address).uppercase()}",
            accent = Cp.Yellow,
            onClose = { if (thread != null) open = null else onClose() },
        )
        Spacer(Modifier.height(8.dp))
        when {
            !SmsStore.canRead(context) -> PermissionCard { ask.launch(SmsStore.permissions) }
            thread == null -> ThreadList(version) {
                UiSound.play(context, UiSound.Cue.Open)
                open = it
            }
            else -> Conversation(thread, version, onSent = { version++ })
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Red)
            .clickable(onClick = onGrant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("ACCÈS SMS REFUSÉ", color = Cp.Red, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            "Touche ici pour autoriser la lecture et l'envoi des SMS. Ton appli SMS habituelle reste en place " +
                "(MMS, RCS, notifications) : Netrunner lit les mêmes conversations.",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ThreadList(version: Int, onOpen: (SmsThread) -> Unit) {
    val context = LocalContext.current
    val threads by produceState(emptyList<SmsThread>(), version) {
        value = withContext(Dispatchers.IO) { SmsStore.threads(context) }
    }
    if (threads.isEmpty()) {
        Text("> AUCUN CANAL ACTIF", color = Cp.Dim, fontFamily = MonoFont, fontSize = 13.sp)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(threads, key = { it.threadId }) { t ->
            val hot = t.unread > 0
            Row(
                Modifier
                    .fillMaxWidth()
                    .hudPanel(borderColor = if (hot) Cp.Yellow else Cp.Cyan.copy(alpha = 0.45f), cut = 10.dp)
                    .clickable { onOpen(t) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Portrait(t.name ?: t.address, hot)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (t.name ?: t.address).uppercase(),
                            color = if (hot) Cp.Yellow else Cp.Text,
                            fontFamily = HudFont,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(stamp(t.date), color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
                    }
                    Text(
                        (if (t.lastFromMe) "▸ " else "") + t.snippet.replace('\n', ' '),
                        color = if (hot) Cp.Text else Cp.Dim,
                        fontFamily = HudFont,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (hot) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "${t.unread}",
                        color = Cp.Background,
                        fontFamily = HudFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Cp.Yellow).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Portrait(name: String, hot: Boolean) {
    val initials = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
    Box(
        Modifier
            .size(40.dp)
            .hudPanel(borderColor = if (hot) Cp.Yellow else Cp.Cyan, fill = Cp.Cyan.copy(alpha = 0.08f), cut = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials.ifEmpty { "#" }, color = if (hot) Cp.Yellow else Cp.Cyan, fontFamily = DisplayFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

private val QuickReplies = listOf(
    "Reçu.",
    "J'arrive.",
    "Je te rappelle.",
    "Pas dispo, plus tard.",
    "OK 👍",
)

@Composable
private fun Conversation(thread: SmsThread, version: Int, onSent: () -> Unit) {
    val context = LocalContext.current
    val messages by produceState(emptyList<SmsMessage>(), thread.threadId, version) {
        value = withContext(Dispatchers.IO) { SmsStore.messages(context, thread.threadId) }
    }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.scrollToItem(messages.lastIndex) }
    var draft by remember(thread.threadId) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun send(text: String) {
        if (SmsStore.send(context, thread.address, text.trim())) {
            UiSound.play(context, UiSound.Cue.Confirm)
            draft = ""
            error = null
            onSent()
        } else {
            UiSound.play(context, UiSound.Cue.Error)
            error = "ÉCHEC DE TRANSMISSION : vérifie la permission d'envoi de SMS (HyperOS la demande à part)."
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Portrait(thread.name ?: thread.address, false)
            Spacer(Modifier.size(10.dp))
            Column {
                Text((thread.name ?: thread.address).uppercase(), color = Cp.Cyan, fontFamily = DisplayFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("${thread.address} · LIAISON SMS", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            var lastDay: LocalDate? = null
            messages.forEach { m ->
                val day = Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault()).toLocalDate()
                if (day != lastDay) {
                    lastDay = day
                    item(key = "d$day") {
                        Text(
                            "── ${day.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} ──",
                            color = Cp.Dim,
                            fontFamily = MonoFont,
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                item(key = m.id) { Bubble(m) }
            }
        }
        error?.let { Text(it, color = Cp.Red, fontFamily = HudFont, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)) }

        // Dialogue options, like the game's reply choices.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QuickReplies.forEachIndexed { i, reply ->
                Text(
                    "[${i + 1}] $reply",
                    color = Cp.Yellow,
                    fontFamily = HudFont,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.6f), fill = Cp.Yellow.copy(alpha = 0.08f), cut = 6.dp)
                        .clickable { send(reply) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp),
                cursorBrush = SolidColor(Cp.Yellow),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank()) send(draft) }),
                maxLines = 4,
                modifier = Modifier
                    .weight(1f)
                    .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), cut = 8.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text("> composer une transmission…", color = Cp.Dim, fontFamily = HudFont, fontSize = 15.sp)
                    inner()
                },
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "ENVOI ►",
                color = if (draft.isBlank()) Cp.Dim else Cp.Background,
                fontFamily = HudFont,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .hudPanel(borderColor = Cp.Yellow, fill = if (draft.isBlank()) Cp.Panel else Cp.Yellow, cut = 8.dp)
                    .clickable(enabled = draft.isNotBlank()) { send(draft) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun Bubble(m: SmsMessage) {
    val color = when {
        m.failed -> Cp.Red
        m.fromMe -> Cp.Yellow
        else -> Cp.Cyan
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromMe) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 290.dp)
                .hudPanel(borderColor = color.copy(alpha = 0.7f), fill = color.copy(alpha = 0.07f), cut = 8.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(m.body, color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp)
            Text(
                (if (m.failed) "✕ NON TRANSMIS · " else "") + Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                color = color.copy(alpha = 0.8f),
                fontFamily = MonoFont,
                fontSize = 10.sp,
                modifier = Modifier.align(if (m.fromMe) Alignment.End else Alignment.Start),
            )
        }
    }
}

private fun stamp(date: Long): String {
    val time = Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault())
    return if (time.toLocalDate() == LocalDate.now()) time.format(DateTimeFormatter.ofPattern("HH:mm"))
    else time.format(DateTimeFormatter.ofPattern("dd.MM"))
}
