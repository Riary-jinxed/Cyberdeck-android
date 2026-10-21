package dev.netrunner.launcher.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.netrunner.launcher.notify.FeedItem
import dev.netrunner.launcher.notify.FeedKind
import dev.netrunner.launcher.notify.NotificationHub
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HourFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun kindTag(kind: FeedKind): Pair<String, Color> = when (kind) {
    FeedKind.Message -> "MSG" to Cp.Cyan
    FeedKind.Mail -> "MAIL" to Cp.Yellow
    FeedKind.Call -> "CALL" to Cp.Red
    FeedKind.Other -> "SYS" to Cp.Dim
}

private fun hourOf(time: Long): String =
    Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(HourFormat)

/** Explains how to grant notification access, including Android's "restricted settings" step for sideloaded apps. */
@Composable
fun FeedAccessCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Red, fill = Cp.Red.copy(alpha = 0.08f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("FLUX ENTRANT HORS LIGNE", color = Cp.Red, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(
            "Autorise Netrunner à lire les notifications pour afficher messages, WhatsApp, mails et le widget musique.\n" +
                "Si l'option est grisée : Infos de l'appli › ⋮ › « Autoriser les paramètres restreints », puis réessaie.",
            color = Cp.Text,
            fontFamily = HudFont,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallHudButton("[ AUTORISER ]", Cp.Yellow) { NotificationHub.openAccessSettings(context) }
            SmallHudButton("[ INFOS APPLI ]", Cp.Cyan) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}

@Composable
fun SmallHudButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontFamily = HudFont,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .hudPanel(borderColor = color, fill = color.copy(alpha = 0.1f), cut = 6.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** The last few notifications on the home HUD. */
@Composable
fun FeedPreview(items: List<FeedItem>, onOpenAll: () -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "// FLUX ENTRANT",
            color = Cp.Yellow,
            fontFamily = HudFont,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            "TOUT VOIR (${items.size}) ►",
            color = Cp.Cyan,
            fontFamily = HudFont,
            fontSize = 11.sp,
            modifier = Modifier
                .clickable(onClick = onOpenAll)
                .padding(4.dp),
        )
    }
    Spacer(Modifier.height(6.dp))
    if (items.isEmpty()) {
        Text("> aucun signal entrant", color = Cp.Dim, fontFamily = HudFont, fontSize = 12.sp)
    }
    items.take(3).forEach { item ->
        FeedRow(item, compact = true, onOpen = { NotificationHub.open(context, item) })
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun FeedRow(
    item: FeedItem,
    compact: Boolean,
    onOpen: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val (tag, color) = kindTag(item.kind)
    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = color.copy(alpha = 0.6f), cut = 8.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(if (compact) 26.dp else 32.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("[$tag] ", color = color, fontFamily = HudFont, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    item.appLabel.uppercase(),
                    color = Cp.Dim,
                    fontFamily = HudFont,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(hourOf(item.postTime), color = Cp.Dim, fontFamily = HudFont, fontSize = 10.sp)
            }
            Text(
                item.title,
                color = Cp.Text,
                fontFamily = HudFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.text,
                color = Cp.Text.copy(alpha = 0.8f),
                fontFamily = HudFont,
                fontSize = 12.sp,
                maxLines = if (compact) 1 else 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/** Full-screen notification feed with inline replies. */
@Composable
fun FeedScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val items by NotificationHub.items.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<FeedKind?>(null) }
    var replyingTo by remember { mutableStateOf<String?>(null) }
    val shown = items.filter { filter == null || it.kind == filter }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.98f))
            .scanlines()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        PanelHeader(title = "// FLUX ENTRANT : ${items.size}", accent = Cp.Yellow, onClose = onClose) {
            Text(
                "PURGER",
                color = Cp.Red,
                fontFamily = HudFont,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable { NotificationHub.dismissAll() }
                    .padding(8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (!NotificationHub.isAccessGranted(context)) {
            FeedAccessCard()
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(null to "TOUT", FeedKind.Message to "MSG", FeedKind.Mail to "MAIL", FeedKind.Call to "CALL", FeedKind.Other to "SYS")
                .forEach { (kind, label) ->
                    val selected = filter == kind
                    Text(
                        label,
                        color = if (selected) Cp.Background else Cp.Cyan,
                        fontFamily = HudFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .hudPanel(borderColor = Cp.Cyan, fill = if (selected) Cp.Cyan else Cp.Panel, cut = 6.dp)
                            .clickable { filter = kind }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shown.isEmpty()) {
                item { Text("> aucun signal", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp) }
            }
            items(shown, key = { it.key }) { item ->
                Column {
                    FeedRow(
                        item,
                        compact = false,
                        onOpen = { NotificationHub.open(context, item) },
                        trailing = {
                            Column(horizontalAlignment = Alignment.End) {
                                if (item.replyAction != null) {
                                    Text(
                                        "↩",
                                        color = Cp.Cyan,
                                        fontSize = 18.sp,
                                        modifier = Modifier
                                            .clickable { replyingTo = if (replyingTo == item.key) null else item.key }
                                            .padding(6.dp),
                                    )
                                }
                                if (item.clearable) {
                                    Text(
                                        "✕",
                                        color = Cp.Red,
                                        fontSize = 16.sp,
                                        modifier = Modifier
                                            .clickable { NotificationHub.dismiss(item) }
                                            .padding(6.dp),
                                    )
                                }
                            }
                        },
                    )
                    if (replyingTo == item.key) {
                        ReplyBox(onSend = { text ->
                            if (NotificationHub.reply(context, item, text)) replyingTo = null
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyBox(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(color = Cp.Text, fontFamily = HudFont, fontSize = 13.sp),
            cursorBrush = SolidColor(Cp.Yellow),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend(text) }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Cyan)
                        .padding(10.dp)
                ) {
                    if (text.isEmpty()) Text("RÉPONSE_", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        SmallHudButton("▶", Cp.Yellow) { if (text.isNotBlank()) onSend(text) }
    }
}
