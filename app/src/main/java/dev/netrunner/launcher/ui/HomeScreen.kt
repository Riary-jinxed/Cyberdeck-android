package dev.netrunner.launcher.ui

import android.os.PowerManager
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import dev.netrunner.launcher.aska.AskaMorning
import dev.netrunner.launcher.AppCategory
import dev.netrunner.launcher.AppEntry
import dev.netrunner.launcher.LauncherViewModel
import dev.netrunner.launcher.LauncherViewModel.Panel
import dev.netrunner.launcher.call.Contacts
import dev.netrunner.launcher.call.DialerActivity
import dev.netrunner.launcher.call.PhoneContact
import dev.netrunner.launcher.media.MediaWatcher
import dev.netrunner.launcher.notify.NotificationHub
import dev.netrunner.launcher.system.BatteryState
import dev.netrunner.launcher.system.ClaudeApp
import dev.netrunner.launcher.system.Ghost
import dev.netrunner.launcher.system.HomeRole
import dev.netrunner.launcher.system.SystemInfo
import dev.netrunner.launcher.system.UiSound
import dev.netrunner.launcher.Folder
import dev.netrunner.launcher.system.Gestures
import dev.netrunner.launcher.system.NightWatch
import dev.netrunner.launcher.system.Ripperdoc
import dev.netrunner.launcher.system.BatteryLog
import dev.netrunner.launcher.system.Profiles
import dev.netrunner.launcher.system.Updater
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.draw.drawWithContent

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
    val launching by viewModel.launching.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val panel by viewModel.panel.collectAsStateWithLifecycle()
    var drawerOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val feed by NotificationHub.items.collectAsStateWithLifecycle()
    val mediaWatcher = remember { MediaWatcher(context.applicationContext) }
    val nowPlaying by mediaWatcher.nowPlaying.collectAsStateWithLifecycle()
    var feedAccess by remember { mutableStateOf(NotificationHub.isAccessGranted(context)) }
    LifecycleResumeEffect(Unit) {
        feedAccess = NotificationHub.isAccessGranted(context)
        if (feedAccess) mediaWatcher.start()
        // No media callbacks while another app or the lock screen is in front.
        onPauseOrDispose { mediaWatcher.stop() }
    }
    DisposableEffect(Unit) { onDispose { mediaWatcher.stop() } }

    // Re-checked every time we come back, e.g. after the user picked a home app in Settings.
    var isDefaultHome by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        isDefaultHome = HomeRole.isDefault(context)
        onPauseOrDispose { }
    }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefaultHome = HomeRole.isDefault(context)
        // HyperOS sometimes dismisses the dialog without asking: send the user to Settings instead.
        if (!isDefaultHome) HomeRole.openSettings(context)
    }
    val makeDefault = {
        val intent = HomeRole.requestIntent(context)
        if (intent != null) roleRequest.launch(intent) else HomeRole.openSettings(context)
    }

    // Pressing Home brings us back to the main HUD.
    LaunchedEffect(homePresses) {
        if (homePresses == 0) return@LaunchedEffect
        drawerOpen = false
        viewModel.openPanel(Panel.None)
    }

    // A launcher must never close on Back.
    BackHandler {
        drawerOpen = false
        viewModel.openPanel(Panel.None)
    }

    val favorites = remember(apps, favoriteKeys) {
        favoriteKeys.mapNotNull { key -> apps.find { it.key == key } }
    }

    val folders by viewModel.folders.collectAsStateWithLifecycle()
    // Grid slots: pinned apps and folders, in the saved order.
    val slots = remember(apps, favoriteKeys, folders) {
        favoriteKeys.mapNotNull { key ->
            if (key.startsWith(Folder.PREFIX)) {
                folders.firstOrNull { it.slot == key }?.let { f ->
                    QuickSlot.Dir(f, f.keys.mapNotNull { k -> apps.find { it.key == k } })
                }
            } else {
                apps.find { it.key == key }?.let { QuickSlot.App(it) }
            }
        }
    }
    var openFolder by remember { mutableStateOf<String?>(null) }

    var booting by remember { mutableStateOf(!viewModel.booted) }
    remember { Wallpaper.load(context.applicationContext) }
    remember { DeckSettings.load(context.applicationContext) }
    remember { Gestures.load(context.applicationContext) }
    remember { Profiles.load(context.applicationContext) }
    remember { NightWatch.load(context.applicationContext) }
    remember { Ripperdoc.load(context.applicationContext) }
    // Night window check once a minute while the home screen is visible.
    val screenActive = rememberScreenActive()
    LaunchedEffect(screenActive) {
        while (screenActive) {
            NightWatch.refresh()
            delay(60_000)
        }
    }
    // Silent update check, at most once a day.
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    LaunchedEffect(Unit) {
        if (Updater.dueForAutoCheck(context)) update = runCatching { Updater.check(context) }.getOrNull()
    }

    val runAction: (Gestures.Action) -> Unit = { action ->
        when (action) {
            Gestures.Action.Drawer -> drawerOpen = true
            Gestures.Action.Notifications -> expandNotifications(context)
            Gestures.Action.Quick -> viewModel.openPanel(Panel.Quick)
            Gestures.Action.Terminal -> viewModel.openPanel(Panel.Terminal)
            Gestures.Action.Claude -> ClaudeApp.open(context)
            Gestures.Action.Aska -> viewModel.openPanel(Panel.Aska)
            Gestures.Action.Messages -> viewModel.openPanel(Panel.Messages)
            Gestures.Action.Feed -> viewModel.openPanel(Panel.Feed)
            Gestures.Action.Dialer -> context.startActivity(Intent(context, DialerActivity::class.java))
            Gestures.Action.Wifi -> viewModel.openPanel(Panel.Wifi)
            Gestures.Action.Kit -> viewModel.openPanel(Panel.Kit)
            Gestures.Action.Stats -> viewModel.openPanel(Panel.Stats)
            Gestures.Action.Ghost -> {
                UiSound.play(context, UiSound.Cue.Glitch)
                Ghost.toggle(context)
            }
            Gestures.Action.Torch -> toggleTorch(context)
            Gestures.Action.Settings -> viewModel.openPanel(Panel.Settings)
            Gestures.Action.None -> Unit
        }
    }
    // Glitch burst whenever a panel or the drawer opens.
    LaunchedEffect(panel, drawerOpen) {
        if (panel != Panel.None || drawerOpen) GlitchBus.trigger()
    }
    val power = rememberBattery()

    Box(
        Modifier
            .fillMaxSize()
            .cyberGrid()
            .scanlines()
            .hudFrame()
            .crtGlitch()
            .nightDim()
    ) {
        LiveWallpaper(batteryLevel = power.level, charging = power.charging)
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .displayCutoutPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .pointerInput(Unit) {
                    var total = 0f
                    var fromTopRight = false
                    var fromEdge = false
                    detectVerticalDragGestures(
                        onDragStart = { start ->
                            total = 0f
                            // The top strip belongs to Android (it reveals the hidden status bar):
                            // pull-downs that start there are left alone.
                            fromEdge = start.y < 64.dp.toPx()
                            fromTopRight = start.x > size.width * 0.55f && start.y < size.height * 0.3f
                        },
                        onDragEnd = {
                            when {
                                total < -120f -> runAction(Gestures.actionFor(Gestures.Gesture.SwipeUp))
                                total > 120f && fromEdge -> Unit
                                total > 120f && fromTopRight -> viewModel.openPanel(Panel.Quick)
                                total > 120f -> runAction(Gestures.actionFor(Gestures.Gesture.SwipeDown))
                            }
                        },
                    ) { _, dragAmount -> total += dragAmount }
                }
                // Swipe left opens the Claude app, swipe right slides the terminal in.
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            when {
                                total < -120f -> runAction(Gestures.actionFor(Gestures.Gesture.SwipeLeft))
                                total > 120f -> runAction(Gestures.actionFor(Gestures.Gesture.SwipeRight))
                            }
                        },
                    ) { _, dragAmount -> total += dragAmount }
                }
        ) {
            StatusStrip(onMenu = { viewModel.openPanel(Panel.Quick) })
            Spacer(Modifier.height(6.dp))
            DeckRamBar()
            if (!isDefaultHome) {
                Spacer(Modifier.height(12.dp))
                DefaultHomeBanner(onClick = makeDefault)
            }
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Double tap on the clock: ghost mode on or off.
                Box(
                    Modifier
                        .weight(1f)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { runAction(Gestures.actionFor(Gestures.Gesture.DoubleTap)) },
                                onLongPress = { runAction(Gestures.actionFor(Gestures.Gesture.LongPress)) },
                            )
                        }
                ) { Column { ClockBlock() } }
                // As//ka watches over the deck: touch her sigil to talk.
                AskaAvatar(
                    Modifier
                        .size(64.dp)
                        .clickable { viewModel.openPanel(Panel.Aska) },
                    animated = panel == Panel.None && !drawerOpen,
                )
            }
            // The morning after the alarm, As//ka pops up to start the day's conversation.
            val homeContext = LocalContext.current
            var morning by remember { mutableStateOf<String?>(null) }
            LifecycleResumeEffect(Unit) {
                AskaMorning.observe(homeContext)
                morning = if (AskaMorning.isMorning(homeContext)) AskaMorning.greeting() else null
                onPauseOrDispose { }
            }
            morning?.let { greeting ->
                Spacer(Modifier.height(14.dp))
                AskaMorningCard(
                    greeting,
                    onTalk = {
                        AskaMorning.done(homeContext)
                        morning = null
                        viewModel.aska.greet(greeting)
                        viewModel.openPanel(Panel.Aska)
                    },
                    onLater = {
                        AskaMorning.done(homeContext)
                        morning = null
                    },
                    animated = panel == Panel.None && !drawerOpen,
                )
            }
            Spacer(Modifier.height(20.dp))
            LiveFeed()
            update?.let { release ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "▲ MISE À JOUR v${release.version} DISPONIBLE · TOUCHE POUR L'INSTALLER",
                    color = Cp.Cyan,
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .hudPanel(borderColor = Cp.Cyan, fill = Cp.Cyan.copy(alpha = 0.1f), cut = 8.dp)
                        .clickable {
                            update = null
                            viewModel.openPanel(Panel.Settings)
                        }
                        .padding(10.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            // Home pages: HUD (music, messages, zone, implants) then Android widgets.
            val pages = rememberPagerState { 2 }
            HorizontalPager(
                state = pages,
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top,
                beyondViewportPageCount = 1,
            ) { page ->
                if (page == 0) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AskaWidget(viewModel.aska.pet) { viewModel.openPanel(Panel.Aska) }
                        nowPlaying?.let { MediaWidget(it, mediaWatcher) }
                        if (feedAccess) {
                            FeedPreview(feed) { viewModel.openPanel(Panel.Feed) }
                        } else {
                            FeedAccessCard()
                        }
                        ZoneWidget()
                        ImplantHud()
                    }
                } else {
                    WidgetsPage(Modifier.fillMaxSize())
                }
            }
            PageDots(count = 2, current = pages.currentPage, labels = listOf("HUD", "WIDGETS"))
            Spacer(Modifier.height(8.dp))
            QuickhackGrid(
                slots = slots,
                folders = folders,
                onLaunch = viewModel::requestLaunch,
                onUnpin = viewModel::toggleFavorite,
                onInfo = viewModel::openAppInfo,
                onMoveToFolder = viewModel::moveToFolder,
                onOpenFolder = { openFolder = it },
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton("WI-FI", Cp.Cyan, Modifier.weight(1f), HudGlyph.Wifi) { viewModel.openPanel(Panel.Wifi) }
                HudButton("SMS", Cp.Yellow, Modifier.weight(1f), HudGlyph.Message) { viewModel.openPanel(Panel.Messages) }
                HudButton("APPELS", Cp.Red, Modifier.weight(1f), HudGlyph.Phone) {
                    context.startActivity(Intent(context, DialerActivity::class.java))
                }
                HudButton("KIT", Cp.Cyan, Modifier.weight(0.8f), HudGlyph.Kit) { viewModel.openPanel(Panel.Kit) }
            }
        }

        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            AppDrawer(
                apps = apps,
                favoriteKeys = favoriteKeys,
                usage = usage,
                onLaunch = { app ->
                    viewModel.requestLaunch(app)
                    drawerOpen = false
                },
                onTogglePin = viewModel::toggleFavorite,
                onInfo = viewModel::openAppInfo,
                onCommand = { command ->
                    drawerOpen = false
                    viewModel.openPanel(Panel.Terminal)
                    viewModel.terminal.submit(command)
                },
                onContact = { number ->
                    drawerOpen = false
                    context.startActivity(
                        Intent(context, DialerActivity::class.java).setData(Uri.fromParts("tel", number, null))
                    )
                },
                onClose = { drawerOpen = false },
            )
        }

        AnimatedVisibility(
            visible = panel == Panel.Terminal,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            TerminalScreen(terminal = viewModel.terminal, onClose = { viewModel.openPanel(Panel.None) })
        }

        AnimatedVisibility(
            visible = panel == Panel.Feed,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            FeedScreen(onClose = { viewModel.openPanel(Panel.None) })
        }

        AnimatedVisibility(
            visible = panel == Panel.Quick,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            QuickPanel(
                onClose = { viewModel.openPanel(Panel.None) },
                onSystemShade = {
                    viewModel.openPanel(Panel.None)
                    expandNotifications(context)
                },
                onSettings = { viewModel.openPanel(Panel.Settings) },
            )
        }

        AnimatedVisibility(
            visible = panel == Panel.Settings,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            SettingsScreen(onClose = { viewModel.openPanel(Panel.None) }, onConfigChanged = viewModel::reloadConfig)
        }

        AnimatedVisibility(
            visible = panel == Panel.Messages,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            MessagesScreen(onClose = { viewModel.openPanel(Panel.None) })
        }

        AnimatedVisibility(
            visible = panel == Panel.Kit,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            KitScreen(onClose = { viewModel.openPanel(Panel.None) })
        }

        AnimatedVisibility(
            visible = panel == Panel.Stats,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            StatsScreen(onClose = { viewModel.openPanel(Panel.None) })
        }

        AnimatedVisibility(
            visible = panel == Panel.Wifi,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            WifiScreen(onClose = { viewModel.openPanel(Panel.None) })
        }

        AnimatedVisibility(
            visible = panel == Panel.Aska,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            AskaScreen(brain = viewModel.aska, onClose = { viewModel.openPanel(Panel.None) })
        }

        slots.filterIsInstance<QuickSlot.Dir>().firstOrNull { it.folder.id == openFolder }?.let { dir ->
            FolderOverlay(
                dir = dir,
                onLaunch = { app ->
                    openFolder = null
                    viewModel.requestLaunch(app)
                },
                onRemove = { app -> viewModel.removeFromFolder(dir.folder.id, app.key) },
                onRename = { name -> viewModel.renameFolder(dir.folder.id, name) },
                onClose = { openFolder = null },
            )
        }

        launching?.let { app ->
            BreachOverlay(app = app, onDone = viewModel::completeLaunch)
        }

        if (booting) {
            BootSequence(onDone = {
                viewModel.booted = true
                booting = false
            })
        }
    }
}

@Composable
private fun DefaultHomeBanner(onClick: () -> Unit) {
    Text(
        "⚠ NETRUNNER N'EST PAS TON ÉCRAN D'ACCUEIL. TOUCHE ICI POUR L'ACTIVER.",
        color = Cp.Red,
        fontFamily = HudFont,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Red, fill = Cp.Red.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(12.dp),
    )
}

@Composable
private fun StatusStrip(onMenu: () -> Unit) {
    val battery = rememberBattery()
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                Cp.faction == Faction.Ghost -> "GHOST://FURTIF"
                NightWatch.active -> "NETWATCH://NUIT"
                Ripperdoc.active -> "RIPPERDOC://ÉCO"
                else -> "${Cp.faction.label}://${Profiles.current.label}"
            },
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
        Spacer(Modifier.width(10.dp))
        // Control panel button, so nothing depends on swiping from the top edge.
        Text(
            "◢ DECK",
            color = Cp.Background,
            fontFamily = HudFont,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .hudPanel(borderColor = Cp.Yellow, fill = Cp.Yellow, cut = 6.dp)
                .clickable(onClick = onMenu)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
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
    val active = rememberScreenActive()
    val now by produceState(LocalDateTime.now(), active) {
        while (active) {
            value = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    Row(verticalAlignment = Alignment.Bottom) {
        GlitchText(
            text = now.format(TimeFormat),
            style = TextStyle(fontFamily = DisplayFont, fontSize = 64.sp, fontWeight = FontWeight.Black),
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
    val active = rememberScreenActive() && !Ripperdoc.active
    LaunchedEffect(active) {
        if (!active) shown = FeedLines[index].length
        while (active) {
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
    slots: List<QuickSlot>,
    folders: List<Folder>,
    onLaunch: (AppEntry) -> Unit,
    onUnpin: (AppEntry) -> Unit,
    onInfo: (AppEntry) -> Unit,
    onMoveToFolder: (String, String?) -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    // Two rows of four per page; more quickhacks spill onto further pages.
    val pages = slots.chunked(8)
    val pager = rememberPagerState { pages.size.coerceAtLeast(1) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "// QUICKHACKS",
            color = Cp.Yellow,
            fontFamily = HudFont,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (pages.size > 1) {
            Text("${pager.currentPage + 1}/${pages.size}", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
    if (slots.isEmpty()) {
        Text(
            "Aucun raccourci. Ouvre le réseau et fais un appui long sur une appli pour l'épingler.",
            color = Cp.Dim,
            fontFamily = HudFont,
            fontSize = 12.sp,
        )
        return
    }
    HorizontalPager(state = pager, verticalAlignment = Alignment.Top) { page ->
        Column {
            pages[page].chunked(4).forEach { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { slot ->
                        when (slot) {
                            is QuickSlot.App -> QuickhackTile(slot.app, folders, onLaunch, onUnpin, onInfo, onMoveToFolder, Modifier.weight(1f))
                            is QuickSlot.Dir -> FolderTile(slot, Modifier.weight(1f)) { onOpenFolder(slot.folder.id) }
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** One cell of the quickhack grid: an app or a folder of apps. */
sealed interface QuickSlot {
    data class App(val app: AppEntry) : QuickSlot
    data class Dir(val folder: Folder, val apps: List<AppEntry>) : QuickSlot
}

@Composable
private fun FolderTile(dir: QuickSlot.Dir, modifier: Modifier, onOpen: () -> Unit) {
    Column(
        modifier
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.8f), fill = Cp.Cyan.copy(alpha = 0.06f))
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 2×2 preview of the first apps inside.
        Column(Modifier.size(40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            dir.apps.take(4).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { AppIcon(it, 19.dp, tint = Cp.Cyan) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            dir.folder.name.uppercase(),
            color = Cp.Cyan,
            fontFamily = HudFont,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** Opened folder: its apps in a HUD window, a renamable title, long press to take an app out. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderOverlay(
    dir: QuickSlot.Dir,
    onLaunch: (AppEntry) -> Unit,
    onRemove: (AppEntry) -> Unit,
    onRename: (String) -> Unit,
    onClose: () -> Unit,
) {
    var name by remember(dir.folder.id) { mutableStateOf(dir.folder.name) }
    BackHandler(onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .hudPanel(borderColor = Cp.Cyan, fill = Cp.Background.copy(alpha = 0.96f))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { }
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("// ", color = Cp.Cyan, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    singleLine = true,
                    textStyle = TextStyle(color = Cp.Cyan, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    cursorBrush = SolidColor(Cp.Yellow),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onRename(name) }),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "[ X ]",
                    color = Cp.Red,
                    fontFamily = HudFont,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        if (name != dir.folder.name) onRename(name)
                        onClose()
                    }.padding(6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            dir.apps.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { app ->
                        Column(
                            Modifier
                                .weight(1f)
                                .combinedClickable(onClick = { onLaunch(app) }, onLongClick = { onRemove(app) }),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppIcon(app, 40.dp)
                            Text(app.label.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text("Appui long : sortir du dossier", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, labels: List<String>) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Text(
                if (i == current) "◆ ${labels[i]}" else "◇ ${labels[i]}",
                color = if (i == current) Cp.Yellow else Cp.Dim,
                fontFamily = HudFont,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

private var torchOn = false

private fun toggleTorch(context: Context) {
    val camera = context.getSystemService(android.hardware.camera2.CameraManager::class.java) ?: return
    runCatching {
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return
        camera.setTorchMode(id, !torchOn)
        torchOn = !torchOn
    }
}

/** Netwatch night mode: dims everything drawn on top of the launcher. */
private fun Modifier.nightDim(): Modifier = drawWithContent {
    drawContent()
    if (NightWatch.active) drawRect(Color.Black.copy(alpha = 0.42f))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickhackTile(
    app: AppEntry,
    folders: List<Folder>,
    onLaunch: (AppEntry) -> Unit,
    onUnpin: (AppEntry) -> Unit,
    onInfo: (AppEntry) -> Unit,
    onMoveToFolder: (String, String?) -> Unit,
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
            AppIcon(app, 40.dp)
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
            folders = folders,
            onMoveToFolder = { folderId -> onMoveToFolder(app.key, folderId) },
        )
    }
}

@Composable
private fun HudButton(label: String, color: Color, modifier: Modifier = Modifier, glyph: HudGlyph? = null, onClick: () -> Unit) {
    Row(
        modifier
            .hudPanel(borderColor = color, fill = color.copy(alpha = 0.12f), cut = 14.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyph?.let {
            HudIcon(it, color, size = 16.dp)
            Spacer(Modifier.width(7.dp))
        }
        Text(
            label,
            color = color,
            fontFamily = HudFont,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            maxLines = 1,
        )
    }
}

private val SearchCommands = listOf(
    "scan", "ports", "ping", "ip", "wifi", "speedtest", "ghost", "theme", "wallpaper", "sound", "torch",
    "timer", "calc", "subnet", "kit", "qr", "lock", "keyboard", "call", "claude", "sysinfo", "battery", "notifs", "help",
)

private const val FrequentTab = -1
private const val AllTab = -2

/**
 * The "network": every app, by category or by use, with a universal search that also finds
 * contacts and terminal commands.
 */
@Composable
private fun AppDrawer(
    apps: List<AppEntry>,
    favoriteKeys: List<String>,
    usage: Map<String, Int>,
    onLaunch: (AppEntry) -> Unit,
    onTogglePin: (AppEntry) -> Unit,
    onInfo: (AppEntry) -> Unit,
    onCommand: (String) -> Unit,
    onContact: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(AllTab) }
    val focus = LocalFocusManager.current
    val contacts by produceState(emptyList<PhoneContact>()) {
        value = withContext(Dispatchers.IO) { Contacts.all(context) }
    }
    val q = query.trim()
    val filtered = remember(apps, q, tab, usage) {
        when {
            q.isNotEmpty() -> apps.filter { it.label.contains(q, ignoreCase = true) }
                .sortedWith(compareByDescending<AppEntry> { it.label.startsWith(q, ignoreCase = true) }.thenByDescending { usage[it.key] ?: 0 })
            tab == FrequentTab -> apps.filter { (usage[it.key] ?: 0) > 0 }.sortedByDescending { usage[it.key] ?: 0 }
            tab == AllTab -> apps
            else -> apps.filter { it.category.ordinal == tab }
        }
    }
    val matchingCommands = if (q.isEmpty()) emptyList() else SearchCommands.filter { it.startsWith(q.removePrefix(">").trim().substringBefore(' '), true) }.take(4)
    val matchingContacts = if (q.length < 2) emptyList() else contacts.filter { it.name.contains(q, true) || it.number.filter(Char::isDigit).contains(q.filter(Char::isDigit).ifEmpty { "\u0000" }) }.take(5)

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.97f))
            .scanlines()
            .systemBarsPadding()
            .displayCutoutPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "// RÉSEAU : ${apps.size} NŒUDS",
                color = Cp.Yellow,
                fontFamily = HudFont,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "[ X ]",
                color = Cp.Red,
                fontFamily = HudFont,
                fontSize = 15.sp,
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
            textStyle = TextStyle(color = Cp.Cyan, fontFamily = HudFont, fontSize = 17.sp),
            cursorBrush = SolidColor(Cp.Yellow),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                when {
                    q.startsWith(">") -> onCommand(q.removePrefix(">").trim())
                    filtered.isNotEmpty() -> onLaunch(filtered.first())
                    matchingContacts.isNotEmpty() -> onContact(matchingContacts.first().number)
                    matchingCommands.isNotEmpty() -> onCommand(q)
                }
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
                    Text("> ", color = Cp.Yellow, fontFamily = HudFont, fontSize = 17.sp)
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("APPLIS, CONTACTS, COMMANDES_", color = Cp.Dim, fontFamily = HudFont, fontSize = 17.sp)
                        }
                        inner()
                    }
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        if (q.isEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tabs = listOf(AllTab to "TOUT", FrequentTab to "★ FRÉQUENTS") +
                    AppCategory.entries.map { it.ordinal to it.label }
                tabs.forEach { (id, label) ->
                    val selected = tab == id
                    val count = when (id) {
                        AllTab -> apps.size
                        FrequentTab -> apps.count { (usage[it.key] ?: 0) > 0 }
                        else -> apps.count { it.category.ordinal == id }
                    }
                    if (count == 0 && id >= 0) return@forEach
                    Text(
                        "$label $count",
                        color = if (selected) Cp.Background else Cp.Cyan,
                        fontFamily = HudFont,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.7f), fill = if (selected) Cp.Cyan else Cp.Panel, cut = 6.dp)
                            .clickable { tab = id }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (matchingCommands.isNotEmpty()) {
                item { SearchHeader("COMMANDES") }
                items(matchingCommands, key = { "cmd:$it" }) { command ->
                    SearchRow(">_", Cp.Yellow, command, "lancer dans le terminal") { onCommand(command) }
                }
            }
            if (matchingContacts.isNotEmpty()) {
                item { SearchHeader("CONTACTS") }
                items(matchingContacts, key = { "contact:${it.name}:${it.number}" }) { c ->
                    SearchRow("", Cp.Red, c.name, c.number, HudGlyph.Phone) { onContact(c.number) }
                }
            }
            if (q.isNotEmpty() && filtered.isNotEmpty()) item { SearchHeader("APPLIS") }
            items(filtered, key = { it.key }) { app ->
                DrawerRow(
                    app = app,
                    pinned = app.key in favoriteKeys,
                    onLaunch = onLaunch,
                    onTogglePin = onTogglePin,
                    onInfo = onInfo,
                )
            }
            if (q.isNotEmpty() && filtered.isEmpty() && matchingContacts.isEmpty() && matchingCommands.isEmpty()) {
                item {
                    Text("Aucun nœud. Préfixe par « > » pour lancer une commande.", color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SearchHeader(title: String) {
    Text(
        "// $title",
        color = Cp.Yellow,
        fontFamily = HudFont,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SearchRow(icon: String, color: Color, title: String, subtitle: String, glyph: HudGlyph? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = color.copy(alpha = 0.5f), cut = 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Box(Modifier.width(36.dp)) { HudIcon(glyph, color, size = 18.dp) }
        } else {
            Text(icon, color = color, fontFamily = HudFont, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, maxLines = 1)
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
            AppIcon(app, 36.dp, tint = Cp.Cyan)
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
    folders: List<Folder>? = null,
    onMoveToFolder: (String?) -> Unit = {},
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Quickhack tiles only: file the app into a folder.
        if (folders != null) {
            DropdownMenuItem(
                text = { Text("NOUVEAU DOSSIER", color = Cp.Cyan, fontFamily = HudFont, fontSize = 13.sp) },
                onClick = {
                    onMoveToFolder(null)
                    onDismiss()
                },
            )
            folders.forEach { f ->
                DropdownMenuItem(
                    text = { Text("RANGER DANS ${f.name.uppercase()}", color = Cp.Cyan, fontFamily = HudFont, fontSize = 13.sp) },
                    onClick = {
                        onMoveToFolder(f.id)
                        onDismiss()
                    },
                )
            }
        }
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

/** Polls the battery every 20 s and also listens for changes, so it is right on every OEM skin. */
@Composable
private fun rememberBattery(): BatteryState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(SystemInfo.battery(context)) }
    val active = rememberScreenActive()
    LaunchedEffect(active) {
        while (active) {
            state = SystemInfo.battery(context)
            Ripperdoc.refresh(context, state)
            delay(20_000)
        }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                state = SystemInfo.battery(context)
                Ripperdoc.refresh(context, state)
                BatteryLog.record(context, state)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
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
