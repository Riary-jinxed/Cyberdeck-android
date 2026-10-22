package dev.netrunner.launcher.kit

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/** Admin pocket tools: hashes, encodings, JWT, number bases, regex. All local. */
object DevTools {
    data class FileHashes(val name: String, val size: Long, val sha256: String, val sha1: String, val md5: String)

    /** Reads the file once and computes the three usual digests (firmware, ISO checks). Blocking. */
    fun hashFile(context: Context, uri: Uri): FileHashes? = runCatching {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val md5 = MessageDigest.getInstance("MD5")
        var size = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sha256.update(buffer, 0, n)
                sha1.update(buffer, 0, n)
                md5.update(buffer, 0, n)
                size += n
            }
        } ?: return null
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
        FileHashes(name, size, hex(sha256.digest()), hex(sha1.digest()), hex(md5.digest()))
    }.getOrNull()

    fun hashText(text: String, algorithm: String): String =
        hex(MessageDigest.getInstance(algorithm).digest(text.toByteArray()))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    fun base64Encode(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

    /** Accepts standard and URL-safe alphabets, with or without padding. */
    fun base64Decode(text: String): String? = runCatching {
        val clean = text.trim().replace('-', '+').replace('_', '/').filter { !it.isWhitespace() }
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        String(Base64.getDecoder().decode(padded))
    }.getOrNull()

    fun urlEncode(text: String): String = URLEncoder.encode(text, "UTF-8")

    fun urlDecode(text: String): String? = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrNull()

    data class Jwt(val header: String, val payload: String, val expiry: Long?, val issuedAt: Long?)

    /** Decodes a JWT's header and payload (no signature check: that needs the issuer's key). */
    fun jwt(token: String): Jwt? {
        val parts = token.trim().split('.')
        if (parts.size < 2) return null
        val header = base64Decode(parts[0]) ?: return null
        val payload = base64Decode(parts[1]) ?: return null
        fun pretty(json: String) = runCatching { JSONObject(json).toString(2) }.getOrDefault(json)
        val claims = runCatching { JSONObject(payload) }.getOrNull()
        return Jwt(
            pretty(header),
            pretty(payload),
            claims?.optLong("exp")?.takeIf { it > 0 },
            claims?.optLong("iat")?.takeIf { it > 0 },
        )
    }

    data class Bases(val dec: String, val hex: String, val bin: String, val oct: String)

    /** Reads "255", "0xff", "0b1111", "0o17" or a bare value in [radix]. */
    fun bases(text: String, radix: Int = 10): Bases? {
        val t = text.trim().lowercase().replace("_", "").replace(" ", "")
        if (t.isEmpty()) return null
        val (digits, r) = when {
            t.startsWith("0x") -> t.drop(2) to 16
            t.startsWith("0b") -> t.drop(2) to 2
            t.startsWith("0o") -> t.drop(2) to 8
            else -> t to radix
        }
        val negative = digits.startsWith("-")
        val n = runCatching { BigInteger(digits.removePrefix("-"), r) }.getOrNull() ?: return null
        val v = if (negative) n.negate() else n
        val bin = v.toString(2).let { b -> if (negative) b else b.padStart(((b.length + 7) / 8) * 8, '0').chunked(8).joinToString(" ") }
        return Bases(v.toString(10), "0x" + v.toString(16).uppercase(), bin, "0o" + v.toString(8))
    }

    data class RegexResult(val matches: List<String>, val groups: List<List<String>>, val error: String?)

    fun regex(pattern: String, text: String, ignoreCase: Boolean): RegexResult {
        if (pattern.isEmpty()) return RegexResult(emptyList(), emptyList(), null)
        val re = runCatching {
            if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        }.getOrElse { return RegexResult(emptyList(), emptyList(), it.message?.lineSequence()?.firstOrNull()) }
        val found = re.findAll(text).take(50).toList()
        return RegexResult(found.map { it.value }, found.map { it.groupValues.drop(1) }, null)
    }
}
