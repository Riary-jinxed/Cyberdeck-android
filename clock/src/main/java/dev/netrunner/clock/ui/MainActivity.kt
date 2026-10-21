package dev.netrunner.clock.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.clock.alarm.Notifs
import dev.netrunner.clock.alarm.Scheduler

class MainActivity : ComponentActivity() {

    private var tab by mutableIntStateOf(TAB_CLOCK)
    private var newAlarm by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifs.channels(this)
        // Cheap and idempotent: repairs anything the system dropped (force stop, HyperOS cleaning).
        Scheduler.rescheduleAll(this)
        read(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            ClockTheme {
                Box(Modifier.fillMaxSize().cyberGrid().systemBarsPadding()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (tab) {
                                TAB_CLOCK -> ClockTab(onAlarms = { tab = TAB_ALARMS })
                                TAB_ALARMS -> AlarmsTab(openNew = newAlarm, onOpened = { newAlarm = false })
                                TAB_TIMER -> TimerTab()
                                TAB_STOPWATCH -> StopwatchTab()
                                else -> SettingsTab()
                            }
                        }
                        TabBar(tab) { tab = it }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent?) {
        intent ?: return
        if (intent.hasExtra(EXTRA_TAB)) tab = intent.getIntExtra(EXTRA_TAB, TAB_CLOCK)
        if (intent.getBooleanExtra(EXTRA_NEW_ALARM, false)) newAlarm = true
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_NEW_ALARM = "new_alarm"
        const val TAB_CLOCK = 0
        const val TAB_ALARMS = 1
        const val TAB_TIMER = 2
        const val TAB_STOPWATCH = 3
        const val TAB_SETTINGS = 4
    }
}

@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("HORLOGE", "ALARMES", "MINUTEUR", "CHRONO", "CONFIG")
    Row(
        Modifier
            .fillMaxWidth()
            .background(Cp.Panel)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        tabs.forEachIndexed { i, label ->
            val on = i == selected
            Column(
                Modifier.weight(1f).clickable { onSelect(i) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label, color = if (on) Cp.Yellow else Cp.Dim, fontFamily = HudFont,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp,
                )
                Box(
                    Modifier.padding(top = 4.dp).height(2.dp).fillMaxWidth(0.6f)
                        .background(if (on) Cp.Yellow else Cp.Panel)
                )
            }
        }
    }
}
