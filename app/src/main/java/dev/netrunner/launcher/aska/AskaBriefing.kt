package dev.netrunner.launcher.aska

import android.content.Context
import dev.netrunner.launcher.data.Agenda
import dev.netrunner.launcher.data.Contract
import dev.netrunner.launcher.kit.Client
import dev.netrunner.launcher.kit.Clients
import dev.netrunner.launcher.kit.KitStore
import dev.netrunner.launcher.kit.Logbook
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The intervention briefing: before going on site, the client's sheet (contact, access, site
 * rules), what the logbook says of the last visits, and where the checklists stand. Read from
 * the field kit, no model needed.
 */
object AskaBriefing {

    /** Who the briefing is about: a name, or null for the next contract. */
    data class Request(val client: String?)

    private val ASK = Regex(
        "^(?:fais[- ]moi\\s+(?:le\\s+|un\\s+)?|donne[- ]moi\\s+(?:le\\s+|un\\s+)?)?(?:briefing|brief)(?:\\s+(?:d'|de l'|pour l'|de mon |de la |du )?(?:intervention|client|contrat|chantier|rdv|rendez-vous))?(?:\\s+(?:chez|pour|de|du|sur|client)\\s+)?\\s*(.*)$" +
            "|^(?:prepare|preparer)[- ]?(?:moi\\s+)?(?:mon |l'|la |le |ma )?(?:intervention|rendez-vous|rdv|visite|chantier)(?:\\s+(?:chez|pour|de|du)\\s+(.+))?$" +
            "|^(?:qu'est-ce que je dois savoir|que dois-je savoir|quoi savoir)\\s+(?:sur|pour|avant)\\s+(?:le client |mon intervention chez |l'intervention chez |chez )?(.+)$" +
            "|^(?:ma |la )?prochaine intervention$",
    )

    fun parse(input: String): Request? {
        val t = fold(input).trim().trimEnd('?', '.', '!', ' ')
        val m = ASK.find(t) ?: return null
        val name = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("chez ")?.trim()?.ifEmpty { null }
        // "Briefing", "briefing du jour": the morning briefing, not this one.
        if (name != null && Regex("^(du |de la |de )?(jour|matin|journee|aujourd'hui)$").matches(name)) return null
        if (name == null && !Regex("intervention|client|contrat|chantier|rdv|rendez-vous|visite").containsMatchIn(t)) return null
        return Request(name)
    }

    private fun load(context: Context) {
        KitStore.load(context)
        Logbook.load(context)
        Clients.load(context)
    }

    /** The client whose name appears in [text] (the longest name wins). */
    private fun clientIn(text: String): String? {
        val t = " " + words(text) + " "
        return Clients.knownNames().filter { words(it).length >= 3 && t.contains(" " + words(it) + " ") }.maxByOrNull { it.length }
    }

    /** The client a name points to: exact, then containing it. */
    private fun clientNamed(query: String): String? {
        val q = words(query)
        val names = Clients.knownNames()
        return names.firstOrNull { words(it) == q } ?: names.filter { words(it).contains(q) || q.contains(words(it)) }.minByOrNull { it.length }
    }

    /** The next contract in the calendar that names a known client, within two days. */
    private fun nextContract(context: Context): Pair<Contract, String>? =
        Agenda.upcoming(context, hours = 48, limit = 10).firstNotNullOfOrNull { c ->
            clientIn(c.title + " " + c.location.orEmpty())?.let { c to it }
        }

    fun text(context: Context, request: Request): String {
        load(context)
        val next = nextContract(context)
        val name = request.client?.let { clientNamed(it) ?: return "Je ne connais pas de client « ${request.client} » dans le kit terrain." }
            ?: next?.second
            ?: Logbook.get(Logbook.focusId)?.client?.ifBlank { null }
            ?: Logbook.items.filter { it.open }.maxByOrNull { it.started }?.client?.ifBlank { null }
            ?: return "Pas de contrat lié à une fiche client dans les 48 h. Dis « briefing Dupont » avec le nom du client."
        val sheet = Clients.byName(name)
        val visits = Clients.interventionsOf(name)
        return buildString {
            append("Briefing · ${sheet?.name ?: name}")
            next?.takeIf { it.second.equals(name, ignoreCase = true) }?.first?.let { c ->
                append("\n▸ Contrat : ${c.title} · ${whenLabel(c.begin, c.allDay)}" + (c.location?.let { " · $it" } ?: ""))
            }
            sheet?.let { append(sheetLines(it)) } ?: append("\n▸ Pas de fiche client : crée-la dans le kit terrain (Clients).")
            if (visits.isEmpty()) {
                append("\n▸ Historique : première intervention chez ce client.")
            } else {
                val last = visits.first()
                append("\n▸ Historique : ${visits.size} intervention(s). Dernière le ${day(last.started)}")
                if (last.title.isNotBlank()) append(" · ${last.title}")
                if (last.site.isNotBlank()) append(" · ${last.site}")
                if (last.open) append(" (en cours)")
                last.journal.takeLast(3).forEach { append("\n   · ${day(it.at)} : ${it.text.take(120)}") }
                if (last.notes.isNotBlank()) append("\n   · Notes : ${last.notes.take(200)}")
                val sites = Clients.sitesOf(name)
                if (sites.size > 1) append("\n▸ Sites connus : ${sites.joinToString(", ")}")
            }
            // Checklists: what is left on the open visit, else how the last ones ended.
            val runs = visits.firstOrNull { it.open }?.let { Logbook.runsOf(it) }?.takeIf { it.isNotEmpty() }
                ?: visits.firstOrNull()?.let { Logbook.runsOf(it) }.orEmpty()
            runs.forEach { r ->
                val left = r.steps.indices.filter { it !in r.done }
                append("\n▸ Checklist ${r.name} : ${r.done.size}/${r.steps.size}")
                if (left.isNotEmpty() && left.size <= 6) append(" · reste : " + left.joinToString(", ") { r.steps[it] })
            }
            if (runs.isEmpty() && KitStore.templates.isNotEmpty()) {
                append("\n▸ Checklists dispo : ${KitStore.templates.take(4).joinToString(", ") { it.name }}")
            }
        }
    }

    private fun sheetLines(c: Client) = buildString {
        val contact = listOf(c.contact, c.phone).filter { it.isNotBlank() }.joinToString(" · ")
        if (contact.isNotBlank()) append("\n▸ Contact : $contact")
        if (c.address.isNotBlank()) append("\n▸ Adresse : ${c.address}")
        if (c.access.isNotBlank()) append("\n▸ Accès : ${c.access}")
        if (c.instructions.isNotBlank()) append("\n▸ Consignes : ${c.instructions}")
        if (c.notes.isNotBlank()) append("\n▸ Notes : ${c.notes.take(240)}")
    }

    /** For the morning briefing: today's contracts that have a client sheet. */
    fun todayHint(context: Context): String? {
        load(context)
        if (Clients.knownNames().isEmpty()) return null
        val today = LocalDate.now()
        val names = Agenda.upcoming(context, hours = 24, limit = 8)
            .filter { Instant.ofEpochMilli(it.begin).atZone(ZoneId.systemDefault()).toLocalDate() == today || it.allDay }
            .mapNotNull { clientIn(it.title + " " + it.location.orEmpty()) }.distinct()
        if (names.isEmpty()) return null
        return "Fiche client prête pour " + names.joinToString(", ") + " : dis « briefing ${names.first()} »."
    }

    private fun whenLabel(at: Long, allDay: Boolean): String {
        val d = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        val dayWord = when (d.toLocalDate()) {
            LocalDate.now() -> "aujourd'hui"
            LocalDate.now().plusDays(1) -> "demain"
            else -> DateTimeFormatter.ofPattern("EEEE d", Locale.FRENCH).format(d)
        }
        return if (allDay) dayWord else "$dayWord ${hhmm(d.hour, d.minute)}"
    }

    private fun day(at: Long) = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    private fun words(s: String) = fold(s).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun fold(s: String) =
        Normalizer.normalize(s.lowercase(Locale.FRENCH), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('’', '\'')
}
