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
data class SyncReport(val device: String, val folder: String, val files: Int, val stored: Int, val removed: Int, val skipped: Int, val applied: Int, val at: Long)

/**
 * The PC side: listens on [port], checks each phone against the pairing code, and mirrors the
 * folder it sends into `<root>/<folder>`. One phone at a time; each connection is one sync.
 */
class SyncServer(
    private val root: File,
    private val code: () -> PairingCode?,
    val port: Int = DEFAULT_PORT,
    private val folders: Set<String> = setOf("kit"),
    private val outbox: Outbox = Outbox.None,
    private val onEvent: (Event) -> Unit = {},
    private val backups: Backups? = null,
) {
    /** The dated kit backups the PC keeps, which the phone can list and download to restore one. */
    interface Backups {
        fun list(): List<FileEntry>
        fun open(path: String): InputStream
    }

    /** The PC's changes waiting for the phone, per synced folder. */
    interface Outbox {
        fun pending(folder: String): List<Op>
        /** The phone applied (or dropped as older) these: they leave the outbox. */
        fun applied(folder: String, ids: List<String>)

        object None : Outbox {
            override fun pending(folder: String) = emptyList<Op>()
            override fun applied(folder: String, ids: List<String>) {}
        }
    }

    sealed class Event {
        data class Started(val device: String, val files: Int, val wanted: Int) : Event()
        data class Progress(val done: Int, val total: Int) : Event()
        data class Finished(val report: SyncReport) : Event()
        data class Failed(val reason: String) : Event()
        data class BackupSent(val device: String, val path: String) : Event()
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
        // One phone at a time: a silent connection from anyone on the network must not hold the
        // server, so the hello has to come quickly; a paired phone then gets the usual minute.
        client.soTimeout = HELLO_TIMEOUT_MS
        val key = code()?.key ?: throw IOException("aucun code d'appairage")
        val challenge = SecureChannel.newChallenge()
        client.getOutputStream().apply { write(SecureChannel.MAGIC); write(challenge); flush() }
        val ch = SecureChannel(client.getInputStream(), client.getOutputStream(), key, challenge, isClient = false)
        val hello = ch.receiveMessage() as? Message.Hello ?: throw IOException("salutation attendue")
        if (hello.version != Message.VERSION) {
            ch.sendMessage(Message.Error("version du protocole différente : mets à jour le launcher et Deck Link"))
            throw IOException("version ${hello.version}")
        }
        client.soTimeout = 60_000
        if (hello.folder == BACKUPS && backups != null) return serveBackups(ch, hello.device, backups)
        if (hello.folder !in folders) {
            ch.sendMessage(Message.Error("dossier « ${hello.folder} » non pris en charge par ce PC"))
            throw IOException("dossier ${hello.folder}")
        }
        // One frame holds the whole list: what does not fit waits for the next sync.
        var room = SecureChannel.MAX_FRAME - 64 * 1024
        val ops = outbox.pending(hello.folder).takeWhile { op -> room -= op.json.length * 3 + op.id.length + 16; room > 0 }
        ch.sendMessage(Message.Ops(ops))
        val manifest = ch.receiveMessage() as? Message.Manifest ?: throw IOException("liste des fichiers attendue")
        outbox.applied(hello.folder, manifest.applied)
        val store = MirrorStore(File(root, hello.folder))
        val plan = store.plan(manifest.files)
        onEvent(Event.Started(hello.device, manifest.files.size, plan.wanted.size))
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
        onEvent(Event.Finished(SyncReport(hello.device, hello.folder, manifest.files.size, stored, removed, skipped, manifest.applied.size, System.currentTimeMillis())))
    }

    /** The list of backups, then the one the phone picked (if any). */
    private fun serveBackups(ch: SecureChannel, device: String, backups: Backups) {
        val list = backups.list()
        ch.sendMessage(Message.Listing(list))
        val want = ch.receiveMessage() as? Message.Want ?: throw IOException("choix attendu")
        want.paths.take(1).forEach { path ->
            val entry = list.firstOrNull { it.path == path } ?: throw IOException("sauvegarde inconnue")
            sendFile(ch, entry) { backups.open(it) }
            onEvent(Event.BackupSent(device, path))
        }
        ch.sendMessage(Message.Done)
    }

    /** Reads and drops the frames of a file nobody asked for. */
    private fun drain(ch: SecureChannel, size: Long): Boolean {
        var left = size
        while (left > 0) left -= ch.receive().size.toLong().takeIf { it > 0 } ?: throw IOException("trame vide")
        return false
    }

    companion object {
        const val DEFAULT_PORT = 47321
        private const val HELLO_TIMEOUT_MS = 5_000
        /** The hello folder that asks for the backups instead of a sync. */
        const val BACKUPS = "sauvegardes"
    }
}

/** The phone side: sends a folder to a paired PC. */
object SyncClient {
    data class Result(val host: String, val files: Int, val sent: Int, val stored: Int, val removed: Int, val skipped: Int, val applied: Int)

    /**
     * Applies the PC's changes through [apply] (which returns the ids it handled), then mirrors
     * the files [scan] lists (read through [open]) to the PC at [address]. Throws
     * [NotPairedException] when the PC holds another pairing code, IOException when the PC
     * cannot be reached.
     */
    fun sync(
        address: InetSocketAddress,
        code: PairingCode,
        device: String,
        folder: String,
        scan: () -> List<FileEntry>,
        open: (String) -> InputStream,
        apply: (List<Op>) -> List<String> = { emptyList() },
        connectTimeoutMs: Int = 3000,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result = session(address, code, device, folder, connectTimeoutMs) { ch, reply ->
        val ops = when (val m = reply) {
            is Message.Ops -> m.ops
            is Message.Error -> throw IOException(m.reason)
            else -> throw IOException("réponse inattendue")
        }
        val applied = if (ops.isEmpty()) emptyList() else apply(ops)
        val files = scan()
        ch.sendMessage(Message.Manifest(applied, files))
        val want = when (val m = ch.receiveMessage()) {
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
        when (val m = ch.receiveMessage()) {
            is Message.Ack -> Result(address.hostString, files.size, want.size, m.stored, m.removed, m.skipped, applied.size)
            is Message.Error -> throw IOException(m.reason)
            else -> throw IOException("réponse inattendue")
        }
    }

    /** The kit backups the PC keeps, newest first. */
    fun backups(address: InetSocketAddress, code: PairingCode, device: String, connectTimeoutMs: Int = 3000): List<FileEntry> =
        session(address, code, device, SyncServer.BACKUPS, connectTimeoutMs) { ch, reply ->
            val list = listing(reply)
            ch.sendMessage(Message.Want(emptyList()))
            ch.receiveMessage()
            list
        }

    /** Downloads the backup [path] into [target], checked against its hash. */
    fun fetchBackup(
        address: InetSocketAddress,
        code: PairingCode,
        device: String,
        path: String,
        target: File,
        connectTimeoutMs: Int = 3000,
        progress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Unit = session(address, code, device, SyncServer.BACKUPS, connectTimeoutMs) { ch, reply ->
        val entry = listing(reply).firstOrNull { it.path == path } ?: throw IOException("cette sauvegarde n'est plus sur le PC")
        ch.sendMessage(Message.Want(listOf(path)))
        val start = ch.receiveMessage() as? Message.FileStart ?: throw IOException("réponse inattendue")
        if (start.path != path || start.size != entry.size) throw IOException("réponse inattendue")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        target.outputStream().use { out ->
            var left = start.size
            while (left > 0) {
                val chunk = ch.receive()
                if (chunk.isEmpty() || chunk.size > left) throw IOException("trame invalide")
                out.write(chunk)
                digest.update(chunk)
                left -= chunk.size
                progress(start.size - left, start.size)
            }
        }
        if (Hashes.hex(digest.digest()) != start.sha256) {
            target.delete()
            throw IOException("sauvegarde abîmée pendant le transfert : réessaie")
        }
        ch.receiveMessage()
    }

    private fun listing(reply: Message): List<FileEntry> = when (reply) {
        is Message.Listing -> reply.files
        is Message.Error -> throw IOException(reply.reason)
        else -> throw IOException("réponse inattendue")
    }

    /** Opens the sealed channel, says hello for [folder] and hands the PC's first answer to [block]. */
    private fun <T> session(
        address: InetSocketAddress,
        code: PairingCode,
        device: String,
        folder: String,
        connectTimeoutMs: Int,
        block: (SecureChannel, Message) -> T,
    ): T = Socket().use { s ->
        s.connect(address, connectTimeoutMs)
        s.soTimeout = 60_000
        val head = ByteArray(SecureChannel.MAGIC.size + SecureChannel.CHALLENGE)
        DataInputStream(s.getInputStream()).readFully(head)
        if (!head.copyOfRange(0, SecureChannel.MAGIC.size).contentEquals(SecureChannel.MAGIC)) throw IOException("ce n'est pas Deck Link")
        val challenge = head.copyOfRange(SecureChannel.MAGIC.size, head.size)
        val ch = SecureChannel(s.getInputStream(), s.getOutputStream(), code.key, challenge, isClient = true)
        ch.sendMessage(Message.Hello(Message.VERSION, device, folder))
        // A PC holding another code cannot answer: it hangs up right after the hello.
        val reply = try { ch.receiveMessage() } catch (e: NotPairedException) { throw e } catch (e: IOException) {
            throw NotPairedException("le PC a refusé la liaison : appaire à nouveau le téléphone")
        }
        block(ch, reply)
    }
}

/**
 * Sends exactly [FileEntry.size] bytes: a file that grew is cut, one that shrank is padded.
 * Either way its hash no longer matches and the PC keeps its copy until the next sync.
 */
internal fun sendFile(ch: SecureChannel, entry: FileEntry, open: (String) -> InputStream) {
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
