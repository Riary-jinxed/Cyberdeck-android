package dev.netrunner.agenda.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.agenda.data.CalendarRepo
import dev.netrunner.agenda.data.Contract
import dev.netrunner.deck.Cp
import dev.netrunner.deck.DeckTheme
import dev.netrunner.deck.HudButton
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.TabBar
import dev.netrunner.deck.cyberGrid
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {

    private lateinit var state: AgendaState
    private var granted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        state = AgendaState(this)
        granted = CalendarRepo.granted(this)
        setContent {
            DeckTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    granted = CalendarRepo.granted(this)
                    if (granted) { state.start(); read(intent) }
                }
                DisposableEffect(granted) {
                    if (granted) state.start()
                    onDispose { if (granted) state.stop() }
                }
                Box(Modifier.fillMaxSize().cyberGrid().systemBarsPadding()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                tab == 2 -> SettingsTab(state)
                                !granted -> Gate { ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }
                                tab == 0 -> ContractsTab(state, onOpen = { state.editing = it }, onNew = { state.editing = newContract(LocalDate.now()) })
                                else -> MonthTab(state, onOpen = { state.editing = it }, onNew = { state.editing = newContract(it) })
                            }
                        }
                        TabBar(listOf("CONTRATS", "MOIS", "CONFIG"), tab) { tab = it }
                    }
                }
                state.editing?.let { EditorDialog(state, it) { state.editing = null } }
            }
        }
        if (granted) read(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (granted) read(intent)
    }

    /** VIEW/EDIT of an event (the launcher's HUD, other apps) or INSERT with the standard extras. */
    private fun read(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> {
                val id = intent.data?.lastPathSegment?.toLongOrNull() ?: return
                lifecycleScope.launch {
                    val event = withContext(Dispatchers.IO) { runCatching { CalendarRepo.event(this@MainActivity, id) }.getOrNull() }
                    // The calendar list first, so the editor can name and pick calendars.
                    state.load(0, 1)
                    if (event != null) state.editing = event
                }
            }
            Intent.ACTION_INSERT -> {
                val now = System.currentTimeMillis()
                val begin = intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, nextHour(now))
                val end = intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 3_600_000)
                lifecycleScope.launch {
                    state.load(0, 1)
                    state.editing = Contract(
                        eventId = -1,
                        title = intent.getStringExtra(CalendarContract.Events.TITLE) ?: "",
                        begin = begin, end = end,
                        allDay = intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false),
                        location = intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION),
                        description = intent.getStringExtra(CalendarContract.Events.DESCRIPTION),
                    )
                }
            }
        }
    }

    companion object {
        /** A one-hour contract at the next full hour of [day] (today) or at 9:00 (another day). */
        fun newContract(day: LocalDate): Contract {
            val zone = ZoneId.systemDefault()
            val begin = if (day == LocalDate.now()) nextHour(System.currentTimeMillis())
            else day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
            return Contract(eventId = -1, title = "", begin = begin, end = begin + 3_600_000, allDay = false)
        }

        fun nextHour(now: Long): Long = (now / 3_600_000 + 1) * 3_600_000
    }
}

@Composable
private fun Gate(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("LIAISON AGENDA REQUISE", color = Cp.Red, fontFamily = HudFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 2.sp)
        Text(
            "L'agenda lit et écrit le calendrier d'Android, pour rester synchronisé avec Google Agenda. Rien n'est envoyé ailleurs.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, modifier = Modifier.padding(vertical = 14.dp),
        )
        HudButton("AUTORISER", filled = true, modifier = Modifier.fillMaxWidth(), onClick = onGrant)
    }
}
