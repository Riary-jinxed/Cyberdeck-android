package dev.netrunner.launcher.ui

import android.app.AlarmManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.netrunner.launcher.aska.AskaDay
import dev.netrunner.launcher.aska.AskaPet
import dev.netrunner.launcher.aska.AskaPet.Mood
import dev.netrunner.launcher.aska.AskaReminders
import dev.netrunner.launcher.aska.dayLabel
import dev.netrunner.launcher.aska.hhmm
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * As//ka on the home screen: her chibi in her current mood, her line of the day, and what
 * comes next (a reminder or the next alarm). Touch to talk to her. Static: it is read again
 * each time the home screen comes back, and costs nothing in between.
 */
@Composable
fun AskaWidget(pet: AskaPet, onOpen: () -> Unit) {
    val context = LocalContext.current
    // Re-read on each return to the home screen.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        pet.refresh()
        resumed++
        onPauseOrDispose { }
    }
    val revision = AskaReminders.revision.intValue
    val next = remember(resumed, revision) { nextUp(context) }
    val mood = pet.mood()
    // Her needs first; otherwise her line of the day.
    val daily = remember(resumed) { lineOfTheDay() }
    // The phone first (low battery, charging, very late), then her needs, then her line of the day.
    val moment = remember(resumed) { AskaDay.moment(context)?.let { AskaDay.momentLine(context, it) } }
    val line = moment ?: if (mood in NEEDS && pet.line.isNotEmpty()) pet.line else daily

    Row(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = AskaColors.Pink.copy(alpha = 0.6f), fill = AskaColors.Void.copy(alpha = 0.8f), cut = 10.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 62.dp, height = 72.dp), contentAlignment = Alignment.BottomCenter) {
            AskaPad(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp),
                color = Color(pet.looks.getValue(AskaPet.Slot.Pad).argb),
            )
            Image(
                painterResource(mood.drawable()),
                contentDescription = "As//ka",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 4.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("AS//KA ◆ NIV ${pet.stats.level}", color = AskaColors.Pink, fontFamily = MonoFont, fontSize = 10.sp)
            Text(
                line,
                color = Cp.Text,
                fontFamily = HudFont,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            next?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = AskaColors.HotPink, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val NEEDS = setOf(Mood.Hungry, Mood.Tired, Mood.Bored, Mood.Sleeping)

/** The reminder or the alarm that comes first. */
private fun nextUp(context: android.content.Context): String? {
    val reminder = AskaReminders.upcoming(context).firstOrNull { it.at > System.currentTimeMillis() }
    val alarm = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
    return when {
        reminder != null && (alarm == null || reminder.at <= alarm) -> "◷ ${reminder.whenLabel.uppercase()} · ${reminder.text}"
        alarm != null -> {
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(alarm), ZoneId.systemDefault())
            "⏰ ALARME ${dayLabel(alarm).uppercase()} ${hhmm(at.hour, at.minute)}"
        }
        else -> null
    }
}

private fun lineOfTheDay(): String {
    val lines = listOf(
        "Le réseau est calme aujourd'hui… parfait pour une pause pâtisserie.",
        "Héhé, j'ai trié tes icônes pendant que tu dormais. Non, je plaisante. Presque.",
        "Rappel amical : boire de l'eau, c'est comme refroidir son deck. Indispensable.",
        "Si tu as quelque chose à ne pas oublier, dis « rappelle-moi… ». Je veille.",
        "Je suis là si tu veux parler. Ou juste me donner un macaron.",
        "Petit conseil de dragonne : une tâche à la fois, et la journée se hacke toute seule.",
        "Euh… tu as bonne mine aujourd'hui. Voilà. C'est dit.",
        "Systèmes au vert, écailles brillantes. On fait quoi de beau ?",
    )
    return lines[(LocalDate.now().toEpochDay() % lines.size).toInt()]
}
