package dev.netrunner.files.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** SMB2/3 access to one share of a NAS (smbj). Paths are relative to the share, with "/" separators. */
object Smb {

    data class Server(
        val id: String,
        val name: String,
        val host: String,
        val share: String,
        val user: String = "",
        val domain: String = "",
        val password: String = "",
    ) {
        val label get() = name.ifBlank { "$host/$share" }
    }

    data class Entry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long)

    /** "nas.local", "192.168.1.20:4450" → host and port. */
    fun hostPort(text: String): Pair<String, Int> {
        val t = text.trim().removePrefix("smb://").substringBefore('/')
        if (t.startsWith('[')) {
            val host = t.substringAfter('[').substringBefore(']')
            return host to (t.substringAfter("]:", "").toIntOrNull() ?: 445)
        }
        if (t.count { it == ':' } == 1) {
            t.substringAfter(':').toIntOrNull()?.let { return t.substringBefore(':') to it }
        }
        return t to 445
    }

    /** "a/b" + "c" → "a/b/c"; the share root is "". */
    fun join(dir: String, name: String): String = listOf(dir.trim('/'), name.trim('/')).filter { it.isNotEmpty() }.joinToString("/")

    fun parent(path: String): String = path.trim('/').substringBeforeLast('/', "")

    private fun win(path: String) = path.trim('/').replace('/', '\\')

    fun open(server: Server): Link = Link(server)

    class Link(server: Server) : Closeable {
        private val client = SMBClient(
            SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS).withSoTimeout(60, TimeUnit.SECONDS).build(),
        )
        private val connection: Connection
        private val session: Session
        val share: DiskShare

        init {
            val (host, port) = hostPort(server.host)
            connection = client.connect(host, port)
            try {
                val auth = if (server.user.isBlank()) AuthenticationContext.guest()
                else AuthenticationContext(server.user, server.password.toCharArray(), server.domain.ifBlank { null })
                session = connection.authenticate(auth)
                share = session.connectShare(server.share.trim('/')) as? DiskShare ?: throw IOException("« ${server.share} » n'est pas un dossier partagé")
            } catch (e: Exception) {
                runCatching { connection.close() }
                runCatching { client.close() }
                throw e
            }
        }

        /** Folders first, then files, by name; "." and ".." left out. */
        fun list(dir: String): List<Entry> = share.list(win(dir))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val isDir = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                Entry(it.fileName, join(dir, it.fileName), isDir, if (isDir) 0 else it.endOfFile, it.lastWriteTime.toEpochMillis())
            }
            .sortedWith(compareBy<Entry> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        /** Copies a remote file (or folder) into [targetDir] locally. [progress] gets bytes copied so far. */
        fun download(entry: Entry, targetDir: File, progress: (Long) -> Unit = {}): File {
            var done = 0L
            fun file(remote: String, local: File) {
                read(remote).use { input -> local.outputStream().use { out -> copy(input, out) { done += it; progress(done) } } }
            }
            fun dir(remote: String, local: File) {
                local.mkdirs()
                list(remote).forEach { if (it.isDir) dir(it.path, File(local, it.name)) else file(it.path, File(local, it.name)) }
            }
            val target = freeName(targetDir, entry.name)
            if (entry.isDir) dir(entry.path, target) else file(entry.path, target)
            return target
        }

        /** Sends a local file (or folder) into the remote folder [dir], replacing a file of the same name. */
        fun upload(local: File, dir: String, progress: (Long) -> Unit = {}) {
            var done = 0L
            fun send(f: File, remoteDir: String) {
                val path = join(remoteDir, f.name)
                if (f.isDirectory) {
                    if (!share.folderExists(win(path))) share.mkdir(win(path))
                    f.listFiles().orEmpty().forEach { send(it, path) }
                } else {
                    f.inputStream().use { input -> write(path).use { out -> copy(input, out) { done += it; progress(done) } } }
                }
            }
            send(local, dir)
        }

        fun mkdir(dir: String, name: String) = share.mkdir(win(join(dir, name)))

        fun delete(entry: Entry) {
            if (entry.isDir) share.rmdir(win(entry.path), true) else share.rm(win(entry.path))
        }

        fun rename(entry: Entry, newName: String) {
            val target = join(parent(entry.path), newName)
            val f = share.open(
                win(entry.path), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null,
            )
            f.use { it.rename(win(target)) }
        }

        fun read(path: String): InputStream {
            val f = share.openFile(win(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
            val input = f.inputStream
            return object : InputStream() {
                override fun read() = input.read()
                override fun read(b: ByteArray, off: Int, len: Int) = input.read(b, off, len)
                override fun close() { input.close(); f.close() }
            }
        }

        private fun write(path: String): OutputStream {
            val f = share.openFile(win(path), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)
            val out = f.outputStream
            return object : OutputStream() {
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun flush() = out.flush()
                override fun close() { out.close(); f.close() }
            }
        }

        override fun close() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    private fun copy(input: InputStream, out: OutputStream, step: (Int) -> Unit) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            step(n)
        }
    }

    private fun freeName(dir: File, name: String): File {
        var candidate = File(dir, name)
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var n = 2
        while (candidate.exists()) candidate = File(dir, "${name.substring(0, dot)} ($n)${name.substring(dot)}").also { n++ }
        return candidate
    }
}
