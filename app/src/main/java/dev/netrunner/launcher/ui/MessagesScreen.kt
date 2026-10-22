@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.netrunner.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
    var composing by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version++ }

    DisposableEffect(version) {
        val stop = SmsStore.observe(context) { version++ }
        onDispose { stop() }
    }
    BackHandler(enabled = open != null || composing) {
        if (open != null) version++ // back on the list: refresh the unread counters
        open = null
        composing = false
    }

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
            onClose = {
                when {
                    thread != null -> {
                        open = null
                        version++
                    }
                    composing -> composing = false
                    else -> onClose()
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        when {
            !SmsStore.canRead(context) -> PermissionCard { ask.launch(SmsStore.permissions) }
            thread == null && composing -> NewMessage { t ->
                composing = false
                open = t
            }
            thread == null -> ThreadList(version, onChanged = { version++ }, onNew = { composing = true }) {
                UiSound.play(context, UiSound.Cue.Open)
                open = it
            }
            else -> Conversation(
                thread,
                version,
                onSent = { version++ },
                onResolved = { open = it },
                onHide = {
                    SmsStore.hide(context, thread.threadId, thread.date)
                    open = null
                    version++
                },
            )
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
private fun ThreadList(version: Int, onChanged: () -> Unit, onNew: () -> Unit, onOpen: (SmsThread) -> Unit) {
    val context = LocalContext.current
    var showHidden by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<Long?>(null) }
    val all by produceState(emptyList<SmsThread>(), version) {
        value = withContext(Dispatchers.IO) { SmsStore.threads(context, includeHidden = true) }
    }
    val hiddenCount = all.count { SmsStore.isHidden(context, it) }
    val q = query.trim()
    val threads = all
        .filter { SmsStore.isHidden(context, it) == showHidden }
        .filter { q.isEmpty() || (it.name ?: "").contains(q, true) || it.address.contains(q) || it.snippet.contains(q, true) }
    val unreadTotal = all.filter { !SmsStore.isHidden(context, it) }.sumOf { it.unread }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("+ NOUVEAU", Cp.Yellow, onClick = onNew)
            if (unreadTotal > 0) {
                SmallAction("TOUT LU ✓", Cp.Cyan) {
                    SmsStore.markAllSeen(context, all.filter { it.unread > 0 })
                    onChanged()
                }
            }
            Spacer(Modifier.weight(1f))
            if (hiddenCount > 0 || showHidden) {
                SmallAction(if (showHidden) "◄ CANAUX" else "MASQUÉS ($hiddenCount)", Cp.Dim) { showHidden = !showHidden }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (all.size > 6) {
            SearchField(query, { query = it }, "> rechercher un nom, un numéro, un mot…")
            Spacer(Modifier.height(8.dp))
        }
        if (threads.isEmpty()) {
            Text(
                when {
                    q.isNotEmpty() -> "> AUCUN RÉSULTAT"
                    showHidden -> "> AUCUN CANAL MASQUÉ"
                    else -> "> AUCUN CANAL ACTIF"
                },
                color = Cp.Dim,
                fontFamily = MonoFont,
                fontSize = 13.sp,
            )
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(threads, key = { it.threadId }) { t ->
                val hot = t.unread > 0
                Column(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = if (hot) Cp.Yellow else Cp.Cyan.copy(alpha = 0.45f), cut = 10.dp)
                        .combinedClickable(
                            onClick = { if (menu == t.threadId) menu = null else onOpen(t) },
                            onLongClick = {
                                UiSound.play(context, UiSound.Cue.Tap)
                                menu = if (menu == t.threadId) null else t.threadId
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                    if (menu == t.threadId) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (hot) {
                                SmallAction("LU ✓", Cp.Cyan) {
                                    SmsStore.markSeen(context, t.threadId, t.date)
                                    menu = null
                                    onChanged()
                                }
                            }
                            if (showHidden) {
                                SmallAction("RÉAFFICHER", Cp.Cyan) {
                                    SmsStore.unhide(context, t.threadId)
                                    menu = null
                                    onChanged()
                                }
                            } else {
                                SmallAction("MASQUER", Cp.Red) {
                                    SmsStore.hide(context, t.threadId, t.date)
                                    menu = null
                                    onChanged()
                                }
                            }
                            SmallAction("APPELER", Cp.Yellow) { dial(context, t.address) }
                            SmallAction("APPLI SMS", Cp.Dim) { openInSmsApp(context, t.address) }
                        }
                    }
                }
            }
            item {
                Text(
                    "Appui long sur un canal : marquer lu, masquer, appeler. Pour supprimer pour de bon, passe par ton appli SMS (seule autorisée par Android).",
                    color = Cp.Dim,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontFamily = HudFont,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .hudPanel(borderColor = color.copy(alpha = 0.6f), fill = color.copy(alpha = 0.08f), cut = 6.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, hint: String, onDone: () -> Unit = {}) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp),
        cursorBrush = SolidColor(Cp.Yellow),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f), cut = 8.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(hint, color = Cp.Dim, fontFamily = HudFont, fontSize = 15.sp)
            inner()
        },
    )
}

private fun dial(context: android.content.Context, number: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(context, dev.netrunner.launcher.call.DialerActivity::class.java)
                .setData(android.net.Uri.fromParts("tel", number, null))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** The default SMS app on this conversation: the place for a real deletion. */
private fun openInSmsApp(context: android.content.Context, number: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.fromParts("smsto", number, null))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { android.widget.Toast.makeText(context, "Aucune appli SMS trouvée", android.widget.Toast.LENGTH_SHORT).show() }
}

/** Picks a contact (or types a number) to start a conversation. */
@Composable
private fun NewMessage(onStart: (SmsThread) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val contacts by produceState(emptyList<dev.netrunner.launcher.call.PhoneContact>()) {
        value = withContext(Dispatchers.IO) { dev.netrunner.launcher.call.Contacts.all(context) }
    }
    val q = query.trim()
    val digits = q.filter { it.isDigit() || it == '+' }
    val matches = if (q.isEmpty()) contacts.take(30) else contacts.filter {
        it.name.contains(q, true) || (digits.length >= 3 && it.number.filter(Char::isDigit).contains(digits.filter(Char::isDigit)))
    }.take(30)
    fun start(number: String, name: String?) {
        // Reuse the existing conversation with this number when there is one.
        val existing = SmsStore.threadFor(context, number)
        onStart(existing ?: SmsThread(-1L, number, name, "", 0L, 0, false))
    }
    Column {
        SearchField(query, { query = it }, "> nom ou numéro du destinataire") {
            if (digits.length >= 3 && matches.isEmpty()) start(digits, null)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (digits.length >= 3 && q.all { it.isDigit() || it in "+ .-()" }) {
                item {
                    Text(
                        "► ENVOYER AU $digits",
                        color = Cp.Yellow,
                        fontFamily = HudFont,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.6f), cut = 8.dp)
                            .clickable { start(digits, null) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            items(matches) { c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.4f), cut = 8.dp)
                        .clickable { start(c.number, c.name) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Portrait(c.name, false)
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(c.name.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.number, color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
                    }
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
private fun Conversation(thread: SmsThread, version: Int, onSent: () -> Unit, onResolved: (SmsThread) -> Unit, onHide: () -> Unit) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val messages by produceState(emptyList<SmsMessage>(), thread.threadId, version) {
        value = withContext(Dispatchers.IO) { if (thread.threadId < 0) emptyList() else SmsStore.messages(context, thread.threadId) }
    }
    // A new conversation gets its thread once Android has filed the first message.
    LaunchedEffect(thread.threadId, version) {
        if (thread.threadId < 0 && version > 0) {
            withContext(Dispatchers.IO) { SmsStore.threadFor(context, thread.address) }?.let { onResolved(it.copy(name = thread.name ?: it.name)) }
        }
    }
    // Opening the conversation reads it: the unread counter goes away.
    LaunchedEffect(messages) {
        messages.lastOrNull()?.let { SmsStore.markSeen(context, thread.threadId, it.date) }
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
            Column(Modifier.weight(1f)) {
                Text((thread.name ?: thread.address).uppercase(), color = Cp.Cyan, fontFamily = DisplayFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${thread.address} · LIAISON SMS", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
            }
            Text("☎", color = Cp.Yellow, fontSize = 18.sp, modifier = Modifier.clickable { dial(context, thread.address) }.padding(8.dp))
            if (thread.threadId >= 0) {
                Text("MASQUER", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onHide).padding(8.dp))
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
                item(key = m.id) {
                    Bubble(m) {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.body))
                        android.widget.Toast.makeText(context, "Message copié", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
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
private fun Bubble(m: SmsMessage, onCopy: () -> Unit) {
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
                .combinedClickable(onClick = {}, onLongClick = onCopy)
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
