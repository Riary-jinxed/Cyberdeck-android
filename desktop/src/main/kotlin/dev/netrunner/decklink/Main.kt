package dev.netrunner.decklink

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.SystemTray

private enum class Screen(val label: String) {
    Link("LIAISON"), Inventory("INVENTAIRE"), Stock("CONSOMMABLES"), Logbook("CARNET"), Clients("CLIENTS"), Cables("CÂBLES"),
}

/** A small yellow cut-corner badge, for the window and the notification area. */
private fun badge(): BitmapPainter {
    val image = ImageBitmap(32, 32)
    Canvas(image).apply {
        drawRect(0f, 0f, 32f, 32f, Paint().apply { color = Cp.Background })
        val p = androidx.compose.ui.graphics.Path().apply { moveTo(4f, 4f); lineTo(22f, 4f); lineTo(28f, 10f); lineTo(28f, 28f); lineTo(10f, 28f); lineTo(4f, 22f); close() }
        drawPath(p, Paint().apply { color = Cp.Yellow })
        drawRect(10f, 13f, 22f, 15f, Paint().apply { color = Cp.Background })
        drawRect(10f, 18f, 18f, 20f, Paint().apply { color = Cp.Background })
    }
    return BitmapPainter(image)
}

fun main() = application {
    val state = remember { LinkState() }
    val icon = remember { badge() }
    var visible by remember { mutableStateOf(true) }
    // Closing the window keeps Deck Link listening in the notification area, so the phone can sync anytime.
    val tray = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
    fun quit() {
        state.stop()
        exitApplication()
    }
    if (tray) {
        Tray(icon = icon, tooltip = "Deck Link", onAction = { visible = true }) {
            Item("Ouvrir Deck Link", onClick = { visible = true })
            Item("Quitter", onClick = ::quit)
        }
    }
    Window(
        onCloseRequest = { if (tray && state.fatal == null) visible = false else quit() },
        visible = visible,
        title = "Deck Link",
        icon = icon,
        state = rememberWindowState(width = 1180.dp, height = 780.dp),
    ) {
        App(state)
    }
}

@Composable
private fun App(state: LinkState) {
    var screen by remember { mutableStateOf(Screen.Link) }
    Column(Modifier.fillMaxSize().background(Cp.Background).scanlines().padding(18.dp)) {
        Row(Modifier.fillMaxWidth()) {
            T("// DECK LINK", Cp.Yellow, 22.sp, TitleFont, FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            val p = state.progress
            T(
                when {
                    state.fatal != null -> "HORS LIGNE"
                    p != null -> "RÉCEPTION ${p.first}/${p.second}"
                    else -> "EN ÉCOUTE · ${state.pcName.uppercase()}"
                },
                if (state.fatal != null) Cp.Red else Cp.Cyan, 14.sp, MonoFont, FontWeight.Normal,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(190.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Screen.entries.forEach { s ->
                    val on = s == screen
                    T(
                        s.label, if (on) Cp.Background else Cp.Cyan, 14.sp, weight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                            .hudPanel(Cp.Cyan.copy(alpha = 0.7f), if (on) Cp.Cyan else Cp.Panel, 6.dp)
                            .clickable { screen = s }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                T("lecture seule : le téléphone fait foi", Cp.Dim, 11.sp, MonoFont, FontWeight.Normal)
            }
            Spacer(Modifier.width(18.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (screen) {
                    Screen.Link -> LinkScreen(state)
                    Screen.Inventory -> InventoryScreen(state.kit)
                    Screen.Stock -> StockScreen(state.kit)
                    Screen.Logbook -> LogbookScreen(state.kit)
                    Screen.Clients -> ClientsScreen(state.kit)
                    Screen.Cables -> CablesScreen(state.kit)
                }
            }
        }
    }
}

