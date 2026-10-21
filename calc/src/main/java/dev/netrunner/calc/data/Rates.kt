package dev.netrunner.calc.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Euro exchange rates from the European Central Bank (via Frankfurter, no key), kept for offline use. */
object Rates {
    data class Table(val date: String, val rates: Map<String, Double>)

    private const val URL_LATEST = "https://api.frankfurter.app/latest"

    /** French names for the currencies the ECB publishes. */
    val names = mapOf(
        "EUR" to "Euro", "USD" to "Dollar américain", "GBP" to "Livre sterling", "CHF" to "Franc suisse",
        "JPY" to "Yen", "CAD" to "Dollar canadien", "AUD" to "Dollar australien", "NZD" to "Dollar néo-zélandais",
        "CNY" to "Yuan", "HKD" to "Dollar de Hong Kong", "SGD" to "Dollar de Singapour", "KRW" to "Won",
        "INR" to "Roupie indienne", "IDR" to "Roupie indonésienne", "THB" to "Baht", "PHP" to "Peso philippin",
        "MYR" to "Ringgit", "SEK" to "Couronne suédoise", "NOK" to "Couronne norvégienne", "DKK" to "Couronne danoise",
        "ISK" to "Couronne islandaise", "PLN" to "Zloty", "CZK" to "Couronne tchèque", "HUF" to "Forint",
        "RON" to "Leu roumain", "BGN" to "Lev", "TRY" to "Livre turque", "ILS" to "Shekel",
        "ZAR" to "Rand", "BRL" to "Real", "MXN" to "Peso mexicain",
    )

    /** Shown first, in this order; the others follow alphabetically. */
    val favourites = listOf("EUR", "USD", "GBP", "CHF", "JPY", "CAD", "CNY")

    private fun prefs(context: Context) = context.getSharedPreferences("calc", Context.MODE_PRIVATE)

    fun cached(context: Context): Table? = prefs(context).getString("rates", null)?.let { runCatching { parse(it) }.getOrNull() }

    suspend fun refresh(context: Context): Table? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(URL_LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val table = parse(body)
            prefs(context).edit().putString("rates", body).apply()
            table
        }.getOrNull()
    }

    private fun parse(body: String): Table {
        val json = JSONObject(body)
        val base = json.optString("base", "EUR")
        val r = json.getJSONObject("rates")
        val rates = mutableMapOf(base to 1.0)
        r.keys().forEach { rates[it] = r.getDouble(it) }
        return Table(json.optString("date"), rates)
    }

    fun ordered(rates: Map<String, Double>): List<String> =
        favourites.filter { it in rates } + rates.keys.filter { it !in favourites }.sorted()
}
