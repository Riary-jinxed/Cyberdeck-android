package dev.netrunner.clock.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.clock.alarm.RingService
import dev.netrunner.clock.data.ClockPrefs
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Full-screen wake screen, over the lock screen. Slide to stop, tap to snooze. */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this) {}
        setContent {
            ClockTheme {
                val ring by RingService.ringing.collectAsState()
                LaunchedEffect(ring) { if (ring == null) finish() }
                ring?.let { RingScreen(it, onSnooze = ::snooze, onDismiss = { dismiss(it) }) }
            }
        }
    }

    private fun snooze() {
        RingService.command(this, RingService.ACTION_SNOOZE)
        finish()
    }

    private fun dismiss(ring: RingService.Ring) {
        RingService.command(this, RingService.ACTION_DISMISS)
        // A morning alarm hands over to the home screen, where As//ka greets her runner.
        if (ring.kind == RingService.Kind.Alarm && ring.hour in 4..11 && ClockPrefs.homeAfterMorning(this)) {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        finish()
    }

    // The volume keys snooze, like most clocks; back does nothing so the alarm is not lost.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            snooze()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun RingScreen(ring: RingService.Ring, onSnooze: () -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val now by rememberNow(500L)
    val pulse = (now / 500L) % 2L == 0L
    val time = LocalTime.now()
    val isAlarm = ring.kind == RingService.Kind.Alarm
    val accent = if (pulse) Cp.Yellow else Cp.Red

    Box(
        Modifier
            .fillMaxSize()
            .cyberGrid()
            .scanlines()
            .border(2.dp, accent.copy(alpha = 0.7f))
            .systemBarsPadding()
            .padding(24.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            Text(
                if (isAlarm) "WAKE-UP PROTOCOL" else "COMPTE À REBOURS TERMINÉ",
                color = Cp.Red, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                time.format(DateTimeFormatter.ofPattern("HH:mm")),
                color = Cp.Yellow, fontFamily = DisplayFont, fontWeight = FontWeight.Black, fontSize = 92.sp,
            )
            Text(
                java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)).uppercase(),
                color = Cp.Cyan, fontFamily = HudFont, fontSize = 16.sp, letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(32.dp))
            Text(
                ring.label.ifEmpty { if (isAlarm) "Debout, runner. Night City n'attend pas." else "Temps écoulé." },
                color = Cp.Text, fontFamily = HudFont, fontSize = 22.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f)).padding(18.dp),
            )
            Spacer(Modifier.weight(1f))
            HudButton(
                if (isAlarm) "RÉPIT +${ClockPrefs.snoozeMinutes(context)} MIN" else "+1 MIN",
                color = Cp.Cyan, size = 20.sp, modifier = Modifier.fillMaxWidth(), onClick = onSnooze,
            )
            Spacer(Modifier.height(20.dp))
            SlideToStop(onDismiss)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SlideToStop(onDone: () -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .hudPanel(borderColor = Cp.Yellow),
    ) {
        val knob = 68.dp
        val max = with(LocalDensity.current) { (maxWidth - knob).toPx() }
        var x by remember { mutableFloatStateOf(0f) }
        Text(
            "GLISSER POUR ARRÊTER  ▸▸▸",
            color = Cp.Yellow.copy(alpha = 0.7f), fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 2.sp,
            modifier = Modifier.align(Alignment.Center),
        )
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), 0) }
                .size(knob)
                .fillMaxHeight()
                .background(Cp.Yellow, hudShape())
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d -> x = (x + d).coerceIn(0f, max) },
                    onDragStopped = { if (x > max * 0.85f) onDone() else x = 0f },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = Cp.Background, fontSize = 28.sp, fontWeight = FontWeight.Black)
        }
    }
}
