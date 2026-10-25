package dev.netrunner.sync

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The secret shared by the phone and the PC: 128 random bits, shown on the PC as a QR code
 * (or typed by hand, 26 letters and digits). Every frame between the two is sealed with a key
 * derived from it, so a device that never scanned the code cannot read or send anything.
 */
class PairingCode private constructor(private val raw: ByteArray) {
    /** The code as typed: 26 characters in groups of 4 or 2 ("ABCD-EFGH-…"). */
    val text: String get() = encode(raw).chunked(4).joinToString("-")

    /** AES-256 key for [SecureChannel]. */
    val key: ByteArray get() = MessageDigest.getInstance("SHA-256").digest("decklink-v1".toByteArray() + raw)

    override fun equals(other: Any?) = other is PairingCode && other.raw.contentEquals(raw)
    override fun hashCode() = raw.contentHashCode()

    companion object {
        // Crockford's base 32: no I, L, O or U, so the code reads back without ambiguity.
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val BYTES = 16
        private const val CHARS = 26

        fun random(): PairingCode = PairingCode(ByteArray(BYTES).also { SecureRandom().nextBytes(it) })

        /** Reads a typed or scanned code, dashes, spaces and case ignored; null if it is not one. */
        fun parse(text: String): PairingCode? {
            val clean = text.uppercase().filter { it.isLetterOrDigit() }
                .replace('O', '0').replace('I', '1').replace('L', '1')
            if (clean.length != CHARS || clean.any { it !in ALPHABET }) return null
            return decode(clean)?.let { PairingCode(it) }
        }

        private fun encode(bytes: ByteArray): String {
            val out = StringBuilder()
            var buffer = 0
            var bits = 0
            for (b in bytes) {
                buffer = (buffer shl 8) or (b.toInt() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                    bits -= 5
                }
            }
            if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
            return out.toString()
        }

        private fun decode(text: String): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            var buffer = 0
            var bits = 0
            for (c in text) {
                buffer = (buffer shl 5) or ALPHABET.indexOf(c)
                bits += 5
                if (bits >= 8) {
                    out.write((buffer shr (bits - 8)) and 0xFF)
                    bits -= 8
                }
            }
            // The 2 leftover bits of a 26-character code must be zero.
            if (buffer and ((1 shl bits) - 1) != 0) return null
            return out.toByteArray().takeIf { it.size == BYTES }
        }
    }
}

/**
 * What the PC's QR code holds: the pairing code and where to reach the PC.
 * Format: `DECKLINK:1:<code>:<host>:<port>:<pc name>`.
 */
data class PairingOffer(val code: PairingCode, val host: String, val port: Int, val pcName: String) {
    fun encode(): String = "DECKLINK:1:${code.text}:$host:$port:${URLEncoder.encode(pcName, "UTF-8")}"

    companion object {
        fun parse(text: String): PairingOffer? {
            val parts = text.trim().split(':', limit = 6)
            if (parts.size != 6 || parts[0] != "DECKLINK" || parts[1] != "1") return null
            val code = PairingCode.parse(parts[2]) ?: return null
            val port = parts[4].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val name = runCatching { URLDecoder.decode(parts[5], "UTF-8") }.getOrNull() ?: return null
            return PairingOffer(code, parts[3], port, name)
        }
    }
}
