package dev.netrunner.aska

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.application
import java.awt.SystemTray

fun main() {
    // Already on the screen: a second launch leaves her alone.
    if (!Store.lockSingleInstance()) return
    application {
        val scope = rememberCoroutineScope()
        val aska = remember { Aska(scope) }
        var shown by remember { mutableStateOf(true) }
        var settingsOpen by remember { mutableStateOf(false) }
        val settings by aska.settings.collectAsState()
        val icon = remember { BitmapPainter(sprite("chibi_happy")) }
        val tray = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }

        DisposableEffect(Unit) {
            aska.start()
            onDispose { aska.stop() }
        }

        fun quit() {
            aska.stop()
            exitApplication()
        }

        fun talk() {
            shown = true
            aska.openChat()
        }

        if (tray) {
            Tray(icon = icon, tooltip = "As//ka", onAction = ::talk) {
                Item("Lui parler (Ctrl+Alt+A)", onClick = ::talk)
                Item(if (shown) "La cacher" else "La montrer", onClick = { shown = !shown })
                CheckboxItem("Mode jeu", checked = settings.game, onCheckedChange = { on -> aska.update { it.copy(game = on) } })
                Item("Réglages…", onClick = { settingsOpen = true })
                Separator()
                Item("Quitter", onClick = ::quit)
            }
        }

        Companion(aska, shown, onSettings = { settingsOpen = true })
        if (settingsOpen) SettingsWindow(aska, onClose = { settingsOpen = false }, onQuit = ::quit)
        val pictures by aska.pictures.collectAsState()
        pictures?.takeIf { it.isNotEmpty() }?.let { StudioWindow(aska, it, onClose = aska::closePictures) }
    }
}
