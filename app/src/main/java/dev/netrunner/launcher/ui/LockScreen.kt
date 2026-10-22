package dev.netrunner.launcher.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.netrunner.launcher.R
import dev.netrunner.launcher.aska.AskaDay
import dev.netrunner.launcher.aska.AskaMorning
import dev.netrunner.launcher.media.MediaWatcher
import dev.netrunner.launcher.notify.NotificationHub
import dev.netrunner.launcher.system.SystemInfo
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val LockTime = DateTimeFormatter.ofPattern("HH:mm")
private val LockDate = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())

/**
 * Cyberdeck lock screen: clock, power, incoming feed (app and title only, never the message
 * text) and music. Swipe up to "jack in", which hands over to the system unlock.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val context = LocalContext.current
    remember { DeckSettings.load(context.applicationContext) }
    val active = rememberScreenActive()
    val now by produceState(LocalDateTime.now(), active) {
        while (active) {
            value = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val battery by produceState(SystemInfo.battery(context), active) {
        while (active) {
            value = SystemInfo.battery(context)
            delay(30_000)
        }
    }
    val feed by NotificationHub.items.collectAsStateWithLifecycle()
    val watcher = remember { MediaWatcher(context.applicationContext) }
    val nowPlaying by watcher.nowPlaying.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        watcher.start()
        onPauseOrDispose { watcher.stop() }
    }
    DisposableEffect(Unit) { onDispose { watcher.stop() } }

    var drag by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .cyberGrid()
            .scanlines()
            .hudFrame()
            .crtGlitch()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (drag < -160f) onUnlock()
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { _, amount -> drag = (drag + amount).coerceAtMost(0f) }
            }
    ) {
        remember { Wallpaper.load(context.applicationContext) }
        LiveWallpaper(batteryLevel = battery.level, charging = battery.charging)
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .displayCutoutPadding()
                .padding(horizontal = 22.dp, vertical = 16.dp)
                .offset { IntOffset(0, (drag / 3).roundToInt()) }
                .alpha((1f + drag / 900f).coerceIn(0.3f, 1f)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("// DECK VERROUILLÉ", color = Cp.Red, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                val low = battery.level in 0..19
                Text(
                    (if (battery.charging) "⚡ " else "") + "PWR ${battery.level.coerceAtLeast(0)}%",
                    color = if (low) Cp.Red else Cp.Cyan,
                    fontFamily = HudFont,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            DeckRamBar()
            Spacer(Modifier.height(40.dp))
            GlitchText(
                text = now.format(LockTime),
                style = TextStyle(fontFamily = DisplayFont, fontSize = 72.sp, fontWeight = FontWeight.Black),
                color = Cp.Yellow,
            )
            Text(
                now.format(LockDate).uppercase(),
                color = Cp.Cyan,
                fontFamily = HudFont,
                fontSize = 15.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(24.dp))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                nowPlaying?.let { MediaWidget(it, watcher) }
                if (feed.isNotEmpty()) {
                    Text(
                        "// ${feed.size} SIGNAL${if (feed.size > 1) "S" else ""} ENTRANT${if (feed.size > 1) "S" else ""}",
                        color = Cp.Yellow,
                        fontFamily = HudFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    feed.take(4).forEach { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.4f), cut = 6.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                item.appLabel.uppercase(),
                                color = Cp.Cyan,
                                fontFamily = HudFont,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.width(96.dp),
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                item.title,
                                color = Cp.Text,
                                fontFamily = HudFont,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            // As//ka sleeps while the deck is locked, and is up on the morning after the alarm.
            val morning = remember(now.hour, now.minute) {
                AskaMorning.observe(context)
                AskaMorning.isMorning(context)
            }
            // Otherwise she mumbles in her sleep about the battery or the hour.
            val moment = remember(now.hour, now.minute) { AskaDay.moment(context) }
            val mumble = moment?.let { AskaDay.momentLine(context, it, asleep = true) }
            if (DeckSettings.askaLock) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.weight(1f))
                if (morning && DeckSettings.askaMorning) {
                    AskaBubble(
                        "Debout, runner ! Glisse vers le haut, j'ai un truc à te dire.",
                        Modifier
                            .weight(2f, fill = false)
                            .padding(bottom = 70.dp, end = 4.dp),
                    )
                    AskaWaking(Modifier.size(width = 110.dp, height = 130.dp), animated = active)
                } else {
                    mumble?.let {
                        AskaBubble(
                            it,
                            Modifier
                                .weight(2f, fill = false)
                                .padding(bottom = 60.dp, end = 4.dp),
                        )
                    }
                    AskaSleeping(
                        Modifier.size(width = 130.dp, height = 120.dp),
                        animated = active,
                        // Out of battery, she slumps instead of sleeping.
                        art = if (moment == AskaDay.Moment.LowBattery) R.drawable.aska_chibi_lowbatt else R.drawable.aska_chibi_sleep,
                    )
                }
            }
            Text(
                "▲ JACK-IN",
                color = Cp.Yellow.copy(alpha = 0.6f),
                fontFamily = HudFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
