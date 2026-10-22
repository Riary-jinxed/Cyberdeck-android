package dev.netrunner.notes.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.netrunner.deck.DeckTheme
import dev.netrunner.deck.TabBar
import dev.netrunner.deck.cyberGrid

class MainActivity : ComponentActivity() {

    private lateinit var state: NotesState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        state = NotesState(this)
        if (savedInstanceState == null) read(intent)
        setContent {
            DeckTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                DisposableEffect(Unit) {
                    state.start()
                    onDispose { state.stop() }
                }
                Box(Modifier.fillMaxSize().cyberGrid().systemBarsPadding()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (tab == 0) NotesTab(state) else SettingsTab()
                        }
                        TabBar(listOf("NOTES", "CONFIG"), tab) { tab = it }
                    }
                }
                state.editing?.let { EditorDialog(state, it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    /** Text shared from another app becomes a new note; a VIEW of …/notes/ID opens that note. */
    private fun read(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val id = intent.data?.lastPathSegment?.toLongOrNull() ?: return
            val note = dev.netrunner.notes.data.NoteStore.get(this, id) ?: return
            state.editing?.let { state.commit(it) }
            state.editing = note
            return
        }
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        if (text.isBlank() && subject.isBlank()) return
        state.editing?.let { state.commit(it) }
        state.new(title = subject, body = text)
    }
}
