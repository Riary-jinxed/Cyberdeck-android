package dev.netrunner.aska

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetTest {

    @Test
    fun readsFrenchAndEnglishPings() {
        val fr = """
            Envoi d'une requête 'Ping'  192.168.1.1 avec 32 octets de données :
            Réponse de 192.168.1.1 : octets=32 temps=2 ms TTL=64
            Réponse de 192.168.1.1 : octets=32 temps<1ms TTL=64
            Délai d'attente de la demande dépassé.
            Réponse de 192.168.1.1 : octets=32 temps=3 ms TTL=64
        """.trimIndent()
        val p = Net.parsePing("192.168.1.1", 4, fr)
        assertEquals(3, p.received)
        assertEquals(2.0, p.avgMs)
        val en = "64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=12.4 ms\n64 bytes from 1.1.1.1: icmp_seq=2 ttl=57 time=13.6 ms"
        assertEquals(13.0, Net.parsePing("1.1.1.1", 2, en).avgMs)
        assertEquals(0, Net.parsePing("10.0.0.9", 4, "Hôte de destination inaccessible.").received)
    }

    @Test
    fun computesSubnets() {
        val s = Net.Subnet.parse("10.0.12.37/22")!!
        assertEquals("10.0.12.0", Net.ip(s.network))
        assertEquals("255.255.252.0", Net.ip(s.mask))
        assertEquals("10.0.15.255", Net.ip(s.broadcast))
        assertEquals(1022L, s.hosts)
        assertEquals(26, Net.Subnet.parse("192.168.1.70 255.255.255.192")!!.prefix)
        assertEquals("192.168.1.64", Net.ip(Net.Subnet.parse("192.168.1.70 255.255.255.192")!!.network))
        assertEquals(2L, Net.Subnet.parse("10.0.0.0/31")!!.hosts)
        assertNull(Net.Subnet.parse("300.1.1.1/24"))
        assertNull(Net.Subnet.parse("10.0.0.0/33"))
    }

    @Test
    fun readsArpTables() {
        val windows = """
            Interface : 192.168.1.20 --- 0x7
              Adresse Internet      Adresse physique      Type
              192.168.1.1           a4-2b-b0-11-22-33     dynamique
              192.168.1.255         ff-ff-ff-ff-ff-ff     statique
              224.0.0.22            01-00-5e-00-00-16     statique
        """.trimIndent()
        assertEquals(mapOf("192.168.1.1" to "A4:2B:B0:11:22:33"), Net.parseArp(windows))
        val linux = "192.168.1.1 dev eth0 lladdr a4:2b:b0:11:22:33 REACHABLE\n192.168.1.9 dev eth0 FAILED"
        assertEquals(mapOf("192.168.1.1" to "A4:2B:B0:11:22:33"), Net.parseArp(linux))
    }

    @Test
    fun namesMakers() {
        val table = Oui.parse(listOf("A4-2B-B0   (hex)\t\tTP-LINK TECHNOLOGIES CO.,LTD.", "A42BB0     (base 16)\t\tTP-LINK"))
        assertEquals("TP-LINK TECHNOLOGIES CO.,LTD.", Oui.vendor(table, "a4:2b:b0:11:22:33"))
        assertEquals("adresse aléatoire (téléphone ?)", Oui.vendor(table, "DA:11:22:33:44:55"))
        assertNull(Oui.vendor(table, "00:11:22:33:44:55"))
    }

    @Test
    fun readsCommands() {
        val (ping, args) = Tools.parseCommand("/ping 192.168.1.1")!!
        assertEquals("ping", ping.name)
        assertEquals(mapOf("host" to "192.168.1.1"), args)
        assertEquals(mapOf("host" to "srv", "port" to "443"), Tools.parseCommand("/port srv 443")!!.second)
        assertEquals(mapOf("query" to "Dupont accès"), Tools.parseCommand("/kit Dupont accès")!!.second)
        assertEquals(emptyMap<String, String>(), Tools.parseCommand("/scan")!!.second)
        assertNull(Tools.parseCommand("/inconnu"))
        assertNull(Tools.parseCommand("salut"))
        assertEquals(Tools.all.size, Tools.definitions().size)
    }

    @Test
    fun readsToolCalls() {
        val chunk = Ollama.parseChunk("""{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"ping","arguments":{"host":"1.1.1.1"}}}]},"done":false}""")!!
        assertEquals("ping", chunk.calls.single().name)
        assertEquals(mapOf("host" to "1.1.1.1"), Tools.args(chunk.calls.single().arguments))
        val asText = Ollama.parseChunk("""{"message":{"content":"","tool_calls":[{"function":{"name":"port_check","arguments":"{\"host\":\"srv\",\"port\":22}"}}]},"done":false}""")!!
        assertEquals(mapOf("host" to "srv", "port" to "22"), Tools.args(asText.calls.single().arguments))
    }

    @Test
    fun searchesTheKitAndFleet() {
        val home = Files.createTempDirectory("decklink").toFile()
        val kit = File(home, "sync/kit").apply { mkdirs() }
        File(kit, "kit.json").writeText("""{"equipment":[{"id":"1","name":"Switch SW-2","location":"Baie A"}],"cables":[{"id":"c1","outlet":"B12","switch":"SW-2","port":"14","vlan":"20","poe":"oui"}]}""")
        File(kit, "kit_clients.json").writeText("""{"clients":[{"id":"k","name":"Dupont SA","access":"badge à l'accueil","contracts":[{"ref":"C-1","kind":"maintenance"}]}]}""")
        File(home, "sync/parc").mkdirs()
        File(home, "sync/parc/PC-01.json").writeText("""{"hostname":"PC-01","ramGB":8,"network":{"name":"Ethernet","mac":"A4-2B-B0-11-22-33","ipv4":"192.168.1.20"}}""")
        val dir = home.path
        val b12 = DeckLink.search(dir, "prise b12")
        assertTrue(b12.contains("port: 14") && b12.contains("vlan: 20"), b12)
        assertTrue(DeckLink.search(dir, "dupont acces").contains("badge"))
        assertTrue(DeckLink.fleet(dir).contains("PC-01"))
        assertEquals("PC-01", DeckLink.fleetAddresses(dir)["A4:2B:B0:11:22:33"])
        assertEquals("PC-01", DeckLink.fleetAddresses(dir)["192.168.1.20"])
        assertNotNull(DeckLink.home(dir))
        home.deleteRecursively()
    }

    @Test
    fun preparesKitEditsWithoutSavingThem() {
        val home = Files.createTempDirectory("decklink").toFile()
        File(home, "sync/kit").mkdirs()
        File(home, "config").mkdirs()
        File(home, "sync/kit/kit_stock.json").writeText("""{"stock":[{"id":"s1","name":"Connecteurs RJ45","unit":"pcs","qty":10},{"id":"s2","name":"Colliers","qty":50}]}""")
        // A PC edit not yet sent to the phone wins over the phone's copy.
        File(home, "config/outbox.json").writeText("""[{"op":"x","kind":"stock","id":"s1","at":1,"deleted":false,"item":{"qty":12}}]""")
        assertEquals("12", DeckLink.items(home, "stock").first { it.id == "s1" }.s("qty"))
        var proposed: Tools.Edit? = null
        val text = kotlinx.coroutines.runBlocking {
            Tools.run(Tools.byName("deck_use_stock")!!, mapOf("quantity" to "3", "item" to "rj45"), home.path) { proposed = it }
        }
        assertTrue(text.contains("PAS encore enregistré"), text)
        assertEquals(Tools.Edit("stock", "s1", mapOf("qty" to "9"), "Connecteurs RJ45 : 12 pcs → 9 pcs (−3)"), proposed)
        val (conso, args) = Tools.parseCommand("/conso 3 câble RJ45")!!
        assertEquals("deck_use_stock", conso.name)
        assertEquals(mapOf("quantity" to "3", "item" to "câble RJ45"), args)
        // Unknown field: nothing prepared.
        proposed = null
        kotlinx.coroutines.runBlocking {
            Tools.run(Tools.byName("deck_update")!!, mapOf("kind" to "stock", "item" to "colliers", "field" to "couleur", "value" to "rouge"), home.path) { proposed = it }
        }
        assertNull(proposed)
        home.deleteRecursively()
    }
}
