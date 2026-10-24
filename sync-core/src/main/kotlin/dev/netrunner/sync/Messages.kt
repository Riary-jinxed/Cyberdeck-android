package dev.netrunner.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * A change made on the PC, for the phone to apply to its own data. [json] is opaque here: the
 * launcher reads it (kit/KitRemote.kt), Deck Link writes it (Outbox.kt).
 */
data class Op(val id: String, val json: String)

/** What the phone and the PC say to each other inside a [SecureChannel]. */
sealed class Message {
    /** Phone → PC: who is speaking and which folder it syncs. */
    data class Hello(val version: Int, val device: String, val folder: String) : Message()

    /** PC → phone: the changes made on the PC since the last sync, for the phone to apply. */
    data class Ops(val ops: List<Op>) : Message()

    /** Phone → PC: the changes it applied (or dropped as older), then every file it now holds. */
    data class Manifest(val applied: List<String>, val files: List<FileEntry>) : Message()

    /** PC → phone: the files the PC is missing or holds in an older version. */
    data class Want(val paths: List<String>) : Message()

    /** Phone → PC: a file follows, in raw frames adding up to [size] bytes. */
    data class FileStart(val path: String, val size: Long, val sha256: String) : Message()

    /** Phone → PC: every wanted file was sent. */
    object Done : Message()

    /** PC → phone: the sync is over. */
    data class Ack(val stored: Int, val removed: Int, val skipped: Int) : Message()

    /** Either side: the sync stops, with a reason for the user. */
    data class Error(val reason: String) : Message()

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { o ->
            when (this) {
                is Hello -> { o.writeByte(1); o.writeInt(version); o.writeUTF(device); o.writeUTF(folder) }
                is Ops -> {
                    o.writeByte(7); o.writeInt(ops.size)
                    // writeUTF stops at 64 KB: an op's JSON goes as length + bytes.
                    ops.forEach { op -> o.writeUTF(op.id); val b = op.json.toByteArray(Charsets.UTF_8); o.writeInt(b.size); o.write(b) }
                }
                is Manifest -> {
                    o.writeByte(8); o.writeInt(applied.size); applied.forEach { o.writeUTF(it) }
                    o.writeInt(files.size)
                    files.forEach { o.writeUTF(it.path); o.writeLong(it.size); o.writeUTF(it.sha256) }
                }
                is Want -> { o.writeByte(2); o.writeInt(paths.size); paths.forEach { o.writeUTF(it) } }
                is FileStart -> { o.writeByte(3); o.writeUTF(path); o.writeLong(size); o.writeUTF(sha256) }
                Done -> o.writeByte(4)
                is Ack -> { o.writeByte(5); o.writeInt(stored); o.writeInt(removed); o.writeInt(skipped) }
                is Error -> { o.writeByte(6); o.writeUTF(reason.take(500)) }
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        const val VERSION = 2
        private const val MAX_FILES = 200_000

        fun decode(data: ByteArray): Message {
            val i = DataInputStream(ByteArrayInputStream(data))
            return when (val type = i.readByte().toInt()) {
                1 -> Hello(i.readInt(), i.readUTF(), i.readUTF())
                7 -> {
                    val n = i.readInt()
                    if (n !in 0..MAX_FILES) throw IOException("liste trop longue")
                    Ops(List(n) {
                        val id = i.readUTF()
                        val size = i.readInt()
                        if (size !in 0..SecureChannel.MAX_FRAME) throw IOException("modification trop longue")
                        Op(id, String(ByteArray(size).also { b -> i.readFully(b) }, Charsets.UTF_8))
                    })
                }
                8 -> {
                    val a = i.readInt()
                    if (a !in 0..MAX_FILES) throw IOException("liste trop longue")
                    val applied = List(a) { i.readUTF() }
                    val n = i.readInt()
                    if (n !in 0..MAX_FILES) throw IOException("liste trop longue")
                    Manifest(applied, List(n) { FileEntry(i.readUTF(), i.readLong(), i.readUTF()) })
                }
                2 -> {
                    val n = i.readInt()
                    if (n !in 0..MAX_FILES) throw IOException("liste trop longue")
                    Want(List(n) { i.readUTF() })
                }
                3 -> FileStart(i.readUTF(), i.readLong(), i.readUTF())
                4 -> Done
                5 -> Ack(i.readInt(), i.readInt(), i.readInt())
                6 -> Error(i.readUTF())
                else -> throw IOException("message inconnu ($type)")
            }
        }
    }
}

fun SecureChannel.sendMessage(m: Message) = send(m.encode())
fun SecureChannel.receiveMessage(): Message = Message.decode(receive())
