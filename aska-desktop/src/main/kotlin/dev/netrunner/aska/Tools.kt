package dev.netrunner.aska

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Her tools: the network toolbox and Deck Link's kit, offered to the model (Ollama's tool
 * calling) and to the runner as /commands. All of them only read, except the kit edits, which wait for « oui ».
 */
object Tools {

    data class Param(val name: String, val description: String, val type: String = "string", val required: Boolean = true)

    data class Tool(val name: String, val command: String, val description: String, val params: List<Param> = emptyList(), val doing: String)

    val all = listOf(
        Tool("net_config", "ip", "La configuration réseau de ce PC : cartes, IPv4, masque, MAC, passerelle, DNS.", doing = "lit ta config IP"),
        Tool("ping", "ping", "Ping d'un hôte (nom ou IP) : réponses, pertes, temps moyen.", listOf(Param("host", "nom ou adresse IP")), "lance un ping"),
        Tool("traceroute", "trace", "Traceroute vers un hôte : les routeurs traversés.", listOf(Param("host", "nom ou adresse IP")), "suit la route"),
        Tool("dns_lookup", "dns", "Résout un nom en adresses IP, ou une IP en nom.", listOf(Param("name", "nom de domaine ou IP")), "interroge le DNS"),
        Tool("port_check", "port", "Teste si un port TCP est ouvert sur un hôte.", listOf(Param("host", "nom ou adresse IP"), Param("port", "numéro de port TCP", "integer")), "frappe à un port"),
        Tool("wifi_status", "wifi", "Le Wi-Fi de ce PC : SSID, signal, canal, débit.", doing = "écoute le Wi-Fi"),
        Tool("scan_network", "scan", "Liste les appareils qui répondent sur un sous-réseau (celui du PC par défaut) avec MAC, fabricant, nom, et s'ils sont connus du parc Deck Link.", listOf(Param("cidr", "réseau à scanner, par exemple 192.168.1.0/24 ; vide pour celui du PC", required = false)), "scanne le réseau"),
        Tool("subnet_calc", "calc", "Calcul IP : réseau, masque, broadcast, plage d'hôtes et nombre d'adresses d'un CIDR.", listOf(Param("cidr", "par exemple 10.0.12.37/22")), "fait le calcul"),
        Tool("diagnose_internet", "diag", "Diagnostic « pas d'internet » pas à pas : carte, adresse, passerelle, internet, DNS, web, et où ça casse.", doing = "déroule le diagnostic"),
        Tool("deck_search", "kit", "Cherche dans le kit Deck Link : équipements, câbles (prise, switch, port, VLAN, PoE), consommables, interventions, clients (accès, consignes, contrats) et PC du parc.", listOf(Param("query", "les mots à chercher, par exemple « B12 » ou « Dupont accès »")), "fouille le kit"),
        Tool(
            "deck_use_stock", "conso",
            "Prépare la sortie d'un consommable du kit (ce qui a été utilisé en intervention). Rien n'est enregistré avant que l'utilisateur dise oui.",
            listOf(Param("quantity", "quantité utilisée", "integer"), Param("item", "le consommable, par exemple « RJ45 »")), "prépare la sortie de stock",
        ),
        Tool(
            "deck_update", "maj",
            "Prépare la modification d'un champ d'une fiche du kit. Fiches et champs : équipement (code, name, serial, location, state, notes), consommable (name, unit, qty, threshold, location, notes), câble (outlet, switch, port, vlan, length, poe, test, site, notes), client (name, contact, phone, email, address, access, instructions, notes). Rien n'est enregistré avant que l'utilisateur dise oui.",
            listOf(
                Param("kind", "equipment, stock, cable ou client"), Param("item", "les mots qui désignent la fiche, par exemple « B12 » ou « Switch SW-2 »"),
                Param("field", "le champ à changer"), Param("value", "la nouvelle valeur"),
            ),
            "prépare la modification",
        ),
        Tool(
            "warframe_status", "wf",
            "L'état du monde de Warframe en ce moment : cycles (Plaines d'Eidolon jour ou nuit, Vallée Orbis, Désert de Cambion, Zariman) avec le temps restant, Baro Ki'Teer et son stock, sortie, chasse à l'archonte, fissures du Néant, défis Nightwave, récompense Steel Path.",
            doing = "consulte Warframe",
        ),
        Tool("deck_fleet", "parc", "Le parc de PC remonté par Deck Probe : nom, modèle, n° de série, OS, CPU, RAM, disques, IP, MAC.", doing = "ouvre la fiche du parc"),
    )

    fun definitions(): JsonArray = buildJsonArray {
        all.forEach { t ->
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", t.name)
                    put("description", t.description)
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            t.params.forEach { p -> putJsonObject(p.name) { put("type", p.type); put("description", p.description) } }
                        }
                        putJsonArray("required") { t.params.filter { it.required }.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) } }
                    }
                }
            }
        }
    }

    fun byName(name: String) = all.firstOrNull { it.name == name }

    /** "/ping 192.168.1.1" → the ping tool and its arguments; null when it is not a command. */
    fun parseCommand(input: String): Pair<Tool, Map<String, String>>? {
        val text = input.trim()
        if (!text.startsWith("/")) return null
        val words = text.drop(1).split(Regex("\\s+")).filter { it.isNotBlank() }
        val tool = all.firstOrNull { it.command == words.firstOrNull()?.lowercase() } ?: return null
        val rest = words.drop(1)
        val args = when {
            tool.params.isEmpty() -> emptyMap()
            tool.name == "deck_search" -> mapOf("query" to rest.joinToString(" "))
            tool.name == "deck_use_stock" -> mapOf("quantity" to rest.firstOrNull().orEmpty(), "item" to rest.drop(1).joinToString(" "))
            tool.name == "deck_update" -> mapOf("kind" to rest.getOrElse(0) { "" }, "item" to rest.getOrElse(1) { "" }, "field" to rest.getOrElse(2) { "" }, "value" to rest.drop(3).joinToString(" "))
            else -> tool.params.zip(rest).associate { (p, v) -> p.name to v }
        }
        return tool to args
    }

    /** "/aide": what she can do from the keyboard. */
    fun help(): String = "Commandes : " + all.joinToString(" · ") { t -> "/" + t.command + t.params.joinToString("") { " <${it.name}>" } }

    fun args(o: JsonObject): Map<String, String> = o.mapValues { (_, v) -> runCatching { v.jsonPrimitive.contentOrNull }.getOrNull().orEmpty() }

    /** A change of the kit waiting for the runner's « oui ». */
    data class Edit(val kind: String, val id: String, val fields: Map<String, String>, val summary: String)

    private val KINDS = mapOf(
        "equipment" to "equipment", "equipement" to "equipment", "stock" to "stock", "consommable" to "stock",
        "cable" to "cable", "client" to "client",
    )

    /** Finds the item and prepares the edit, or says why it cannot. */
    fun prepare(deckLink: String, kind: String, item: String, change: (DeckLink.Item) -> Pair<Map<String, String>, String>?): Pair<Edit?, String> {
        val home = DeckLink.home(deckLink) ?: return null to "Je ne trouve pas Deck Link sur ce PC."
        val found = DeckLink.find(home, kind, item)
        if (found.isEmpty()) return null to "Aucune fiche $kind ne correspond à « $item »."
        if (found.size > 1) return null to "Plusieurs fiches correspondent, il faut préciser : " + found.take(6).joinToString(", ") { it.label }
        val target = found.single()
        val (fields, summary) = change(target) ?: return null to "Cette modification n'est pas possible sur ${target.label}."
        return Edit(kind, target.id, fields, summary) to
            "Prêt, mais PAS encore enregistré : $summary. Demande à l'utilisateur de confirmer par « oui » (ou « non » pour annuler)."
    }

    /** Runs [tool] and answers in text, errors included: the model reads them too. */
    suspend fun run(tool: Tool, args: Map<String, String>, deckLink: String, propose: (Edit) -> Unit = {}): String = withContext(Dispatchers.IO) {
        fun need(name: String) = args[name]?.trim()?.takeIf { it.isNotEmpty() }
        runCatching {
            when (tool.name) {
                "net_config" -> Net.config()
                "ping" -> need("host")?.let { Net.ping(it).text() } ?: "Quel hôte faut-il pinger ?"
                "traceroute" -> need("host")?.let { Net.traceroute(it) } ?: "Vers quel hôte ?"
                "dns_lookup" -> need("name")?.let { Net.dns(it) } ?: "Quel nom faut-il résoudre ?"
                "port_check" -> {
                    val host = need("host") ?: return@runCatching "Sur quel hôte ?"
                    val port = need("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@runCatching "Quel port (1 à 65535) ?"
                    Net.port(host, port)
                }
                "wifi_status" -> Net.wifi()
                "scan_network" -> Net.scan(need("cidr"), DeckLink.fleetAddresses(deckLink))
                "subnet_calc" -> need("cidr")?.let { Net.subnet(it) } ?: "Quel réseau (exemple : 192.168.1.0/24) ?"
                "diagnose_internet" -> Net.diagnose()
                "deck_search" -> need("query")?.let { DeckLink.search(deckLink, it) } ?: "Que faut-il chercher dans le kit ?"
                "deck_fleet" -> DeckLink.fleet(deckLink)
                "warframe_status" -> Warframe.world()?.text() ?: "L'API Warframe (warframestat.us) ne répond pas pour l'instant."
                "deck_use_stock" -> {
                    val used = need("quantity")?.toLongOrNull()?.takeIf { it > 0 } ?: return@runCatching "Quelle quantité a été utilisée ?"
                    val item = need("item") ?: return@runCatching "Quel consommable ?"
                    val (edit, text) = prepare(deckLink, "stock", item) {
                        val qty = it.s("qty").toLongOrNull() ?: 0L
                        val unit = it.s("unit").let { u -> if (u.isBlank()) "" else " $u" }
                        mapOf("qty" to (qty - used).coerceAtLeast(0).toString()) to "${it.label} : $qty$unit → ${(qty - used).coerceAtLeast(0)}$unit (−$used)"
                    }
                    edit?.let(propose)
                    text
                }
                "deck_update" -> {
                    val kind = KINDS[DeckLink.normalize(need("kind").orEmpty())] ?: return@runCatching "Quelle fiche : equipment, stock, cable ou client ?"
                    val item = need("item") ?: return@runCatching "Quelle fiche faut-il modifier ?"
                    val field = need("field")?.lowercase() ?: return@runCatching "Quel champ ?"
                    if (field !in DeckLink.FIELDS.getValue(kind)) return@runCatching "Le champ « $field » n'existe pas. Champs possibles : " + DeckLink.FIELDS.getValue(kind).joinToString(", ")
                    val value = args["value"]?.trim().orEmpty()
                    if (field in setOf("qty", "threshold") && value.toLongOrNull() == null) return@runCatching "« $field » attend un nombre."
                    val (edit, text) = prepare(deckLink, kind, item) {
                        mapOf(field to value) to "${it.label}, $field : « ${it.s(field)} » → « $value »"
                    }
                    edit?.let(propose)
                    text
                }
                else -> "Outil inconnu : ${tool.name}"
            }
        }.getOrElse { "L'outil ${tool.name} a échoué : ${it.message ?: it.javaClass.simpleName}" }
    }
}
