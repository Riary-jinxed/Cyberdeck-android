package dev.netrunner.decklink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppsTest {

    private val releases = """[
      {"tag_name":"askapc-v0.1.1","name":"As//ka PC v0.1.1","draft":false,"prerelease":false,"body":"Voix",
       "assets":[{"name":"aska-0.1.1.msi","browser_download_url":"https://x/aska-0.1.1.msi","size":90000000,"digest":"sha256:ab12"},
                 {"name":"aska-0.1.1.deb","browser_download_url":"https://x/aska-0.1.1.deb","size":80000000}]},
      {"tag_name":"askapc-v0.1.0","name":"As//ka PC v0.1.0","assets":[{"name":"aska-0.1.0.msi","browser_download_url":"https://x/old.msi","size":1}]},
      {"tag_name":"decklink-v0.2.0","name":"Deck Link (PC) v0.2.0","assets":[{"name":"deck-link-0.2.0.msi","browser_download_url":"https://x/dl.msi","size":5}]},
      {"tag_name":"horloge-v1.2","name":"Horloge v1.2","assets":[{"name":"horloge-1.2.apk","browser_download_url":"https://x/h.apk","size":5}]},
      {"tag_name":"carto-v0.3.0","name":"Carto v0.3.0","assets":[{"name":"carto-tool-0.3.0.msi","browser_download_url":"https://x/c.msi","size":5}]},
      {"tag_name":"beta-v9.0","name":"Beta v9.0","prerelease":true,"assets":[{"name":"beta-9.0.msi","browser_download_url":"https://x/b.msi","size":5}]},
      {"tag_name":"v3.4.0","name":"Netrunner v3.4.0","assets":[{"name":"netrunner.apk","browser_download_url":"https://x/n.apk","size":5}]}
    ]"""

    @Test
    fun listsTheLatestInstallerOfEachPcApp() {
        val apps = Apps.parse(releases, ".msi")
        // Deck Link first; phone-only apps, pre-releases and the launcher are left out.
        assertEquals(listOf("decklink", "askapc", "carto"), apps.map { it.id })
        val aska = apps[1]
        assertEquals("As//ka PC", aska.title)
        assertEquals("0.1.1", aska.version)
        assertEquals("aska-0.1.1.msi", aska.asset.name)
        assertEquals("ab12", aska.asset.sha256)
        assertEquals("Aska", aska.windowsName)
        assertEquals("aska", aska.debName)
        // An app nobody told Deck Link about follows the naming convention.
        val carto = apps[2]
        assertEquals("Carto", carto.title)
        assertEquals("Carto", carto.windowsName)
        assertEquals("carto-tool", carto.debName)
        assertNull(carto.asset.sha256)

        val deb = Apps.parse(releases, ".deb")
        assertEquals(listOf("askapc"), deb.map { it.id })
        assertNull(deb.single().asset.sha256)
    }

    @Test
    fun comparesVersions() {
        assertTrue(Apps.isNewer("0.2.0", "0.1.9"))
        assertTrue(Apps.isNewer("0.10.0", "0.9.0"))
        assertFalse(Apps.isNewer("0.1.0", "0.1.0"))
        assertFalse(Apps.isNewer("0.1.0", "0.1.1"))
        val app = Apps.parse(releases, ".msi").first { it.id == "askapc" }
        assertTrue(app.copy(installed = "0.1.0").updatable)
        assertFalse(app.copy(installed = "0.1.1").updatable)
        assertFalse(app.updatable)
    }

    @Test
    fun readsInstalledProgramsFromTheRegistry() {
        val out = """
HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Uninstall\{1B2C}
    DisplayName    REG_SZ    Deck Link
    DisplayVersion    REG_SZ    0.1.1
    EstimatedSize    REG_DWORD    0x2a3c

HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Uninstall\Empty

HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Uninstall\{9F00}
    DisplayName    REG_SZ    Aska
    DisplayVersion    REG_SZ    0.1.0
""".trimStart()
        val entries = Apps.uninstallEntries(out)
        assertEquals(2, entries.size)
        assertEquals("Deck Link", entries[0]["DisplayName"])
        assertEquals("0.1.1", entries[0]["DisplayVersion"])
        assertEquals("0.1.0", entries.first { it["DisplayName"] == "Aska" }["DisplayVersion"])
    }
}
