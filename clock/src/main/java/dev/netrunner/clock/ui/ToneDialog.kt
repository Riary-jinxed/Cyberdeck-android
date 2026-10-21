package dev.netrunner.clock.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.netrunner.clock.alarm.TonePlayer
import dev.netrunner.clock.data.ClockPrefs
import java.io.File

/**
 * Picks the alarm sound: the phone's default, a system tone, or a music file imported from the
 * phone. Imported files are copied into the app (never uploaded anywhere); for those, a range
 * slider chooses the excerpt that loops.
 */
@Composable
fun ToneDialog(current: ToneChoice, onDismiss: () -> Unit, onChoose: (ToneChoice) -> Unit) {
    val context = LocalContext.current
    var choice by remember { mutableStateOf(current) }
    var imported by remember { mutableStateOf(TonePlayer.imported(context)) }
    var importing by remember { mutableStateOf(false) }
    val preview = remember { TonePlayer(context) }
    var previewing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { preview.stop() } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        Thread {
            val copied = TonePlayer.import(context, uri)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                importing = false
                if (copied == null) {
                    Toast.makeText(context, "Impossible de lire ce fichier.", Toast.LENGTH_SHORT).show()
                } else {
                    imported = TonePlayer.imported(context)
                    choice = ToneChoice(copied.toString())
                }
            }
        }.start()
    }
    val systemLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        choice = ToneChoice(uri?.toString())
    }

    fun stopPreview() {
        preview.stop()
        previewing = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .hudPanel(borderColor = Cp.Cyan, fill = Cp.Background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionTitle("SONNERIE")
            Spacer(Modifier.height(10.dp))
            ToneRow("Sonnerie d'alarme du téléphone", choice.uri == null) { stopPreview(); choice = ToneChoice(null) }
            ToneRow("Sonnerie système…", choice.uri?.startsWith("content://") == true) {
                stopPreview()
                systemLauncher.launch(
                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, choice.uri?.let(Uri::parse))
                )
            }
            Spacer(Modifier.height(10.dp))
            SectionTitle("MUSIQUES IMPORTÉES", Cp.Yellow)
            Spacer(Modifier.height(6.dp))
            imported.forEach { file ->
                val uri = Uri.fromFile(file).toString()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        ToneRow(file.nameWithoutExtension, choice.uri == uri) { stopPreview(); choice = ToneChoice(uri) }
                    }
                    Text(
                        "✕", color = Cp.Red, fontSize = 18.sp,
                        modifier = Modifier.clickable {
                            stopPreview()
                            File(file.path).delete()
                            imported = TonePlayer.imported(context)
                            if (choice.uri == uri) choice = ToneChoice(null)
                        }.padding(12.dp),
                    )
                }
            }
            HudButton(if (importing) "IMPORT EN COURS…" else "+ IMPORTER UNE MUSIQUE", color = Cp.Yellow, modifier = Modifier.fillMaxWidth()) {
                if (!importing) importLauncher.launch(arrayOf("audio/*"))
            }
            Text(
                "MP3, M4A, OGG, FLAC… depuis ton téléphone. Le fichier est copié dans l'appli et reste sur le téléphone.",
                color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
            )

            val fileUri = choice.uri?.takeIf { it.startsWith("file:") }
            if (fileUri != null) {
                Spacer(Modifier.height(14.dp))
                ExcerptPicker(
                    choice = choice,
                    onChange = { stopPreview(); choice = it },
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                HudButton(if (previewing) "■ STOP" else "▶ ÉCOUTER", color = Cp.Cyan) {
                    if (previewing) stopPreview() else {
                        preview.play(choice.uri, choice.start, choice.end, ClockPrefs.crescendoSeconds(context) * 1000L, alarmStream = false)
                        previewing = true
                    }
                }
                Spacer(Modifier.weight(1f))
                HudButton("ANNULER", color = Cp.Dim) { stopPreview(); onDismiss() }
                HudButton("OK", filled = true) { stopPreview(); onChoose(choice) }
            }
        }
    }
}

@Composable
private fun ToneRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "◆" else "◇", color = if (selected) Cp.Yellow else Cp.Dim, fontSize = 16.sp)
        Text(
            title, color = if (selected) Cp.Yellow else Cp.Text, fontFamily = HudFont, fontSize = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** Start and end of the looping excerpt, to the second. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExcerptPicker(choice: ToneChoice, onChange: (ToneChoice) -> Unit) {
    val context = LocalContext.current
    val duration = remember(choice.uri) { TonePlayer.durationMs(context, Uri.parse(choice.uri)) }
    if (duration <= 0) return
    val seconds = (duration / 1000).toInt().coerceAtLeast(1)
    var startS by remember(choice.uri) { mutableIntStateOf((choice.start / 1000).toInt().coerceIn(0, seconds)) }
    var endS by remember(choice.uri) { mutableIntStateOf(if (choice.end > 0) (choice.end / 1000).toInt().coerceIn(0, seconds) else seconds) }

    SectionTitle("EXTRAIT À JOUER", Cp.Yellow)
    Text(
        "${formatDuration(startS * 1000L)} → ${formatDuration(endS * 1000L)}  (sur ${formatDuration(duration)})",
        color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
    RangeSlider(
        value = startS.toFloat()..endS.toFloat(),
        onValueChange = { r ->
            startS = r.start.toInt()
            endS = r.endInclusive.toInt().coerceAtLeast(startS + 1).coerceAtMost(seconds)
        },
        onValueChangeFinished = {
            onChange(choice.copy(start = startS * 1000L, end = if (endS >= seconds) 0L else endS * 1000L))
        },
        valueRange = 0f..seconds.toFloat(),
        colors = SliderDefaults.colors(thumbColor = Cp.Yellow, activeTrackColor = Cp.Yellow, inactiveTrackColor = Cp.Panel),
    )
    Text(
        "L'extrait tourne en boucle et le volume monte progressivement (réglable dans CONFIG).",
        color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp,
    )
}
