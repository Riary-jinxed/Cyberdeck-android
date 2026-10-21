package dev.netrunner.launcher.ui

import android.content.Context
import android.os.PowerManager
import android.telecom.CallAudioState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.netrunner.launcher.call.CallInfo
import dev.netrunner.launcher.call.CallManager
import dev.netrunner.launcher.call.ContactHit
import dev.netrunner.launcher.call.Contacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sin

/** Cyan hologram tint for the caller's photo. */
private val HoloFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.1f, 0.2f, 0.1f, 0f, 0f,
            0.3f, 0.6f, 0.2f, 0f, 20f,
            0.3f, 0.6f, 0.3f, 0f, 30f,
            0f, 0f, 0f, 0.9f, 0f,
        )
    )
)

@Composable
fun HoloCallScreen(onFinished: () -> Unit) {
    val calls by CallManager.calls.collectAsStateWithLifecycle()
    val audio by CallManager.audio.collectAsStateWithLifecycle()
    var lastCall by remember { mutableStateOf<CallInfo?>(null) }
    val current = calls.firstOrNull { it.ringing } ?: calls.firstOrNull { it.active } ?: calls.firstOrNull()
    if (current != null) lastCall = current

    // Show "link terminated" briefly, then leave.
    LaunchedEffect(current == null) {
        if (current == null) {
            delay(if (lastCall == null) 300 else 1400)
            onFinished()
        }
    }

    val shown = current ?: lastCall
    val context = LocalContext.current
    val contact by produceState<ContactHit?>(null, shown?.number) {
        val number = shown?.number.orEmpty()
        value = withContext(Dispatchers.IO) { Contacts.lookup(context, number) }
    }
    val speaker = audio?.route == CallAudioState.ROUTE_SPEAKER
    ProximityLock(enabled = current?.active == true && !speaker)

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
                .displayCutoutPadding()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val status = when {
                current == null -> "LIAISON TERMINÉE"
                current.ringing -> "HOLO-APPEL ENTRANT"
                current.onHold -> "LIAISON EN ATTENTE"
                current.active -> "LIAISON ÉTABLIE"
                current.incoming -> "CONNEXION..."
                else -> "APPEL SORTANT..."
            }
            BlinkingLabel(status, if (current == null) Cp.Red else Cp.Yellow, blink = current?.active != true)
            Spacer(Modifier.height(24.dp))

            val name = contact?.name ?: shown?.displayName ?: shown?.number?.ifBlank { null } ?: "NUMÉRO MASQUÉ"
            Hologram(name = name, photo = contact, talking = current?.active == true)
            Spacer(Modifier.height(20.dp))

            GlitchText(
                text = name.uppercase(),
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = HudFont,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                ),
                color = Cp.Yellow,
            )
            if (shown != null && shown.number.isNotBlank() && name != shown.number) {
                Text(shown.number, color = Cp.Cyan, fontFamily = HudFont, fontSize = 15.sp)
            }
            if (current?.active == true) {
                CallTimer(current.connectTimeMillis)
            }

            Spacer(Modifier.weight(1f))

            when {
                current == null -> Unit
                current.ringing -> IncomingControls(current)
                else -> ActiveControls(current, muted = audio?.isMuted == true, speaker = speaker)
            }
        }
    }
}

@Composable
private fun BlinkingLabel(text: String, color: Color, blink: Boolean) {
    val loop = rememberInfiniteTransition(label = "blink")
    val a by loop.animateFloat(1f, 0.35f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
    Text(
        "// $text //",
        color = color,
        fontFamily = HudFont,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = Modifier.alpha(if (blink) a else 1f),
    )
}

/** The caller as a flickering cyan hologram in a cut-corner frame, like the in-game holocalls. */
@Composable
private fun Hologram(name: String, photo: ContactHit?, talking: Boolean) {
    val loop = rememberInfiniteTransition(label = "holo")
    val flicker by loop.animateFloat(0.75f, 1f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "flicker")
    val scan by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2200)), label = "scan")
    val wave by loop.animateFloat(0f, 6.28f, infiniteRepeatable(tween(700)), label = "wave")

    Box(
        Modifier
            .size(220.dp)
            .hudPanel(borderColor = Cp.Yellow, fill = Cp.Cyan.copy(alpha = 0.05f), cut = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = photo?.photo
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = HoloFilter,
                modifier = Modifier
                    .size(200.dp)
                    .clip(hudShape(18.dp))
                    .alpha(flicker),
            )
        } else {
            Text(
                name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                color = Cp.Cyan.copy(alpha = flicker),
                fontFamily = HudFont,
                fontSize = 110.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Canvas(Modifier.size(200.dp)) {
            // Sweep line and hologram scanlines.
            val y = size.height * scan
            drawLine(Cp.Cyan.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), strokeWidth = 3f)
            var line = 0f
            while (line < size.height) {
                drawLine(Color.Black.copy(alpha = 0.25f), Offset(0f, line), Offset(size.width, line))
                line += 4f
            }
            // Voice bars at the bottom while connected.
            if (talking) {
                val bars = 16
                val w = size.width / (bars * 2f)
                for (i in 0 until bars) {
                    val h = (0.3f + 0.7f * ((sin(wave + i * 0.8f) + 1f) / 2f)) * 24f
                    drawLine(
                        Cp.Yellow,
                        Offset(w + i * 2 * w, size.height - 4f),
                        Offset(w + i * 2 * w, size.height - 4f - h),
                        strokeWidth = w,
                    )
                }
            }
        }
    }
}

@Composable
private fun CallTimer(connectTime: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectTime) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val seconds = if (connectTime > 0) ((now - connectTime) / 1000).coerceAtLeast(0) else 0
    Text(
        "%02d:%02d".format(seconds / 60, seconds % 60),
        color = Cp.Text,
        fontFamily = HudFont,
        fontSize = 18.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun IncomingControls(call: CallInfo) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BigCallButton("✕ REFUSER", Cp.Red, Modifier.weight(1f)) { CallManager.reject(call) }
        BigCallButton("✓ ACCEPTER", Cp.Cyan, Modifier.weight(1f)) { CallManager.answer(call) }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ActiveControls(call: CallInfo, muted: Boolean, speaker: Boolean) {
    var keypad by remember { mutableStateOf(false) }
    if (keypad) {
        Keypad(onDigit = { CallManager.dtmf(call, it) })
        Spacer(Modifier.height(12.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToggleTile("MICRO", if (muted) "COUPÉ" else "ON", muted, Modifier.weight(1f)) { CallManager.setMuted(!muted) }
        ToggleTile("HAUT-PARL.", if (speaker) "ON" else "OFF", speaker, Modifier.weight(1f)) { CallManager.setSpeaker(!speaker) }
        ToggleTile("CLAVIER", if (keypad) "OUVERT" else "", keypad, Modifier.weight(1f)) { keypad = !keypad }
        ToggleTile("ATTENTE", if (call.onHold) "ON" else "OFF", call.onHold, Modifier.weight(1f), enabled = call.canHold) {
            CallManager.toggleHold(call)
        }
    }
    Spacer(Modifier.height(14.dp))
    BigCallButton("■ COUPER LA LIAISON", Cp.Red, Modifier.fillMaxWidth()) { CallManager.hangUp(call) }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun BigCallButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .hudPanel(borderColor = color, fill = color.copy(alpha = 0.18f), cut = 14.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontFamily = HudFont, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable
private fun ToggleTile(
    label: String,
    state: String,
    active: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> Cp.Dim
        active -> Cp.Yellow
        else -> Cp.Cyan
    }
    Column(
        modifier
            .hudPanel(borderColor = color, fill = if (active) Cp.Yellow.copy(alpha = 0.15f) else Cp.Panel, cut = 8.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = color, fontFamily = HudFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Clip)
        Text(state, color = Cp.Text.copy(alpha = 0.7f), fontFamily = HudFont, fontSize = 9.sp)
    }
}

private val KeypadRows = listOf("123", "456", "789", "*0#")

/** 3x4 keypad shared by the dialer and the in-call DTMF pad. */
@Composable
fun Keypad(onDigit: (Char) -> Unit, onLongZero: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KeypadRows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    Box(
                        Modifier
                            .weight(1f)
                            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), cut = 8.dp)
                            .clickable { onDigit(digit) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(digit.toString(), color = Cp.Text, fontFamily = HudFont, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Turns the screen off against the cheek during an earpiece call. */
@Composable
private fun ProximityLock(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val lock = if (enabled && power?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
            power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "netrunner:proximity").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        } else {
            null
        }
        onDispose { lock?.release() }
    }
}
