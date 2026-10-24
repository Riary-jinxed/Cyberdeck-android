package dev.netrunner.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    private fun tempDir(): File = Files.createTempDirectory("decklink").toFile().apply { deleteOnExit() }

    @Test
    fun pairingCodeRoundTrips() {
        repeat(50) {
            val code = PairingCode.random()
            assertEquals(code, PairingCode.parse(code.text))
            assertEquals(code, PairingCode.parse(code.text.lowercase().replace("-", " ")))
        }
        assertNull(PairingCode.parse("ABCD"))
        assertNull(PairingCode.parse("U".repeat(26)))
    }

    @Test
    fun offerRoundTrips() {
        val offer = PairingOffer(PairingCode.random(), "192.168.1.20", 47321, "PC de Roitel : bureau")
        assertEquals(offer, PairingOffer.parse(offer.encode()))
        assertNull(PairingOffer.parse("https://example.com"))
    }

    @Test
    fun safePathRefusesEscapes() {
        listOf("../x", "a/../b", "/etc/passwd", "a\\b", "C:/x", "a//b", "", "./a").forEach { assertFalse(SafePath.ok(it), it) }
        listOf("kit.json", "logbook/abc/photo 1.jpg").forEach { assertTrue(SafePath.ok(it), it) }
    }

    @Test
    fun channelRejectsWrongKeyAndReplay() {
        val key = PairingCode.random().key
        val challenge = SecureChannel.newChallenge()
        val wire = ByteArrayOutputStream()
        val phone = SecureChannel(ByteArrayInputStream(ByteArray(0)), wire, key, challenge, isClient = true)
        phone.send("un".toByteArray())
        phone.send("deux".toByteArray())
        val frames = wire.toByteArray()

        val pc = SecureChannel(ByteArrayInputStream(frames), ByteArrayOutputStream(), key, challenge, isClient = false)
        assertEquals("un", String(pc.receive()))
        assertEquals("deux", String(pc.receive()))

        val stranger = SecureChannel(ByteArrayInputStream(frames), ByteArrayOutputStream(), PairingCode.random().key, challenge, isClient = false)
        assertFailsWith<NotPairedException> { stranger.receive() }

        // Same frames on a new connection (new challenge): refused.
        val replay = SecureChannel(ByteArrayInputStream(frames), ByteArrayOutputStream(), key, SecureChannel.newChallenge(), isClient = false)
        assertFailsWith<NotPairedException> { replay.receive() }

        // Second frame first: refused.
        val firstSize = 4 + java.nio.ByteBuffer.wrap(frames, 0, 4).int
        val reordered = SecureChannel(ByteArrayInputStream(frames.copyOfRange(firstSize, frames.size)), ByteArrayOutputStream(), key, challenge, isClient = false)
        assertFailsWith<NotPairedException> { reordered.receive() }
    }

    @Test
    fun endToEndMirror() {
        val phone = tempDir()
        val pcRoot = tempDir()
        File(phone, "kit.json").writeText("""{"equipment":[]}""")
        File(phone, "logbook/i1").mkdirs()
        File(phone, "logbook/i1/a.jpg").writeBytes(ByteArray(3_000_000) { (it % 251).toByte() })
        File(phone, "kit_stock.json").writeText("""{"stock":[]}""")

        val code = PairingCode.random()
        val reports = mutableListOf<SyncReport>()
        val server = SyncServer(pcRoot, { code }, port = 0.let { java.net.ServerSocket(0).use { s -> s.localPort } }, onEvent = { e ->
            if (e is SyncServer.Event.Finished) synchronized(reports) { reports += e.report }
        })
        server.start()
        try {
            val scanner = FolderScanner()
            fun sync() = SyncClient.sync(
                InetSocketAddress("127.0.0.1", server.port), code, "Xiaomi 13 Pro", "kit", { scanner.scan(phone) },
                open = { File(phone, it).inputStream() },
            )

            val first = sync()
            assertEquals(3, first.sent)
            assertEquals(3, first.stored)
            val mirror = File(pcRoot, "kit")
            assertTrue(File(phone, "logbook/i1/a.jpg").readBytes().contentEquals(File(mirror, "logbook/i1/a.jpg").readBytes()))

            // Nothing changed: nothing sent.
            assertEquals(0, sync().sent)

            // One edit, one deletion.
            File(phone, "kit.json").writeText("""{"equipment":[{"id":"x"}]}""")
            File(phone, "kit_stock.json").delete()
            val third = sync()
            assertEquals(1, third.sent)
            assertEquals(1, third.removed)
            assertEquals("""{"equipment":[{"id":"x"}]}""", File(mirror, "kit.json").readText())
            assertFalse(File(mirror, "kit_stock.json").exists())
            val trashed = File(mirror, MirrorStore.TRASH).walkTopDown().filter { it.isFile }.toList()
            assertEquals(listOf("kit_stock.json"), trashed.map { it.name })

            // The trash and the bookkeeping are never sent back as "removed".
            assertEquals(0, sync().removed)

            // Wrong code: refused, mirror untouched.
            assertFailsWith<java.io.IOException> {
                SyncClient.sync(InetSocketAddress("127.0.0.1", server.port), PairingCode.random(), "intrus", "kit", { scanner.scan(phone) }, { File(phone, it).inputStream() })
            }
            assertEquals("""{"equipment":[{"id":"x"}]}""", File(mirror, "kit.json").readText())
            Thread.sleep(200)
            assertEquals(4, synchronized(reports) { reports.size })
        } finally {
            server.stop()
        }
    }

    @Test
    fun fileChangedWhileSendingIsSkipped() {
        val phone = tempDir()
        val pcRoot = tempDir()
        File(phone, "kit.json").writeText("version 1")
        val code = PairingCode.random()
        val server = SyncServer(pcRoot, { code }, port = java.net.ServerSocket(0).use { it.localPort })
        server.start()
        try {
            val manifest = FolderScanner().scan(phone)
            File(phone, "kit.json").writeText("version 2, plus longue")
            val r = SyncClient.sync(InetSocketAddress("127.0.0.1", server.port), code, "t", "kit", { manifest }, { File(phone, it).inputStream() })
            assertEquals(1, r.skipped)
            assertFalse(File(pcRoot, "kit/kit.json").exists())
        } finally {
            server.stop()
        }
    }

    @Test
    fun maliciousPathsNeverLand() {
        val root = tempDir()
        val store = MirrorStore(File(root, "kit"))
        val plan = store.plan(listOf(FileEntry("../evil.txt", 3, "x"), FileEntry(".decklink/x", 3, "x"), FileEntry("ok.json", 3, "x")))
        assertEquals(listOf("ok.json"), plan.wanted.map { it.path })
        val data = "abc".toByteArray()
        assertFalse(store.receive("../evil.txt", 3, Hashes.sha256(ByteArrayInputStream(data))) { data })
        assertFalse(File(root, "evil.txt").exists())
        assertNotNull(Discovery.localAddresses())
    }

    @Test
    fun pcChangesReachThePhoneBeforeTheMirror() {
        val phone = tempDir()
        val pcRoot = tempDir()
        File(phone, "kit.json").writeText("avant")
        val code = PairingCode.random()
        val pending = mutableListOf(Op("op1", """{"kind":"equipment","id":"e1","item":{"name":"Switch \u00e9tage 2"}}"""), Op("op2", "{}"))
        val outbox = object : SyncServer.Outbox {
            override fun pending(folder: String) = synchronized(pending) { pending.toList() }
            override fun applied(folder: String, ids: List<String>) { synchronized(pending) { pending.removeAll { it.id in ids } } }
        }
        val server = SyncServer(pcRoot, { code }, port = java.net.ServerSocket(0).use { it.localPort }, outbox = outbox)
        server.start()
        try {
            val received = mutableListOf<Op>()
            val r = SyncClient.sync(
                InetSocketAddress("127.0.0.1", server.port), code, "t", "kit", { FolderScanner().scan(phone) }, { File(phone, it).inputStream() },
                apply = { ops ->
                    received += ops
                    // The phone applies the first change to its file, and leaves the second for later.
                    File(phone, "kit.json").writeText("après : " + ops.first().json)
                    listOf("op1")
                },
            )
            assertEquals(listOf("op1", "op2"), received.map { it.id })
            assertEquals(1, r.applied)
            assertEquals(listOf("op2"), pending.map { it.id })
            // The mirror already holds the file as it was after the change.
            assertTrue(File(pcRoot, "kit/kit.json").readText().contains("e1"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentSignatureIsCheckedOnce() {
        val key = AgentAuth.keyBytes(AgentAuth.newKey())!!
        val auth = AgentAuth(key)
        val body = """{"hostname":"PC-COMPTA-01"}""".toByteArray()
        val now = 1_800_000_000_000L
        val sig = AgentAuth(key).sign(now, body)
        assertTrue(auth.verify(now, body, sig, now + 1000))
        assertFalse(auth.verify(now, body, sig, now + 2000), "rejeu")
        assertFalse(auth.verify(now + 1, body, sig, now + 2000), "autre heure")
        assertFalse(auth.verify(now, "{}".toByteArray(), AgentAuth(key).sign(now, body), now), "autre contenu")
        val late = AgentAuth(key).sign(now, body)
        assertFalse(AgentAuth(key).verify(now, body, late, now + 11 * 60_000L), "trop vieux")
        assertFalse(AgentAuth(AgentAuth.keyBytes(AgentAuth.newKey())!!).verify(now, body, sig, now), "autre clé")
        assertNull(AgentAuth.keyBytes("abc"))
    }
}
