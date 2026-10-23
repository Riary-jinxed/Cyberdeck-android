package dev.netrunner.dates.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dev.netrunner.dates.date.AskaDate
import dev.netrunner.dates.date.DateBrain
import dev.netrunner.deck.DeckTheme
import dev.netrunner.deck.TabBar
import dev.netrunner.deck.cyberGrid
import dev.netrunner.deck.rememberScreenActive

class MainActivity : ComponentActivity() {

    private lateinit var brain: DateBrain

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        brain = DateBrain(applicationContext, lifecycleScope)
        brain.refreshProfile()
        if (savedInstanceState == null) read(intent)
        setContent {
            DeckTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                // Her model only stays in memory while the app is on screen (and a minute after).
                val onScreen = rememberScreenActive()
                DisposableEffect(onScreen) {
                    if (onScreen) {
                        brain.refreshProfile()
                        brain.wake()
                    }
                    onDispose { if (onScreen) brain.sleepSoon() }
                }
                BackHandler(enabled = brain.scene != null) { walkHome() }
                Box(Modifier.fillMaxSize().cyberGrid().systemBarsPadding().imePadding()) {
                    val scene = brain.scene
                    if (scene != null) {
                        DateScreen(brain, scene, onEnd = ::walkHome)
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when (tab) {
                                    0 -> ScenesTab(brain)
                                    1 -> ModelTab(brain)
                                    else -> SettingsTab()
                                }
                            }
                            TabBar(listOf("SORTIES", "NOYAU", "CONFIG"), tab) { tab = it }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        brain.refreshProfile()
        read(intent)
    }

    /** The launcher opens the app on the scene picked in her nest. */
    private fun read(intent: Intent?) {
        val name = intent?.getStringExtra("scene") ?: return
        val scene = AskaDate.Scene.entries.firstOrNull { it.name == name } ?: return
        if (brain.scene != scene) {
            if (brain.scene != null) walkHome()
            brain.start(scene)
        }
    }

    private fun walkHome() {
        val scene = brain.scene ?: return
        val saved = brain.end()
        Toast.makeText(
            this,
            if (saved) AskaDate.goodbye(scene)
            else "Le launcher n'a pas pu garder ce rendez-vous : mets-le à jour.",
            Toast.LENGTH_LONG,
        ).show()
    }
}
