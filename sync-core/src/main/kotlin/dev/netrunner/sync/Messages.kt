package dev.netrunner.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** What the phone and the PC say to each other inside a [SecureChannel]. */
sealed class Message {
    /** Phone → PC: who is speaking, which folder it syncs, and every file it holds. */
    data class Hello(val version: Int, val device: String, val folder: String, val files: List<FileEntry>) : Message()

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
                is Hello -> {
                    o.writeByte(1); o.writeInt(version); o.writeUTF(device); o.writeUTF(folder); o.writeInt(files.size)
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
        const val VERSION = 1
        private const val MAX_FILES = 200_000

        fun decode(data: ByteArray): Message {
            val i = DataInputStream(ByteArrayInputStream(data))
            return when (val type = i.readByte().toInt()) {
                1 -> {
                    val version = i.readInt(); val device = i.readUTF(); val folder = i.readUTF()
                    val n = i.readInt()
                    if (n !in 0..MAX_FILES) throw IOException("liste trop longue")
                    Hello(version, device, folder, List(n) { FileEntry(i.readUTF(), i.readLong(), i.readUTF()) })
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
