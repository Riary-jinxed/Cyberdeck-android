package dev.netrunner.launcher.ui

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import dev.netrunner.launcher.lock.CyberLock
import dev.netrunner.launcher.system.Ghost
import dev.netrunner.launcher.system.UiSound

private fun Context.open(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/**
 * Netrunner's own control centre. Android does not let apps flip Wi-Fi or Bluetooth
 * directly any more, so those tiles open the system's quick panels; the torch, volume,
 * ringer, rotation and Do Not Disturb are driven from here.
 */
@Composable
fun QuickPanel(onClose: () -> Unit, onSystemShade: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val notifications = remember { context.getSystemService(NotificationManager::class.java) }
    val torch = rememberTorch()

    var wifiOn by remember { mutableStateOf(false) }
    var bluetoothOn by remember { mutableStateOf(false) }
    var dndOn by remember { mutableStateOf(false) }
    var rotationOn by remember { mutableStateOf(false) }
    var ringer by remember { mutableIntStateOf(AudioManager.RINGER_MODE_NORMAL) }
    var tick by remember { mutableIntStateOf(0) }
    var lockOn by remember { mutableStateOf(false) }
    var ghostOn by remember { mutableStateOf(false) }
    var soundOn by remember { mutableStateOf(true) }

    fun readStates() {
        wifiOn = context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        bluetoothOn = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)
        dndOn = notifications?.currentInterruptionFilter?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL } == true
        rotationOn = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        ringer = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        lockOn = CyberLock.isEnabled(context) && CyberLock.missingStep(context) == null
        ghostOn = Ghost.isOn(context)
        soundOn = UiSound.isEnabled(context)
    }
    LifecycleResumeEffect(tick) {
        readStates()
        onPauseOrDispose { }
    }
    // System panels change state behind our back: poll lightly while open.
    val active = rememberScreenActive()
    LaunchedEffect(active) {
        while (active) {
            readStates()
            delay(1500)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.97f))
            .scanlines()
            .systemBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PanelHeader(title = "// CONTRÔLE DU DECK", accent = Cp.Yellow, onClose = onClose)
        Spacer(Modifier.height(12.dp))

        TileRow(
            Tile("WI-FI", if (wifiOn) "EN LIGNE" else "COUPÉ", wifiOn) {
                context.open(Intent(Settings.Panel.ACTION_WIFI))
            },
            Tile("DONNÉES", "RÉSEAU", true) {
                context.open(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            },
            Tile("BLUETOOTH", if (bluetoothOn) "ACTIF" else "COUPÉ", bluetoothOn) {
                context.open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            },
        )
        TileRow(
            Tile("LAMPE", if (torch.on) "ON" else "OFF", torch.on, enabled = torch.available) { torch.toggle() },
            Tile(
                "SONNERIE",
                when (ringer) {
                    AudioManager.RINGER_MODE_VIBRATE -> "VIBREUR"
                    AudioManager.RINGER_MODE_SILENT -> "SILENCE"
                    else -> "NORMAL"
                },
                ringer == AudioManager.RINGER_MODE_NORMAL,
            ) {
                runCatching {
                    audio?.ringerMode = if (ringer == AudioManager.RINGER_MODE_NORMAL) {
                        AudioManager.RINGER_MODE_VIBRATE
                    } else {
                        AudioManager.RINGER_MODE_NORMAL
                    }
                }
                readStates()
            },
            Tile("NE PAS DÉRANGER", if (dndOn) "ACTIF" else "OFF", dndOn) {
                if (notifications?.isNotificationPolicyAccessGranted == true) {
                    notifications.setInterruptionFilter(
                        if (dndOn) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    )
                    readStates()
                } else {
                    context.open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            },
        )
        TileRow(
            Tile("ROTATION", if (rotationOn) "AUTO" else "BLOQUÉE", rotationOn) {
                if (Settings.System.canWrite(context)) {
                    Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (rotationOn) 0 else 1)
                    readStates()
                } else {
                    context.open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
            },
            Tile("LUMINOSITÉ", "ÉCRAN", true) { context.open(Intent(Settings.ACTION_DISPLAY_SETTINGS)) },
            Tile("MODE AVION", "RÉGLAGE", false) { context.open(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) },
        )
        TileRow(
            Tile("LOCALISATION", "RÉGLAGE", false) { context.open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            Tile("BATTERIE", "ÉCONOMIE", false) { context.open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) },
            Tile("PARAMÈTRES", "SYSTÈME", false) { context.open(Intent(Settings.ACTION_SETTINGS)) },
        )
        TileRow(
            Tile("VERROU CYBER", if (lockOn) "ACTIF" else "OFF", lockOn) {
                val missing = CyberLock.missingStep(context)
                when {
                    lockOn -> CyberLock.setEnabled(context, false)
                    missing != null -> {
                        // Turned on now, effective as soon as the permission is granted.
                        CyberLock.setEnabled(context, true)
                        context.startActivity(missing)
                    }
                    else -> CyberLock.setEnabled(context, true)
                }
                readStates()
            },
            Tile("CLAVIER", "NETRUNNER", false) { context.open(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            Tile("TÉLÉPHONE", "APPLI PAR DÉFAUT", false) { context.open(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) },
        )
        TileRow(
            Tile("MODE FURTIF", if (ghostOn) "ENGAGÉ" else "OFF", ghostOn) {
                UiSound.play(context, UiSound.Cue.Glitch)
                Ghost.toggle(context)
                readStates()
            },
            Tile("SONS HUD", if (soundOn) "ON" else "OFF", soundOn) {
                UiSound.setEnabled(context, !soundOn)
                readStates()
            },
            Tile("FOND", Wallpaper.mode.label, Wallpaper.mode != WallpaperMode.Off) {
                val modes = WallpaperMode.entries
                Wallpaper.select(context, modes[(Wallpaper.mode.ordinal + 1) % modes.size])
            },
        )
        Text("// ALLÉGEANCE", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        TileRow(
            *Faction.entries.filter { it != Faction.Ghost }.map { f ->
                Tile(f.label, if (Cp.faction == f) "ACTIF" else "", Cp.faction == f) {
                    UiSound.play(context, UiSound.Cue.Glitch)
                    Cp.select(context, f)
                }
            }.toTypedArray()
        )

        Spacer(Modifier.height(16.dp))
        VolumeSlider("MÉDIA", AudioManager.STREAM_MUSIC, audio)
        VolumeSlider("SONNERIE", AudioManager.STREAM_RING, audio)
        VolumeSlider("ALARME", AudioManager.STREAM_ALARM, audio)

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallHudButton("[ VOLET SYSTÈME ]", Cp.Cyan, onSystemShade)
            SmallHudButton("[ CONFIG NETRUNNER ]", Cp.Yellow, onSettings)
        }
    }
}

private data class Tile(
    val label: String,
    val state: String,
    val active: Boolean,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun TileRow(vararg tiles: Tile) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        tiles.forEach { tile ->
            val color: Color = when {
                !tile.enabled -> Cp.Dim
                tile.active -> Cp.Yellow
                else -> Cp.Cyan
            }
            Column(
                Modifier
                    .weight(1f)
                    .hudPanel(borderColor = color, fill = if (tile.active && tile.enabled) Cp.Yellow.copy(alpha = 0.15f) else Cp.Panel)
                    .clickable(enabled = tile.enabled, onClick = tile.onClick)
                    .padding(vertical = 12.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(tile.label, color = color, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(tile.state, color = Cp.Text.copy(alpha = 0.7f), fontFamily = HudFont, fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun VolumeSlider(label: String, stream: Int, audio: AudioManager?) {
    if (audio == null) return
    val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
    var value by remember { mutableFloatStateOf(audio.getStreamVolume(stream).toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label.padEnd(9), color = Cp.Cyan, fontFamily = HudFont, fontSize = 11.sp)
        Slider(
            value = value,
            onValueChange = {
                value = it
                runCatching { audio.setStreamVolume(stream, it.toInt(), 0) }
            },
            valueRange = 0f..max.toFloat(),
            steps = (max - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = Cp.Yellow,
                activeTrackColor = Cp.Yellow,
                inactiveTrackColor = Cp.Dim.copy(alpha = 0.4f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        Text("${value.toInt()}", color = Cp.Text, fontFamily = HudFont, fontSize = 11.sp)
    }
}

private class TorchState(val available: Boolean, val on: Boolean, val toggle: () -> Unit)

@Composable
private fun rememberTorch(): TorchState {
    val context = LocalContext.current
    val camera = remember { context.getSystemService(CameraManager::class.java) }
    val cameraId = remember {
        runCatching {
            camera?.cameraIdList?.firstOrNull {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }
    var on by remember { mutableStateOf(false) }
    DisposableEffect(cameraId) {
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId) on = enabled
            }
        }
        camera?.registerTorchCallback(callback, null)
        onDispose { camera?.unregisterTorchCallback(callback) }
    }
    return TorchState(available = cameraId != null, on = on) {
        cameraId?.let { id -> runCatching { camera?.setTorchMode(id, !on) } }
    }
}
