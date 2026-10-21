package dev.netrunner.launcher.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.netrunner.launcher.AppEntry
import dev.netrunner.launcher.LauncherViewModel
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val SecondsFormat = DateTimeFormatter.ofPattern("ss")
private val DateFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

private val FeedLines = listOf(
    "> CONNEXION AU RÉSEAU......... OK",
    "> ICE DÉTECTÉ : AUCUN",
    "> PROTOCOLE DE BRÈCHE PRÊT",
    "> PUBS XIAOMI : NEUTRALISÉES",
    "> BIENVENUE À NIGHT CITY, NETRUNNER",
    "> CYBERDECK : TEMPÉRATURE NOMINALE",
)

@Composable
fun HomeScreen(viewModel: LauncherViewModel, homePresses: Int) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val favoriteKeys by viewModel.favorites.collectAsStateWithLifecycle()
    var drawerOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Pressing Home while the drawer is open brings us back to the main HUD.
    LaunchedEffect(homePresses) { drawerOpen = false }

    // A launcher must never close on Back.
    BackHandler { drawerOpen = false }

    val favorites = remember(apps, favoriteKeys) {
        favoriteKeys.mapNotNull { key -> apps.find { it.key == key } }
    }

    Box(
        Modifier
            .fillMaxSize()
            .cyberGrid()
            .scanlines()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            when {
                                total < -120f -> drawerOpen = true
                                total > 120f -> expandNotifications(context)
                            }
                        },
                    ) { _, dragAmount -> total += dragAmount }
                }
        ) {
            StatusStrip()
            Spacer(Modifier.height(28.dp))
            ClockBlock()
            Spacer(Modifier.height(20.dp))
            LiveFeed()
            Spacer(Modifier.weight(1f))
            QuickhackGrid(
                favorites = favorites,
                onLaunch = viewModel::launch,
                onUnpin = viewModel::toggleFavorite,
                onInfo = viewModel::openAppInfo,
            )
            Spacer(Modifier.height(16.dp))
            NetworkButton(onClick = { drawerOpen = true })
        }

        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            AppDrawer(
                apps = apps,
                favoriteKeys = favoriteKeys,
                onLaunch = { app ->
                    viewModel.launch(app)
                    drawerOpen = false
                },
                onTogglePin = viewModel::toggleFavorite,
                onInfo = viewModel::openAppInfo,
                onClose = { drawerOpen = false },
            )
        }
    }
}

@Composable
private fun StatusStrip() {
    val battery = rememberBattery()
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "NETRUNNER://HOME",
            color = Cp.Yellow,
            fontFamily = HudFont,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        val low = battery.level in 0..19
        Text(
            (if (battery.charging) "⚡ " else "") + "PWR ${battery.level.coerceAtLeast(0)}%",
            color = if (low) Cp.Red else Cp.Cyan,
            fontFamily = HudFont,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(8.dp))
        BatteryBar(battery.level, low)
    }
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Cp.Yellow.copy(alpha = 0.6f))
    )
}

@Composable
private fun BatteryBar(level: Int, low: Boolean) {
    val filled = (level.coerceIn(0, 100) + 9) / 10
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(10) { i ->
            Box(
                Modifier
                    .size(width = 4.dp, height = 10.dp)
                    .background(
                        when {
                            i >= filled -> Cp.Dim.copy(alpha = 0.4f)
                            low -> Cp.Red
                            else -> Cp.Cyan
                        }
                    )
            )
        }
    }
}

@Composable
private fun ClockBlock() {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    Row(verticalAlignment = Alignment.Bottom) {
        GlitchText(
            text = now.format(TimeFormat),
            style = TextStyle(fontFamily = HudFont, fontSize = 84.sp, fontWeight = FontWeight.Black),
            color = Cp.Yellow,
        )
        Text(
            now.format(SecondsFormat),
            color = Cp.Red,
            fontFamily = HudFont,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp, bottom = 16.dp),
        )
    }
    Text(
        "${now.format(DateFormat).uppercase()} // NIGHT CITY",
        color = Cp.Cyan,
        fontFamily = HudFont,
        fontSize = 14.sp,
        letterSpacing = 2.sp,
    )
}

/** One terminal line typed letter by letter, cycling through FeedLines. */
@Composable
private fun LiveFeed() {
    var index by remember { mutableIntStateOf(0) }
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            val line = FeedLines[index]
            for (i in 1..line.length) {
                shown = i
                delay(35)
            }
            delay(3500)
            index = (index + 1) % FeedLines.size
            shown = 0
        }
    }
    val line = FeedLines[index]
    Text(
        line.take(shown) + "█",
        color = Cp.Text.copy(alpha = 0.8f),
        fontFamily = HudFont,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun QuickhackGrid(
    favorites: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit,
    onUnpin: (AppEntry) -> Unit,
    onInfo: (AppEntry) -> Unit,
) {
    Text(
        "// QUICKHACKS",
        color = Cp.Yellow,
        fontFamily = HudFont,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    if (favorites.isEmpty()) {
        Text(
            "Aucun raccourci. Ouvre le réseau et fais un appui long sur une appli pour l'épingler.",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 12.sp,
        )
        return
    }
    favorites.chunked(4).forEach { row ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            row.forEach { app ->
                QuickhackTile(app, onLaunch, onUnpin, onInfo, Modifier.weight(1f))
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickhackTile(
    app: AppEntry,
    onLaunch: (AppEntry) -> Unit,
    onUnpin: (AppEntry) -> Unit,
    onInfo: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = Cp.Yellow.copy(alpha = 0.7f))
                .combinedClickable(
                    onClick = { onLaunch(app) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(app.icon, contentDescription = app.label, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                app.label.uppercase(),
                color = Cp.Text,
                fontFamily = HudFont,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        AppMenu(
            expanded = menu,
            pinned = true,
            onDismiss = { menu = false },
            onTogglePin = { onUnpin(app) },
            onInfo = { onInfo(app) },
        )
    }
}

@Composable
private fun NetworkButton(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Yellow, fill = Cp.Yellow.copy(alpha = 0.12f), cut = 14.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "[ ACCÈS RÉSEAU ]  ▲",
            color = Cp.Yellow,
            fontFamily = HudFont,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
        )
    }
}

@Composable
private fun AppDrawer(
    apps: List<AppEntry>,
    favoriteKeys: List<String>,
    onLaunch: (AppEntry) -> Unit,
    onTogglePin: (AppEntry) -> Unit,
    onInfo: (AppEntry) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.97f))
            .scanlines()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "// RÉSEAU : ${filtered.size} NŒUDS",
                color = Cp.Yellow,
                fontFamily = HudFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
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
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = Cp.Cyan, fontFamily = HudFont, fontSize = 16.sp),
            cursorBrush = SolidColor(Cp.Yellow),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                filtered.firstOrNull()?.let(onLaunch)
                focus.clearFocus()
            }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Cyan)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("> ", color = Cp.Yellow, fontFamily = HudFont, fontSize = 16.sp)
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("RECHERCHER_", color = Cp.Dim, fontFamily = HudFont, fontSize = 16.sp)
                        }
                        inner()
                    }
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(filtered, key = { it.key }) { app ->
                DrawerRow(
                    app = app,
                    pinned = app.key in favoriteKeys,
                    onLaunch = onLaunch,
                    onTogglePin = onTogglePin,
                    onInfo = onInfo,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerRow(
    app: AppEntry,
    pinned: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onTogglePin: (AppEntry) -> Unit,
    onInfo: (AppEntry) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .hudPanel(
                    borderColor = if (pinned) Cp.Yellow else Cp.Dim.copy(alpha = 0.5f),
                    cut = 8.dp,
                )
                .combinedClickable(
                    onClick = { onLaunch(app) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(app.icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.label.uppercase(),
                    color = Cp.Text,
                    fontFamily = HudFont,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    app.packageName,
                    color = Cp.Dim,
                    fontFamily = HudFont,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pinned) {
                Text("★", color = Cp.Yellow, fontSize = 16.sp)
            }
        }
        AppMenu(
            expanded = menu,
            pinned = pinned,
            onDismiss = { menu = false },
            onTogglePin = { onTogglePin(app) },
            onInfo = { onInfo(app) },
        )
    }
}

@Composable
private fun AppMenu(
    expanded: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onInfo: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = {
                Text(
                    if (pinned) "RETIRER DES QUICKHACKS" else "ÉPINGLER EN QUICKHACK",
                    color = Cp.Yellow,
                    fontFamily = HudFont,
                    fontSize = 13.sp,
                )
            },
            onClick = {
                onTogglePin()
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("INFOS APPLI", color = Cp.Cyan, fontFamily = HudFont, fontSize = 13.sp) },
            onClick = {
                onInfo()
                onDismiss()
            },
        )
    }
}

private data class BatteryState(val level: Int, val charging: Boolean)

@Composable
private fun rememberBattery(): BatteryState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(BatteryState(-1, false)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                state = BatteryState(
                    level = if (level >= 0 && scale > 0) level * 100 / scale else -1,
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL,
                )
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

/** Swipe down on the HUD opens the notification shade, like the stock launcher. */
@SuppressLint("WrongConstant")
private fun expandNotifications(context: Context) {
    runCatching {
        val service = context.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager")
            .getMethod("expandNotificationsPanel")
            .invoke(service)
    }
}
