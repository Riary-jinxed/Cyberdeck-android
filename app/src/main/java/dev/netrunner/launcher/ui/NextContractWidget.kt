package dev.netrunner.launcher.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.data.Agenda
import dev.netrunner.launcher.data.Contract
import dev.netrunner.launcher.kit.ClientDocs
import dev.netrunner.launcher.kit.Clients
import dev.netrunner.launcher.kit.Logbook
import dev.netrunner.launcher.kit.Reputation
import dev.netrunner.launcher.system.DeckLinks
import dev.netrunner.launcher.system.UiSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Hm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val Day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH).withZone(ZoneId.systemDefault())

/**
 * "Prochain contrat" on the home screen: the intervention in progress if there is one,
 * otherwise the next calendar event of the week with its client sheet (call, access, docs)
 * and a button that opens an intervention for it in the field kit. The reputation rank sits
 * at the bottom.
 */
@Composable
fun NextContractWidget(onOpenKit: () -> Unit) {
    val context = LocalContext.current
    remember {
        val app = context.applicationContext
        dev.netrunner.launcher.kit.KitStore.load(app)
        dev.netrunner.launcher.kit.Coverage.load(app)
        Logbook.load(app)
        Clients.load(app)
    }
    val active = rememberScreenActive()
    val now by produceState(System.currentTimeMillis(), active) {
        while (active) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val next by produceState<Contract?>(null, active) {
        while (active) {
            value = withContext(Dispatchers.IO) { Agenda.upcoming(context, hours = 7 * 24, limit = 6) }
                .firstOrNull { it.end > System.currentTimeMillis() && !it.allDay }
            delay(5 * 60_000L)
        }
    }
    val running = Logbook.items.firstOrNull { it.open }

    Column(
        Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = Cp.Cyan.copy(alpha = 0.6f), cut = 10.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when {
            running != null -> {
                Text("// CONTRAT EN COURS", color = Cp.Red, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(running.title.ifBlank { running.site }.uppercase(), color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val minutes = ((now - running.started) / 60_000).coerceAtLeast(0)
                Text(
                    listOf(running.client, running.site, "depuis %dh%02d".format(minutes / 60, minutes % 60)).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                SmallHudButton("[ REPRENDRE LE CARNET ]", Cp.Yellow) {
                    UiSound.play(context, UiSound.Cue.Tap)
                    Logbook.setFocus(context, running.id)
                    onOpenKit()
                }
            }
            next != null -> {
                val c = next!!
                val client = remember(c.id, Clients.items.size) {
                    Clients.items.firstOrNull { cl ->
                        cl.name.length >= 3 && (c.title.contains(cl.name, ignoreCase = true) || c.location.orEmpty().contains(cl.name, ignoreCase = true))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("// PROCHAIN CONTRAT", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(countdown(c.begin, now), color = if (c.begin - now < 3_600_000L) Cp.Red else Cp.Cyan, fontFamily = MonoFont, fontSize = 12.sp)
                }
                Text(
                    c.title.uppercase(),
                    color = Cp.Text, fontFamily = HudFont, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { DeckLinks.openEvent(context, c.id) },
                )
                val day = Instant.ofEpochMilli(c.begin).atZone(ZoneId.systemDefault()).toLocalDate()
                val dayLabel = when (day) {
                    LocalDate.now() -> "aujourd'hui"
                    LocalDate.now().plusDays(1) -> "demain"
                    else -> Day.format(Instant.ofEpochMilli(c.begin))
                }
                Text(
                    listOfNotNull("$dayLabel ${Hm.format(Instant.ofEpochMilli(c.begin))}–${Hm.format(Instant.ofEpochMilli(c.end))}", c.location?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                client?.let { cl ->
                    val docs = remember(cl.id, ClientDocs.revision.intValue) { ClientDocs.list(context, cl.id).size }
                    Text(
                        listOf("Client : ${cl.name}", cl.contact, if (docs > 0) "$docs doc(s)" else "").filter { it.isNotBlank() }.joinToString(" · "),
                        color = Cp.Cyan, fontFamily = HudFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    cl.access.takeIf { it.isNotBlank() }?.let {
                        Text("Accès : $it", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val place = c.location?.takeIf { it.isNotBlank() } ?: client?.address?.takeIf { it.isNotBlank() }
                    if (place != null) {
                        SmallHudButton("[ ITINÉRAIRE ]", Cp.Cyan) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                    client?.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                        SmallHudButton("[ APPELER ]", Cp.Cyan) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone.trim()))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                    SmallHudButton("[ DÉMARRER ]", Cp.Yellow) {
                        UiSound.play(context, UiSound.Cue.Tap)
                        val i = Logbook.create(context, c.title, c.location.orEmpty(), client?.name.orEmpty(), "")
                        Logbook.setFocus(context, i.id)
                        onOpenKit()
                    }
                }
            }
            !Agenda.granted(context) -> {
                Text("// PROCHAIN CONTRAT", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("> relie l'agenda (widget Zone ci-dessous) pour voir ton prochain contrat", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
            }
            else -> {
                Text("// PROCHAIN CONTRAT", color = Cp.Yellow, fontFamily = HudFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("> aucun contrat cette semaine", color = Cp.Dim, fontFamily = HudFont, fontSize = 11.sp)
            }
        }
        // Reputation, one line.
        val faction = Cp.faction
        val status = remember(Logbook.items.toList(), faction) { Reputation.status(context, faction) }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).clickable { onOpenKit() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${Reputation.TITLE} · RANG ${status.rank.level} ${status.rank.title.uppercase()}",
                color = Cp.Yellow, fontFamily = HudFont, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text("${status.points} pts", color = Cp.Dim, fontFamily = MonoFont, fontSize = 11.sp)
        }
        RankBar(status, height = 3)
    }
}

private fun countdown(begin: Long, now: Long): String {
    val m = (begin - now) / 60_000
    return when {
        m <= 0 -> "EN COURS"
        m < 60 -> "T-${m} MIN"
        m < 24 * 60 -> "T-%dH%02d".format(m / 60, m % 60)
        else -> "J-${m / (24 * 60)}"
    }
}
