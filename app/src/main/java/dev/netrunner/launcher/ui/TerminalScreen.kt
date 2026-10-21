package dev.netrunner.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.terminal.Terminal
import dev.netrunner.launcher.terminal.Tone

private val QuickCommands = listOf("help", "scan", "wifi", "claude", "ghost", "speedtest", "theme", "calc", "timer 5m", "torch", "call", "keyboard", "ports", "ping 1.1.1.1", "ip", "sysinfo", "battery", "apps", "stop", "clear")

@Composable
fun TerminalScreen(terminal: Terminal, onClose: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val lines = terminal.lines

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    fun send(command: String) {
        terminal.submit(command)
        input = ""
    }

    Box(Modifier.fillMaxSize()) {
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
            Text(
                "// TERMINAL — CYBERDECK",
                color = Cp.Yellow,
                fontFamily = HudFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (terminal.busy) {
                Text("● EXEC", color = Cp.Red, fontFamily = HudFont, fontSize = 12.sp)
            }
            Text(
                "[ X ]",
                color = Cp.Red,
                fontFamily = HudFont,
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
                .background(Cp.Yellow.copy(alpha = 0.6f))
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            itemsIndexed(lines) { _, line ->
                Text(
                    line.text,
                    color = when (line.tone) {
                        Tone.Command -> Cp.Yellow
                        Tone.Ok -> Cp.Cyan
                        Tone.Warn -> Cp.Yellow
                        Tone.Error -> Cp.Red
                        Tone.Dim -> Cp.Dim
                        Tone.Normal -> Cp.Text
                    },
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickCommands.forEach { command ->
                Text(
                    command,
                    color = Cp.Cyan,
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), cut = 6.dp)
                        .clickable { send(command) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 14.sp),
            cursorBrush = SolidColor(Cp.Yellow),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { send(input) }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Yellow)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$ ", color = Cp.Yellow, fontFamily = HudFont, fontSize = 14.sp)
                    Box(Modifier.weight(1f)) {
                        if (input.isEmpty()) {
                            Text("commande_", color = Cp.Dim, fontFamily = HudFont, fontSize = 14.sp)
                        }
                        inner()
                    }
                }
            },
        )
        Spacer(Modifier.height(4.dp))
    }
    terminal.qr?.let { text -> QrOverlay(text, onClose = { terminal.qr = null }) }
    }
}
