package dev.netrunner.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.launcher.AppEntry
import kotlinx.coroutines.delay

/** Full-screen "command line" shown for under a second before an app opens. */
@Composable
fun BreachOverlay(app: AppEntry, onDone: () -> Unit) {
    val steps = remember(app) {
        listOf(
            "> jack-in --target ${app.packageName}" to Cp.Yellow,
            "[..] résolution du nœud « ${app.label} »" to Cp.Text,
            "[..] contournement ICE" to Cp.Text,
            "[OK] ACCÈS ACCORDÉ" to Cp.Cyan,
        )
    }
    var shown by remember(app) { mutableIntStateOf(0) }
    var percent by remember(app) { mutableIntStateOf(0) }

    LaunchedEffect(app) {
        shown = 1
        delay(150)
        shown = 2
        delay(150)
        shown = 3
        while (percent < 100) {
            percent = (percent + 10).coerceAtMost(100)
            delay(25)
        }
        shown = 4
        delay(200)
        onDone()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cp.Background.copy(alpha = 0.95f))
            .scanlines()
            // Swallow touches while the animation plays.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Image(app.icon, contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        steps.take(shown).forEachIndexed { index, (text, color) ->
            Text(
                text,
                color = color,
                fontFamily = HudFont,
                fontSize = 14.sp,
                fontWeight = if (index == steps.lastIndex) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(vertical = 3.dp),
            )
            if (index == 2) {
                val filled = percent / 10
                Text(
                    "     [" + "█".repeat(filled) + "░".repeat(10 - filled) + "] $percent %",
                    color = Cp.Red,
                    fontFamily = HudFont,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                )
            }
        }
    }
}
