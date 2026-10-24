package dev.netrunner.sync

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signature of the reports sent by Deck Probe, the inventory agent installed on the fleet's PCs:
 * HMAC-SHA256 of "<unix time ms>\n<body>" with the fleet key Deck Link generates. Reports are
 * not secret (name, model, serial number, addresses), but only a PC holding the key can write
 * into the inventory, and a recorded report cannot be replayed.
 */
class AgentAuth(private val key: ByteArray, private val maxSkewMs: Long = 10 * 60_000L) {
    private val seen = LinkedHashMap<String, Long>()

    fun sign(time: Long, body: ByteArray): String = Hashes.hex(mac(time, body))

    /** True once per valid signature, within [maxSkewMs] of [now]. */
    @Synchronized
    fun verify(time: Long, body: ByteArray, signature: String, now: Long = System.currentTimeMillis()): Boolean {
        if (kotlin.math.abs(now - time) > maxSkewMs) return false
        val expected = sign(time, body)
        if (!MessageDigest.isEqual(expected.toByteArray(), signature.lowercase().toByteArray())) return false
        seen.entries.removeIf { now - it.value > 2 * maxSkewMs }
        if (seen.containsKey(expected)) return false
        seen[expected] = now
        return true
    }

    private fun mac(time: Long, body: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.run {
            update("$time\n".toByteArray(Charsets.UTF_8))
            doFinal(body)
        }

    companion object {
        /** A new fleet key, as 64 hex characters (what the deployment pack carries). */
        fun newKey(): String = Hashes.hex(ByteArray(32).also { SecureRandom().nextBytes(it) })

        fun keyBytes(hex: String): ByteArray? {
            val h = hex.trim().lowercase()
            if (h.length != 64 || h.any { it !in "0123456789abcdef" }) return null
            return ByteArray(32) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}
