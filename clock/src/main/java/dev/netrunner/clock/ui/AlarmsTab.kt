package dev.netrunner.clock.ui

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.netrunner.clock.alarm.Clockwork
import dev.netrunner.clock.alarm.TonePlayer
import dev.netrunner.clock.data.Alarm
import dev.netrunner.clock.data.AlarmStore

/** The saved alarms, refreshed whenever they change (here or from a ring / intent). */
@Composable
fun rememberAlarms(): List<Alarm> {
    val context = LocalContext.current
    var list by remember { mutableStateOf(AlarmStore.all(context)) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { list = AlarmStore.all(context) }
        AlarmStore.listeners += l
        l()
        onDispose { AlarmStore.listeners -= l }
    }
    return list
}

@Composable
fun AlarmsTab(openNew: Boolean, onOpened: () -> Unit) {
    val context = LocalContext.current
    val alarms = rememberAlarms()
    val now by rememberNow(30_000L)
    var editing by remember { mutableStateOf<Alarm?>(null) }

    LaunchedEffect(openNew) {
        if (openNew) {
            editing = Alarm(id = -1, hour = 7, minute = 0)
            onOpened()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("ALARMES", Cp.Yellow, Modifier.weight(1f))
            HudButton("+ NOUVELLE", filled = true) { editing = Alarm(id = -1, hour = 7, minute = 0) }
        }
        Spacer(Modifier.height(12.dp))
        if (alarms.isEmpty()) {
            Text(
                "Aucune alarme. Ajoute-en une, ou demande à As//ka : « réveille-moi à 7h30 ».",
                color = Cp.Dim, fontFamily = HudFont, fontSize = 16.sp,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(alarms, key = { it.id }) { alarm ->
                AlarmRow(alarm, now, onClick = { editing = alarm }, onToggle = { Clockwork.setEnabled(context, alarm, it) })
            }
        }
    }

    editing?.let { draft ->
        AlarmEditor(
            initial = draft,
            onDismiss = { editing = null },
            onSave = { a ->
                if (a.id < 0) Clockwork.saveAlarm(context, a.copy(id = AlarmStore.newId(context))) else Clockwork.saveAlarm(context, a)
                editing = null
            },
            onDelete = if (draft.id < 0) null else ({ Clockwork.deleteAlarm(context, draft.id); editing = null }),
        )
    }
}

@Composable
private fun AlarmRow(alarm: Alarm, now: Long, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val color = if (alarm.enabled) Cp.Yellow else Cp.Dim
    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = color.copy(alpha = if (alarm.enabled) 0.8f else 0.4f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(hhmm(alarm.hour, alarm.minute), color = color, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 34.sp)
            val info = buildList {
                add(Alarm.daysLabel(alarm.days))
                if (alarm.label.isNotEmpty()) add("« ${alarm.label} »")
                if (alarm.snoozedUntil > now) add("EN RÉPIT")
                else alarm.nextTrigger(now)?.let { add(untilLabel(it - now)) }
            }
            Text(info.joinToString(" · "), color = if (alarm.enabled) Cp.Cyan else Cp.Dim, fontFamily = HudFont, fontSize = 14.sp)
        }
        Switch(checked = alarm.enabled, onCheckedChange = onToggle, colors = hudSwitch())
    }
}

@Composable
fun hudSwitch() = SwitchDefaults.colors(
    checkedThumbColor = Cp.Background, checkedTrackColor = Cp.Yellow,
    uncheckedThumbColor = Cp.Dim, uncheckedTrackColor = Cp.Panel, uncheckedBorderColor = Cp.Dim,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(initial: Alarm, onDismiss: () -> Unit, onSave: (Alarm) -> Unit, onDelete: (() -> Unit)?) {
    val context = LocalContext.current
    val picker = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    var days by remember { mutableStateOf(initial.days) }
    var label by remember { mutableStateOf(initial.label) }
    var vibrate by remember { mutableStateOf(initial.vibrate) }
    var tone by remember { mutableStateOf(ToneChoice(initial.tone, initial.toneStart, initial.toneEnd)) }
    var choosingTone by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .hudPanel(borderColor = Cp.Yellow, fill = Cp.Background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionTitle(if (initial.id < 0) "NOUVELLE ALARME" else "MODIFIER L'ALARME", Cp.Yellow)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(
                    state = picker,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Cp.Panel,
                        selectorColor = Cp.Yellow,
                        clockDialSelectedContentColor = Cp.Background,
                        clockDialUnselectedContentColor = Cp.Text,
                        timeSelectorSelectedContainerColor = Cp.Yellow,
                        timeSelectorUnselectedContainerColor = Cp.Panel,
                        timeSelectorSelectedContentColor = Cp.Background,
                        timeSelectorUnselectedContentColor = Cp.Text,
                    ),
                )
            }
            SectionTitle("RÉPÉTITION")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Alarm.WEEK.forEach { (day, letter) ->
                    val on = day in days
                    Box(
                        Modifier
                            .size(38.dp)
                            .background(if (on) Cp.Yellow else Cp.Panel, hudShape(6.dp))
                            .clickable { days = if (on) days - day else days + day },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(letter, color = if (on) Cp.Background else Cp.Text, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
            Text(Alarm.daysLabel(days), color = Cp.Dim, fontFamily = HudFont, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = label, onValueChange = { label = it.take(60) },
                label = { Text("Libellé", fontFamily = HudFont) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = hudField(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f)).clickable { choosingTone = true }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SONNERIE", color = Cp.Cyan, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(tone.describe(context), color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp)
                }
                Text("▸", color = Cp.Cyan, fontSize = 20.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vibreur", color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(checked = vibrate, onCheckedChange = { vibrate = it }, colors = hudSwitch())
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onDelete != null) HudButton("SUPPRIMER", color = Cp.Red, onClick = onDelete)
                Spacer(Modifier.weight(1f))
                HudButton("ANNULER", color = Cp.Dim, onClick = onDismiss)
                HudButton("ARMER", filled = true) {
                    onSave(
                        initial.copy(
                            hour = picker.hour, minute = picker.minute, days = days, label = label.trim(), vibrate = vibrate,
                            tone = tone.uri, toneStart = tone.start, toneEnd = tone.end, enabled = true, snoozedUntil = 0L,
                        )
                    )
                }
            }
        }
    }

    if (choosingTone) {
        ToneDialog(current = tone, onDismiss = { choosingTone = false }, onChoose = { tone = it; choosingTone = false })
    }
}

data class ToneChoice(val uri: String?, val start: Long = 0L, val end: Long = 0L) {
    fun describe(context: Context): String {
        val name = TonePlayer.title(context, uri)
        return if (start > 0 || end > 0) "$name (${formatDuration(start)} → ${if (end > 0) formatDuration(end) else "fin"})" else name
    }
}

@Composable
fun hudField() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cp.Yellow, unfocusedBorderColor = Cp.Dim,
    focusedLabelColor = Cp.Yellow, unfocusedLabelColor = Cp.Dim,
    focusedTextColor = Cp.Text, unfocusedTextColor = Cp.Text, cursorColor = Cp.Yellow,
)
