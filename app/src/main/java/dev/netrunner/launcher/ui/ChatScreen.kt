package dev.netrunner.launcher.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.chat.ChatMessage
import dev.netrunner.launcher.chat.ChatSession

@Composable
fun ChatScreen(session: ChatSession, onClose: () -> Unit) {
    var settings by remember { mutableStateOf(false) }
    val needsKey = session.apiKey.isBlank()

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.98f))
            .scanlines()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mascot(session.mood, size = 52.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("GLITCH", color = Cp.Yellow, fontFamily = HudFont, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text(
                    when (session.mood) {
                        MascotMood.Thinking -> "ANALYSE EN COURS..."
                        MascotMood.Talking -> "TRANSMISSION ▮▮▮"
                        MascotMood.Error -> "LIAISON PERTURBÉE"
                        MascotMood.Idle -> if (needsKey) "HORS LIGNE : CLÉ REQUISE" else "EN LIGNE // IA DU CYBERDECK"
                    },
                    color = if (session.mood == MascotMood.Error || needsKey) Cp.Red else Cp.Cyan,
                    fontFamily = HudFont,
                    fontSize = 11.sp,
                )
            }
            if (!needsKey) {
                HeaderAction("NEW") { session.reset() }
                HeaderAction("⚙") { settings = !settings }
            }
            HeaderAction("[ X ]", color = Cp.Red, onClick = onClose)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Cp.Yellow.copy(alpha = 0.6f))
        )
        Spacer(Modifier.height(8.dp))

        when {
            needsKey -> KeySetup(onSave = session::saveKey)
            settings -> KeySettings(
                onForget = {
                    session.forgetKey()
                    settings = false
                },
                onDone = { settings = false },
            )
            else -> Conversation(session, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeaderAction(label: String, color: androidx.compose.ui.graphics.Color = Cp.Cyan, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontFamily = HudFont,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
    )
}

@Composable
private fun Conversation(session: ChatSession, modifier: Modifier) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val messages = session.messages
    val lastLength = messages.lastOrNull()?.text?.length ?: 0

    LaunchedEffect(messages.size, lastLength) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex, scrollOffset = Int.MAX_VALUE / 2)
    }

    fun send() {
        if (input.isBlank() || session.busy) return
        session.send(input)
        input = ""
    }

    Column(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "> Liaison établie. Pose-moi une question, choom.",
                        color = Cp.Dim,
                        fontFamily = HudFont,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            itemsIndexed(messages) { index, message ->
                Bubble(message, streaming = session.busy && index == messages.lastIndex)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp),
                cursorBrush = SolidColor(Cp.Yellow),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .hudPanel(borderColor = Cp.Yellow)
                            .padding(10.dp)
                    ) {
                        Text("» ", color = Cp.Yellow, fontFamily = HudFont, fontSize = 14.sp)
                        Box(Modifier.weight(1f)) {
                            if (input.isEmpty()) Text("TRANSMETTRE_", color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp)
                            inner()
                        }
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            val busy = session.busy
            Text(
                if (busy) "■" else "▶",
                color = if (busy) Cp.Red else Cp.Yellow,
                fontSize = 20.sp,
                modifier = Modifier
                    .hudPanel(borderColor = if (busy) Cp.Red else Cp.Yellow, fill = Cp.Yellow.copy(alpha = 0.1f))
                    .clickable { if (busy) session.stop() else send() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage, streaming: Boolean) {
    val color = when {
        message.error -> Cp.Red
        message.fromUser -> Cp.Yellow
        else -> Cp.Cyan
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .hudPanel(borderColor = color.copy(alpha = 0.7f), cut = 8.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                if (message.fromUser) "» TOI" else "« GLITCH",
                color = color,
                fontFamily = HudFont,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message.text.ifEmpty { if (streaming) "" else "…" } + if (streaming) "█" else "",
                color = if (message.error) Cp.Red else Cp.Text,
                fontFamily = HudFont,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun KeySetup(onSave: (String) -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Yellow)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("CONNEXION AU NOYAU CLAUDE", color = Cp.Yellow, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            "Pour me parler, colle une clé API Anthropic. Elle reste uniquement sur ce téléphone.\n\n" +
                "1. Crée un compte sur console.anthropic.com\n" +
                "2. Ajoute un peu de crédit (Billing)\n" +
                "3. API Keys › Create Key, puis copie-la ici\n\n" +
                "L'API est facturée à l'usage, séparément d'un abonnement Claude.ai.",
            color = Cp.Text,
            fontFamily = HudFont,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Text(
            "[ OUVRIR LA CONSOLE ]",
            color = Cp.Cyan,
            fontFamily = HudFont,
            fontSize = 13.sp,
            modifier = Modifier
                .hudPanel(borderColor = Cp.Cyan)
                .clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        BasicTextField(
            value = key,
            onValueChange = { key = it },
            singleLine = true,
            textStyle = TextStyle(color = Cp.Cyan, fontFamily = HudFont, fontSize = 14.sp),
            cursorBrush = SolidColor(Cp.Yellow),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Cyan)
                        .padding(10.dp)
                ) {
                    if (key.isEmpty()) Text("sk-ant-..._", color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp)
                    inner()
                }
            },
        )
        Text(
            "[ CONNECTER ]",
            color = if (key.isBlank()) Cp.Dim else Cp.Yellow,
            fontFamily = HudFont,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .hudPanel(borderColor = Cp.Yellow, fill = Cp.Yellow.copy(alpha = 0.12f))
                .clickable(enabled = key.isNotBlank()) { onSave(key) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun KeySettings(onForget: () -> Unit, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("RÉGLAGES DE LIAISON", color = Cp.Cyan, fontFamily = HudFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("Modèle : Claude Opus 5.5", color = Cp.Text, fontFamily = HudFont, fontSize = 12.sp)
        Text("Clé API enregistrée sur ce téléphone.", color = Cp.Text, fontFamily = HudFont, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "[ OUBLIER LA CLÉ ]",
                color = Cp.Red,
                fontFamily = HudFont,
                fontSize = 13.sp,
                modifier = Modifier
                    .hudPanel(borderColor = Cp.Red)
                    .clickable(onClick = onForget)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Text(
                "[ RETOUR ]",
                color = Cp.Cyan,
                fontFamily = HudFont,
                fontSize = 13.sp,
                modifier = Modifier
                    .hudPanel(borderColor = Cp.Cyan)
                    .clickable(onClick = onDone)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
