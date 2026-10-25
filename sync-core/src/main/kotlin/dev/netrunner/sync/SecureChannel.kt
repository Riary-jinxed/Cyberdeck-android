package dev.netrunner.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The peer does not hold the pairing code, or a frame was altered, replayed or reordered. */
class NotPairedException(message: String) : IOException(message)

/**
 * Length-prefixed frames sealed with AES-256-GCM. The PC opens each connection with a fresh
 * random challenge; every frame's associated data binds it to that challenge, to its direction
 * and to its position, so frames recorded on the network cannot be replayed or reordered.
 */
class SecureChannel(
    input: InputStream,
    output: OutputStream,
    key: ByteArray,
    private val challenge: ByteArray,
    /** The phone (client) sends direction 1, the PC (server) direction 2. */
    isClient: Boolean,
) {
    private val input = DataInputStream(input.buffered(1 shl 16))
    private val output = DataOutputStream(output.buffered(1 shl 16))
    private val key = SecretKeySpec(key, "AES")
    private val random = SecureRandom()
    private val sendDir: Byte = if (isClient) 1 else 2
    private val receiveDir: Byte = if (isClient) 2 else 1
    private var sent = 0L
    private var received = 0L

    private fun aad(dir: Byte, counter: Long): ByteArray =
        ByteBuffer.allocate(challenge.size + 9).put(challenge).put(dir).putLong(counter).array()

    fun send(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        require(length <= MAX_FRAME)
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(sendDir, sent++))
        val sealed = cipher.doFinal(data, offset, length)
        output.writeInt(NONCE + sealed.size)
        output.write(nonce)
        output.write(sealed)
        output.flush()
    }

    fun receive(): ByteArray {
        val size = try { input.readInt() } catch (e: EOFException) { throw IOException("connexion fermée", e) }
        if (size < NONCE + TAG_BITS / 8 || size > MAX_FRAME + NONCE + TAG_BITS / 8) throw NotPairedException("trame invalide")
        val frame = ByteArray(size).also { input.readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, frame, 0, NONCE))
        cipher.updateAAD(aad(receiveDir, received++))
        return try {
            cipher.doFinal(frame, NONCE, size - NONCE)
        } catch (e: AEADBadTagException) {
            throw NotPairedException("code d'appairage différent")
        }
    }

    companion object {
        const val MAX_FRAME = 1 shl 20
        private const val NONCE = 12
        private const val TAG_BITS = 128
        const val CHALLENGE = 16

        /** Plain bytes the PC sends first, before its challenge. */
        val MAGIC = "DECKLNK1".toByteArray()

        fun newChallenge(): ByteArray = ByteArray(CHALLENGE).also { SecureRandom().nextBytes(it) }
    }
}
