package dev.netrunner.launcher.kit

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A customer: who to call, how to get in, what to watch out for on site. */
data class Client(
    val id: String,
    val name: String,
    val contact: String,
    val phone: String,
    val email: String,
    val address: String,
    /** Opening hours, badge, key box, parking… */
    val access: String,
    /** Site rules: safety, who validates, where the network room is. */
    val instructions: String,
    val notes: String,
    val updated: Long,
    val contracts: List<ClientContract> = emptyList(),
    val quotes: List<Quote> = emptyList(),
)

/** A contract with the client (maintenance, support hours…). Dates are local midnights, 0 when unset. */
data class ClientContract(
    val id: String,
    val ref: String,
    val kind: String,
    val start: Long,
    val end: Long,
    val amount: String,
    val notes: String,
) {
    enum class State { Upcoming, Active, Ending, Expired }

    fun state(now: Long = System.currentTimeMillis()): State = when {
        start > 0 && now < start -> State.Upcoming
        end > 0 && now >= end + DAY -> State.Expired
        end > 0 && end - now < 30 * DAY -> State.Ending
        else -> State.Active
    }

    /** Whole days left before the end date, for "expire dans n j". */
    fun daysLeft(now: Long = System.currentTimeMillis()): Long = if (end == 0L) -1 else ((end + DAY - now) / DAY).coerceAtLeast(0)
}

enum class QuoteState(val id: String, val label: String) {
    Draft("draft", "Brouillon"), Sent("sent", "Envoyé"), Accepted("accepted", "Accepté"), Refused("refused", "Refusé");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Draft
    }
}

/** A quote sent (or to send) to the client. */
data class Quote(
    val id: String,
    val ref: String,
    val label: String,
    val date: Long,
    val amount: String,
    val state: QuoteState,
    val notes: String,
)

private const val DAY = 86_400_000L

/**
 * Customer sheets, kept in kit_clients.json. An intervention belongs to a client through its
 * client name (case ignored), so sheets and logbook stay linked without ids to maintain.
 */
object Clients {
    val items = mutableStateListOf<Client>()
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "kit_clients.json")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        if (!f.exists()) return
        runCatching {
            val a = JSONObject(f.readText()).optJSONArray("clients") ?: return
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                items += Client(
                    o.getString("id"), o.optString("name"), o.optString("contact"), o.optString("phone"),
                    o.optString("email"), o.optString("address"), o.optString("access"), o.optString("instructions"),
                    o.optString("notes"), o.optLong("updated"),
                    o.optJSONArray("contracts").objects().map {
                        ClientContract(
                            it.optString("id"), it.optString("ref"), it.optString("kind"), it.optLong("start"),
                            it.optLong("end"), it.optString("amount"), it.optString("notes"),
                        )
                    },
                    o.optJSONArray("quotes").objects().map {
                        Quote(
                            it.optString("id"), it.optString("ref"), it.optString("label"), it.optLong("date"),
                            it.optString("amount"), QuoteState.of(it.optString("state")), it.optString("notes"),
                        )
                    },
                )
            }
        }
    }

    private fun save(context: Context) {
        val root = JSONObject().put("clients", JSONArray(items.map { c ->
            JSONObject().put("id", c.id).put("name", c.name).put("contact", c.contact).put("phone", c.phone)
                .put("email", c.email).put("address", c.address).put("access", c.access)
                .put("instructions", c.instructions).put("notes", c.notes).put("updated", c.updated)
                .put("contracts", JSONArray(c.contracts.map {
                    JSONObject().put("id", it.id).put("ref", it.ref).put("kind", it.kind).put("start", it.start)
                        .put("end", it.end).put("amount", it.amount).put("notes", it.notes)
                }))
                .put("quotes", JSONArray(c.quotes.map {
                    JSONObject().put("id", it.id).put("ref", it.ref).put("label", it.label).put("date", it.date)
                        .put("amount", it.amount).put("state", it.state.id).put("notes", it.notes)
                }))
        }))
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(f)
        }
    }

    fun byName(name: String): Client? = name.trim().takeIf { it.isNotEmpty() }?.let { n -> items.firstOrNull { it.name.equals(n, ignoreCase = true) } }

    fun upsert(context: Context, client: Client) {
        val c = client.copy(name = client.name.trim(), updated = System.currentTimeMillis())
        val k = items.indexOfFirst { it.id == c.id }
        if (k >= 0) items[k] = c else items += c
        items.sortBy { it.name.lowercase() }
        save(context)
    }

    private fun edit(context: Context, clientId: String, change: (Client) -> Client) {
        items.firstOrNull { it.id == clientId }?.let { upsert(context, change(it)) }
    }

    fun putContract(context: Context, clientId: String, contract: ClientContract) = edit(context, clientId) { c ->
        val list = c.contracts.filterNot { it.id == contract.id } + contract
        c.copy(contracts = list.sortedByDescending { it.start })
    }

    fun removeContract(context: Context, clientId: String, id: String) =
        edit(context, clientId) { c -> c.copy(contracts = c.contracts.filterNot { it.id == id }) }

    fun putQuote(context: Context, clientId: String, quote: Quote) = edit(context, clientId) { c ->
        val list = c.quotes.filterNot { it.id == quote.id } + quote
        c.copy(quotes = list.sortedByDescending { it.date })
    }

    fun removeQuote(context: Context, clientId: String, id: String) =
        edit(context, clientId) { c -> c.copy(quotes = c.quotes.filterNot { it.id == id }) }

    /** Contract and quote references, for the client search. */
    fun refsOf(c: Client): List<String> = c.contracts.flatMap { listOf(it.ref, it.kind) } + c.quotes.flatMap { listOf(it.ref, it.label) }

    fun delete(context: Context, id: String) {
        items.removeAll { it.id == id }
        save(context)
        ClientDocs.deleteAll(context, id)
    }

    /** Client names from the sheets and from the logbook, for suggestions. */
    fun knownNames(): List<String> =
        (items.map { it.name } + Logbook.items.map { it.client })
            .map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    fun interventionsOf(name: String): List<Intervention> =
        Logbook.items.filter { it.client.isNotBlank() && it.client.trim().equals(name.trim(), ignoreCase = true) }
            .sortedByDescending { it.started }

    fun sitesOf(name: String): List<String> = interventionsOf(name).map { it.site.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
}
