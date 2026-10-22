package dev.netrunner.osint.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Plain GETs to the public services the lookups rely on. */
object Net {
    const val AGENT = "NetrunnerOSINT/0.1 (Android)"

    /** The body of [url], at most [limit] bytes. Throws on network errors and non-2xx answers. */
    suspend fun get(url: String, accept: String = "application/json", timeout: Int = 15_000, limit: Int = 4_000_000): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeout
        conn.readTimeout = timeout
        conn.setRequestProperty("User-Agent", AGENT)
        conn.setRequestProperty("Accept", accept)
        try {
            val code = conn.responseCode
            if (code == 404) throw NotFound()
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16_384)
                while (out.size() < limit) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            conn.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    class NotFound : IOException("Introuvable")
}
