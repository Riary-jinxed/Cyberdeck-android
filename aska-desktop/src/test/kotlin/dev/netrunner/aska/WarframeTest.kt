package dev.netrunner.aska

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarframeTest {

    private val now = Instant.parse("2026-10-25T16:00:00Z")

    private val world = """{
      "cetusCycle": {"expiry": "2026-10-25T16:04:00.000Z", "isDay": true, "state": "day"},
      "vallisCycle": {"expiry": "2026-10-25T16:10:00.000Z", "isWarm": false, "state": "cold"},
      "cambionCycle": {"expiry": "2026-10-25T17:30:00.000Z", "state": "vome"},
      "voidTrader": {"active": true, "location": "Relais Larunda (Mercure)", "activation": "2026-10-24T13:00:00.000Z",
        "expiry": "2026-10-26T13:00:00.000Z", "inventory": [{"item": "Primed Flow", "ducats": 350}, {"item": "Prisma Grakata"}]},
      "sortie": {"boss": "Vor", "faction": "Grineer", "variants": [{"missionType": "Survie", "modifier": "Bouclier amélioré", "node": "Ceres"}]},
      "fissures": [
        {"node": "Hepit (Néant)", "missionType": "Capture", "tier": "Axi", "active": true, "expiry": "2026-10-25T16:30:00.000Z"},
        {"node": "Olympus (Mars)", "missionType": "Disruption", "tier": "Lith", "isHard": true, "active": true, "expiry": "2026-10-25T16:30:00.000Z"},
        {"node": "Vieux", "missionType": "Survie", "tier": "Meso", "active": true, "expiry": "2026-10-25T15:00:00.000Z"}
      ],
      "nightwave": {"activeChallenges": [{"title": "Chasseur", "desc": "Tuer 150 ennemis", "reputation": 1000}]},
      "steelPath": {"currentReward": {"name": "Umbra Forma", "cost": 150}}
    }"""

    @Test
    fun readsTheWorldState() {
        val w = Warframe.parse(world, now)
        assertEquals(listOf("Plaines d'Eidolon", "Vallée Orbis", "Désert de Cambion"), w.cycles.map { it.place })
        assertEquals("jour", w.cetus?.state)
        assertTrue(w.baroActive)
        assertTrue(w.baro!!.contains("Primed Flow"))
        // Expired fissures are left out, lowest tier first.
        assertEquals(listOf("Lith Disruption Olympus (Mars) [Steel Path]", "Axi Capture Hepit (Néant)"), w.fissures)
        val text = w.text(now)
        assertTrue(text.contains("Plaines d'Eidolon jour (encore 4 min)"), text)
        assertTrue(text.contains("Sortie : Vor, Grineer"), text)
        assertTrue(text.contains("Umbra Forma"), text)
    }

    @Test
    fun formatsTimeLeft() {
        assertEquals("12 min", Warframe.left(now.plusSeconds(12 * 60), now))
        assertEquals("1 h 05", Warframe.left(now.plusSeconds(65 * 60), now))
        assertEquals("2 j 3 h", Warframe.left(now.plusSeconds((51 * 60 + 10) * 60L), now))
    }

    @Test
    fun knowsTheGameAndWhenTheRunnerWatches() {
        assertTrue(Warframe.isRunning("Warframe.x64.exe"))
        assertFalse(Warframe.isRunning("explorer"))
        assertTrue(Foreground.watching(Foreground("Lofi girl - YouTube — Mozilla Firefox", "firefox", false)))
        assertTrue(Foreground.watching(Foreground("film.mkv", "vlc", true)))
        assertFalse(Foreground.watching(Foreground("Maximum overdrive - Google Docs", "chrome", false)))
        assertFalse(Foreground.watching(Foreground("Warframe", "Warframe.x64", true)))
    }
}
