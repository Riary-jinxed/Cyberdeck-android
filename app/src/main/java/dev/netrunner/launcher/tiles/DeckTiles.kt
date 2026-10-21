package dev.netrunner.launcher.tiles

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.netrunner.launcher.MainActivity
import dev.netrunner.launcher.R
import dev.netrunner.launcher.lock.CyberLock
import dev.netrunner.launcher.system.Ghost
import dev.netrunner.launcher.system.UiSound

/** Opens a Netrunner screen from a tile, collapsing the shade (API 34 needs a PendingIntent). */
@SuppressLint("StartActivityAndCollapseDeprecated")
private fun TileService.openPanel(panel: String) {
    val intent = Intent(this, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_PANEL, panel)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 34) {
        startActivityAndCollapse(
            PendingIntent.getActivity(this, panel.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        )
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}

private fun TileService.render(on: Boolean, subtitle: String) {
    val tile = qsTile ?: return
    tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    if (Build.VERSION.SDK_INT >= 29) tile.subtitle = subtitle
    tile.updateTile()
}

/** Ghost mode on/off without leaving the shade. */
class GhostTileService : TileService() {
    override fun onStartListening() = render(Ghost.isOn(this), if (Ghost.isOn(this)) "Engagé" else "Off")

    override fun onClick() {
        UiSound.play(this, UiSound.Cue.Glitch)
        if (Ghost.isOn(this)) Ghost.disengage(this) else Ghost.engage(this, openRadios = false)
        onStartListening()
    }
}

class LockTileService : TileService() {
    override fun onStartListening() {
        val on = CyberLock.isEnabled(this) && CyberLock.missingStep(this) == null
        render(on, if (on) "Actif" else "Off")
    }

    override fun onClick() {
        val missing = CyberLock.missingStep(this)
        if (missing != null && !CyberLock.isEnabled(this)) {
            CyberLock.setEnabled(this, true)
            openPanel("settings")
            return
        }
        CyberLock.setEnabled(this, !CyberLock.isEnabled(this))
        onStartListening()
    }
}

class TerminalTileService : TileService() {
    override fun onStartListening() = render(false, "Ouvrir")
    override fun onClick() = openPanel("terminal")
}

class WifiScanTileService : TileService() {
    override fun onStartListening() = render(false, "Scanner")
    override fun onClick() = openPanel("wifi")
}

/** Android 13+: one tap in Netrunner's settings adds a tile to the shade. */
object DeckTiles {
    data class Entry(val service: Class<out TileService>, val label: String, val icon: Int)

    val all = listOf(
        Entry(GhostTileService::class.java, "Mode furtif", R.drawable.ic_tile_ghost),
        Entry(LockTileService::class.java, "Verrou cyber", R.drawable.ic_tile_lock),
        Entry(TerminalTileService::class.java, "Terminal", R.drawable.ic_tile_terminal),
        Entry(WifiScanTileService::class.java, "Scan Wi-Fi", R.drawable.ic_tile_wifi),
    )

    /** Returns false when the system has no "add tile" prompt (before Android 13). */
    fun requestAdd(context: Context, entry: Entry): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val bar = context.getSystemService(StatusBarManager::class.java) ?: return false
        bar.requestAddTileService(
            ComponentName(context, entry.service),
            entry.label,
            Icon.createWithResource(context, entry.icon),
            context.mainExecutor,
        ) { }
        return true
    }
}
