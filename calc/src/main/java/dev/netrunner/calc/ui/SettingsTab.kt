package dev.netrunner.calc.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.netrunner.deck.Cp
import dev.netrunner.deck.FactionSection
import dev.netrunner.deck.HudFont
import dev.netrunner.deck.SectionTitle
import dev.netrunner.deck.UpdateSection
import dev.netrunner.deck.Updater

/** Releases tagged "calc-v…", apart from the launcher's and the clock's. */
val CalcUpdater = Updater("calc-v")

@Composable
fun SettingsTab() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("ASTUCES", Cp.Yellow)
        Text(
            "• Appui long sur un résultat ou une base pour le copier.\n" +
                "• Appui long sur ⌫ pour tout effacer.\n" +
                "• 2ND donne les fonctions inverses, eˣ, 10ˣ, x³, ∛, EE, |x| et mod.\n" +
                "• En PROG, touche un bit pour l'inverser, et une base pour y saisir.",
            color = Cp.Text, fontFamily = HudFont, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(18.dp))
        FactionSection()
        Spacer(Modifier.height(18.dp))
        UpdateSection(CalcUpdater)
        Spacer(Modifier.height(24.dp))
    }
}
