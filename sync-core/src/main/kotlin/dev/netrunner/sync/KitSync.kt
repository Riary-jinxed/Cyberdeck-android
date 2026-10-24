package dev.netrunner.sync

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/** One finished sync, as the PC saw it. */
data class SyncReport(val device: String, val folder: String, val files: Int, val stored: Int, val removed: Int, val skipped: Int, val at: Long)

/**
 * The PC side: listens on [port], checks each phone against the pairing code, and mirrors the
 * folder it sends into `<root>/<folder>`. One phone at a time; each connection is one sync.
 */
class SyncServer(
    private val root: File,
    private val code: () -> PairingCode?,
    val port: Int = DEFAULT_PORT,
    private val folders: Set<String> = setOf("kit"),
    private val onEvent: (Event) -> Unit = {},
) {
    sealed class Event {
        data class Started(val device: String, val files: Int, val wanted: Int) : Event()
        data class Progress(val done: Int, val total: Int) : Event()
        data class Finished(val report: SyncReport) : Event()
        data class Failed(val reason: String) : Event()
    }

    private var socket: ServerSocket? = null

    @Volatile
    var running = false
        private set

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        socket = s
        running = true
        Thread({
            while (running) {
                val client = try { s.accept() } catch (e: SocketException) { break }
                try { serve(client) } catch (e: Exception) { onEvent(Event.Failed(e.message ?: e.javaClass.simpleName)) } finally { runCatching { client.close() } }
            }
        }, "decklink-server").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 60_000
        val key = code()?.key ?: throw IOException("aucun code d'appairage")
        val challenge = SecureChannel.newChallenge()
        client.getOutputStream().apply { write(SecureChannel.MAGIC); write(challenge); flush() }
        val ch = SecureChannel(client.getInputStream(), client.getOutputStream(), key, challenge, isClient = false)
        val hello = ch.receiveMessage() as? Message.Hello ?: throw IOException("salutation attendue")
        if (hello.version != Message.VERSION) {
            ch.sendMessage(Message.Error("version du protocole différente : mets à jour le launcher et Deck Link"))
            throw IOException("version ${hello.version}")
        }
        if (hello.folder !in folders) {
            ch.sendMessage(Message.Error("dossier « ${hello.folder} » non pris en charge par ce PC"))
            throw IOException("dossier ${hello.folder}")
        }
        val store = MirrorStore(File(root, hello.folder))
        val plan = store.plan(hello.files)
        onEvent(Event.Started(hello.device, hello.files.size, plan.wanted.size))
        ch.sendMessage(Message.Want(plan.wanted.map { it.path }))
        val expected = plan.wanted.associateBy { it.path }
        var stored = 0
        var skipped = 0
        var done = 0
        while (true) {
            when (val m = ch.receiveMessage()) {
                is Message.FileStart -> {
                    if (m.size < 0) throw IOException("taille invalide")
                    val ok = if (expected[m.path]?.sha256 == m.sha256) store.receive(m.path, m.size, m.sha256) { ch.receive() } else drain(ch, m.size)
                    if (ok) stored++ else skipped++
                    onEvent(Event.Progress(++done, plan.wanted.size))
                }
                Message.Done -> break
                is Message.Error -> throw IOException(m.reason)
                else -> throw IOException("message inattendu")
            }
        }
        val removed = store.remove(plan.removed)
        ch.sendMessage(Message.Ack(stored, removed, skipped))
        onEvent(Event.Finished(SyncReport(hello.device, hello.folder, hello.files.size, stored, removed, skipped, System.currentTimeMillis())))
    }

    /** Reads and drops the frames of a file nobody asked for. */
    private fun drain(ch: SecureChannel, size: Long): Boolean {
        var left = size
        while (left > 0) left -= ch.receive().size.toLong().takeIf { it > 0 } ?: throw IOException("trame vide")
        return false
    }

    companion object {
        const val DEFAULT_PORT = 47321
    }
}

/** The phone side: sends a folder to a paired PC. */
object SyncClient {
    data class Result(val host: String, val files: Int, val sent: Int, val stored: Int, val removed: Int, val skipped: Int)

    /**
     * Mirrors [files] (read through [open]) to the PC at [address]. Throws [NotPairedException]
     * when the PC holds another pairing code, IOException when the PC cannot be reached.
     */
    fun sync(
        address: InetSocketAddress,
        code: PairingCode,
        device: String,
        folder: String,
        files: List<FileEntry>,
        open: (String) -> InputStream,
        connectTimeoutMs: Int = 3000,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        Socket().use { s ->
            s.connect(address, connectTimeoutMs)
            s.soTimeout = 60_000
            val head = ByteArray(SecureChannel.MAGIC.size + SecureChannel.CHALLENGE)
            DataInputStream(s.getInputStream()).readFully(head)
            if (!head.copyOfRange(0, SecureChannel.MAGIC.size).contentEquals(SecureChannel.MAGIC)) throw IOException("ce n'est pas Deck Link")
            val challenge = head.copyOfRange(SecureChannel.MAGIC.size, head.size)
            val ch = SecureChannel(s.getInputStream(), s.getOutputStream(), code.key, challenge, isClient = true)
            ch.sendMessage(Message.Hello(Message.VERSION, device, folder, files))
            // A PC holding another code cannot answer: it hangs up right after the hello.
            val reply = try { ch.receiveMessage() } catch (e: NotPairedException) { throw e } catch (e: IOException) {
                throw NotPairedException("le PC a refusé la liaison : appaire à nouveau le téléphone")
            }
            val want = when (val m = reply) {
                is Message.Want -> m.paths
                is Message.Error -> throw IOException(m.reason)
                else -> throw IOException("réponse inattendue")
            }
            val byPath = files.associateBy { it.path }
            want.forEachIndexed { index, path ->
                val entry = byPath[path] ?: throw IOException("le PC demande un fichier inconnu")
                sendFile(ch, entry, open)
                progress(index + 1, want.size)
            }
            ch.sendMessage(Message.Done)
            return when (val m = ch.receiveMessage()) {
                is Message.Ack -> Result(address.hostString, files.size, want.size, m.stored, m.removed, m.skipped)
                is Message.Error -> throw IOException(m.reason)
                else -> throw IOException("réponse inattendue")
            }
        }
    }

    /**
     * Sends exactly [FileEntry.size] bytes: a file that grew is cut, one that shrank is padded.
     * Either way its hash no longer matches and the PC keeps its copy until the next sync.
     */
    private fun sendFile(ch: SecureChannel, entry: FileEntry, open: (String) -> InputStream) {
        ch.sendMessage(Message.FileStart(entry.path, entry.size, entry.sha256))
        var left = entry.size
        runCatching {
            MirrorStore.chunks(open(entry.path)) { buffer, n ->
                if (left <= 0) return@chunks
                val take = minOf(n.toLong(), left).toInt()
                ch.send(buffer, 0, take)
                left -= take
            }
        }.onFailure { if (it is SocketException) throw it }
        val pad = ByteArray(64 * 1024)
        while (left > 0) {
            val take = minOf(pad.size.toLong(), left).toInt()
            ch.send(pad, 0, take)
            left -= take
        }
    }
}
