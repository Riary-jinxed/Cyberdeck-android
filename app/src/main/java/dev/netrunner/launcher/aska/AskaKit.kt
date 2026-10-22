package dev.netrunner.launcher.aska

import android.content.Context
import dev.netrunner.launcher.kit.CableRecord
import dev.netrunner.launcher.kit.Equipment
import dev.netrunner.launcher.kit.KitStore
import java.text.Normalizer
import java.util.Locale

/**
 * The field kit by voice: where a piece of equipment is, adding one to the inventory or
 * changing its state, and the cable survey (recording an outlet, finding where it goes).
 * Understood without the model; every change is confirmed by the runner first.
 */
object AskaKit {

    sealed class Request {
        data class Where(val query: String) : Request()
        data class Count(val state: String?) : Request()
        data class Act(val action: AlarmAction) : Request()
        data class Outlet(val outlet: String) : Request()
        data class Port(val port: String, val switchName: String?) : Request()
    }

    // On the normalized text.
    private val WHERE = Regex("^(?:ou (?:est|sont|se trouve(?:nt)?|j'ai (?:mis|range))|cherche|trouve)\\s+(?:dans (?:l'|mon )?inventaire\\s+)?(?:le |la |l'|les |mon |ma |mes |un |une )?(.+?)(?:\\s+dans (?:l'|mon )?inventaire)?$")
    private val COUNT = Regex("^combien (?:d'|de )(?:equipements?|materiels?|appareils?)(?:\\s+(?:sont|est))?(?:\\s+(hs|en panne|a reparer|en stock|en service|pretes?))?")
    private val STATE_WORDS = mapOf(
        "hs" to "HS", "en panne" to "HS", "mort" to "HS", "morte" to "HS",
        "a reparer" to "À réparer", "en reparation" to "À réparer",
        "en stock" to "En stock", "en service" to "En service", "installe" to "En service", "installee" to "En service",
        "prete" to "Prêté", "pretee" to "Prêté",
    )
    private val SET_STATE = Regex("^(?:le |la |l'|mon |ma )?(.+?)\\s+(?:est|passe|est passe|est maintenant)\\s+(hs|en panne|morte?|a reparer|en reparation|en stock|en service|installee?|pretee?)$")
    private val OUTLET_FIND = Regex("^(?:ou (?:va|arrive|part|est branchee)|sur quel port (?:est|arrive)|quel port pour|ou est branchee)\\s+(?:la )?prise\\s+([\\w./-]+)")
    private val PORT_FIND = Regex("^(?:qu'est-ce qu'il y a|quoi|qu'y a-t-il|qui est|quelle prise est)\\s+(?:sur|branche sur)\\s+(?:le )?port\\s+([\\w./-]+)(?:\\s+(?:du|de|sur le)\\s+(?:switch\\s+)?([\\w./-]+))?")

    // On the original text: names keep their case.
    private val ADD = Regex(
        "^\\s*(?:as//ka\\s*,?\\s*)?(?:ajoute|enregistre|mets)(?:[- ]moi)?\\s+(?:dans|a|à)\\s+(?:l'|mon\\s+)?inventaire\\s*:?\\s*(.+)$|^\\s*(?:nouvel?\\s+(?:equipement|équipement|appareil)|inventaire)\\s*:\\s*(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val CABLE_TRIGGER = Regex("^\\s*(?:as//ka\\s*,?\\s*)?(?:relev[ée]|nouveau\\s+relev[ée]|enregistre|note|ajoute|c[âa]ble)\\s.*\\bprise\\b", RegexOption.IGNORE_CASE)

    fun parse(context: Context, input: String): Request? {
        val t = normalize(input).trim().trimEnd('?', '.', '!', ' ')
        if (CABLE_TRIGGER.containsMatchIn(input)) cable(input)?.let { return Request.Act(AlarmAction.AddCable(it)) }
        ADD.find(input)?.let { m -> equipment(m.groupValues.drop(1).first { it.isNotBlank() })?.let { return Request.Act(AlarmAction.AddEquipment(it)) } }
        OUTLET_FIND.find(t)?.let { return Request.Outlet(it.groupValues[1]) }
        PORT_FIND.find(t)?.let { return Request.Port(it.groupValues[1], it.groupValues[2].ifBlank { null }) }
        COUNT.find(t)?.let { m -> return Request.Count(m.groupValues[1].ifBlank { null }?.let { STATE_WORDS[it.removeSuffix("s")] ?: STATE_WORDS[it] }) }
        KitStore.load(context)
        SET_STATE.find(t)?.let { m ->
            val item = KitStore.equipment.singleOrNull { matches(it, m.groupValues[1]) }
            val state = STATE_WORDS[m.groupValues[2]]
            if (item != null && state != null && item.state != state) return Request.Act(AlarmAction.SetEquipmentState(item, state))
        }
        // "Où est …" is often not about the kit: only answered when the inventory knows it.
        WHERE.find(t)?.let { m -> if (find(m.groupValues[1]).isNotEmpty()) return Request.Where(m.groupValues[1]) }
        return null
    }

    // ---- Inventory.

    private fun matches(e: Equipment, query: String): Boolean {
        val words = normalize(query).split(Regex("\\s+")).filter { it.length > 1 }
        val text = normalize(listOf(e.code, e.name, e.serial, e.notes).joinToString(" "))
        return words.isNotEmpty() && words.all { text.contains(it) }
    }

    private fun find(query: String) = KitStore.equipment.filter { matches(it, query) }

    fun whereText(context: Context, query: String): String {
        KitStore.load(context)
        val found = find(query)
        fun line(e: Equipment) = "${e.name.ifBlank { e.code }}" + (if (e.location.isNotBlank()) " · ${e.location}" else "") + " · ${e.state.lowercase(Locale.FRENCH)}"
        return when {
            found.isEmpty() -> "Rien de tel dans l'inventaire."
            found.size == 1 -> found[0].let { e ->
                (if (e.location.isNotBlank()) "${e.name.ifBlank { e.code }} : ${e.location}" else "${e.name.ifBlank { e.code }} n'a pas d'emplacement noté") +
                    ", ${e.state.lowercase(Locale.FRENCH)}." + (if (e.serial.isNotBlank()) " N° de série ${e.serial}." else "")
            }
            else -> "${found.size} équipements correspondent :\n" + found.take(8).joinToString("\n") { "▸ ${line(it)}" }
        }
    }

    fun countText(context: Context, state: String?): String {
        KitStore.load(context)
        if (state != null) {
            val n = KitStore.equipment.count { it.state == state }
            return if (n == 0) "Aucun équipement « ${state.lowercase(Locale.FRENCH)} »." else "$n équipement(s) « ${state.lowercase(Locale.FRENCH)} »."
        }
        if (KitStore.equipment.isEmpty()) return "L'inventaire est vide."
        return "${KitStore.equipment.size} équipements : " +
            KitStore.equipment.groupBy { it.state }.entries.joinToString(", ") { "${it.value.size} ${it.key.lowercase(Locale.FRENCH)}" } + "."
    }

    /** "Borne Wi-Fi U6 au 2e étage, numéro de série 1234" → a new item. */
    private fun equipment(text: String): Equipment? {
        var rest = text.trim().trimEnd('.')
        fun take(regex: Regex): String? = regex.find(rest)?.let { m -> rest = rest.removeRange(m.range).trim(' ', ','); m.groupValues[1].trim() }
        val serial = take(Regex("(?:,\\s*)?(?:num[ée]ro de s[ée]rie|n° de s[ée]rie|s/n|sn|s[ée]rie)\\s*:?\\s*([\\w./-]+)", RegexOption.IGNORE_CASE))
        val code = take(Regex("(?:,\\s*)?code\\s*:?\\s*([\\w./-]+)", RegexOption.IGNORE_CASE))
        val state = STATE_WORDS.entries.firstOrNull { (k, _) -> Regex("\\b$k\\b").containsMatchIn(normalize(rest)) }
            ?.let { (k, v) -> rest = rest.replace(Regex("(?:,\\s*)?\\b${accented(k)}\\b", RegexOption.IGNORE_CASE), "").trim(' ', ','); v }
        val location = take(Regex("\\s+(?:au|à la|a la|à l'|a l'|dans (?:le|la|l')|en salle|sur (?:le|la))\\s+(.+)$", RegexOption.IGNORE_CASE))
        val name = rest.trim(' ', ',', ':').removePrefix("un ").removePrefix("une ").trim()
        if (name.isBlank()) return null
        return Equipment(KitStore.newId(), code.orEmpty(), name.replaceFirstChar { it.uppercase() }, serial.orEmpty(), location.orEmpty(), state ?: KitStore.States[0], "", 0)
    }

    // ---- Cable survey.

    /** "Relevé prise B12 switch SW1 port 12 VLAN 20 35 m PoE+ test OK" → a cable record. */
    private fun cable(text: String): CableRecord? {
        fun field(name: String) = Regex("\\b(?:$name)\\s*:?\\s*([\\w./-]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        val outlet = field("prise") ?: return null
        val switchName = field("switch|commutateur").orEmpty()
        val port = field("port").orEmpty()
        val vlan = field("vlan").orEmpty()
        val length = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:m|m[èe]tres?)\\b", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.replace(',', '.').orEmpty()
        val n = normalize(text)
        val poe = when {
            Regex("poe\\s*\\+\\s*\\+|poe\\+\\+").containsMatchIn(n) -> "PoE++"
            Regex("poe\\s*\\+|poe plus").containsMatchIn(n) -> "PoE+"
            Regex("\\bpoe passif\\b|\\bpassif\\b").containsMatchIn(n) -> "Passif"
            Regex("\\bsans poe\\b|\\bpas de poe\\b").containsMatchIn(n) -> "Non"
            Regex("\\bpoe\\b").containsMatchIn(n) -> "PoE"
            else -> KitStore.PoeModes[0]
        }
        val test = when {
            Regex("\\b(test )?(ok|bon|valide)\\b").containsMatchIn(n) -> "OK"
            Regex("\\b(defaut|ko|echec|mauvais)\\b").containsMatchIn(n) -> "Défaut"
            else -> KitStore.TestResults[0]
        }
        val site = Regex("\\b(?:site|chez|b[âa]timent)\\s*:?\\s*([^,]+?)\\s*(?:,|$)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (switchName.isEmpty() && port.isEmpty() && vlan.isEmpty()) return null
        return CableRecord(KitStore.newId(), outlet.uppercase(), switchName, port, vlan, length, poe, test, "", site, "", 0)
    }

    private fun cableLine(c: CableRecord) = buildString {
        append("Prise ${c.outlet}")
        if (c.switchName.isNotBlank() || c.port.isNotBlank()) append(" → ${listOf(c.switchName, c.port.ifBlank { null }?.let { "port $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")}")
        if (c.vlan.isNotBlank()) append(" · VLAN ${c.vlan}")
        if (c.lengthM.isNotBlank()) append(" · ${c.lengthM} m")
        if (c.poe != KitStore.PoeModes[0]) append(" · ${c.poe}")
        append(" · test ${c.test.lowercase(Locale.FRENCH)}")
        if (c.site.isNotBlank()) append(" · ${c.site}")
    }

    fun outletText(context: Context, outlet: String): String {
        KitStore.load(context)
        val found = KitStore.cables.filter { normalize(it.outlet) == normalize(outlet) }
        return if (found.isEmpty()) "Pas de relevé pour la prise ${outlet.uppercase()}." else found.joinToString("\n") { cableLine(it) }
    }

    fun portText(context: Context, port: String, switchName: String?): String {
        KitStore.load(context)
        val found = KitStore.cables.filter { normalize(it.port) == normalize(port) && (switchName == null || normalize(it.switchName) == normalize(switchName)) }
        return if (found.isEmpty()) "Aucun câble relevé sur le port $port${switchName?.let { " de $it" }.orEmpty()}." else found.joinToString("\n") { cableLine(it) }
    }

    // ---- Running a confirmed action.

    fun run(context: Context, action: AlarmAction): String {
        KitStore.load(context)
        return when (action) {
            is AlarmAction.AddEquipment -> {
                KitStore.upsert(context, action.item)
                "Ajouté à l'inventaire : ${action.item.name}."
            }
            is AlarmAction.SetEquipmentState -> {
                KitStore.upsert(context, action.item.copy(state = action.state))
                "${action.item.name} : ${action.state.lowercase(Locale.FRENCH)}. Inventaire à jour."
            }
            is AlarmAction.AddCable -> {
                KitStore.upsertCable(context, action.cable)
                "Relevé enregistré : ${cableLine(action.cable)}."
            }
            else -> ""
        }
    }

    fun cableSummary(c: CableRecord) = cableLine(c).uppercase(Locale.FRENCH)

    /** "a reparer" → a pattern that also matches "à réparer". */
    private fun accented(key: String) = key.map { c ->
        when (c) {
            'a' -> "[aàâ]"
            'e' -> "[eéèê]"
            ' ' -> "\\s+"
            else -> Regex.escape(c.toString())
        }
    }.joinToString("")

    private fun normalize(s: String) =
        Normalizer.normalize(s.lowercase(Locale.FRENCH), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('’', '\'')
}
