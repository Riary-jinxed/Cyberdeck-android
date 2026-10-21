package dev.netrunner.launcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** Night City palette. */
object Cp {
    val Yellow = Color(0xFFFCEE0A)
    val Cyan = Color(0xFF00F0FF)
    val Red = Color(0xFFFF003C)
    val Background = Color(0xFF07070D)
    val Panel = Color(0xFF11111B)
    val Dim = Color(0xFF5C5C74)
    val Text = Color(0xFFE8E8F0)
}

val HudFont: FontFamily = FontFamily.Monospace

@Composable
fun NetrunnerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cp.Yellow,
            secondary = Cp.Cyan,
            error = Cp.Red,
            background = Cp.Background,
            surface = Cp.Panel,
            surfaceContainer = Cp.Panel,
            onSurface = Cp.Text,
            onBackground = Cp.Text,
        ),
        content = content,
    )
}
