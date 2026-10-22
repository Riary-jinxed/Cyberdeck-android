package dev.netrunner.launcher.keyboard

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.netrunner.launcher.ui.NetrunnerTheme

/** The cyberdeck keyboard: AZERTY in neon, with symbols, accents and a key-repeat backspace. */
class NetrunnerKeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    /** What the focused field expects: decides the starting layer and the enter key label. */
    private val field = mutableStateOf(FieldInfo())

    /** Spelling suggestions for the word being typed, from Android's spell checker (Gboard's, Samsung's…). */
    private val suggestions = mutableStateOf(Suggestions())
    private var spellSession: SpellCheckerSession? = null

    private val spellListener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            val info = results?.firstOrNull()?.takeIf { it.suggestionsCount > 0 }?.getSuggestionsInfoAt(0) ?: return
            val word = currentWord()
            if (word != pendingWord) return
            val attrs = info.suggestionsAttributes
            val typo = attrs and (SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR) != 0
            val inDictionary = attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0
            val list = (0 until info.suggestionsCount).map { info.getSuggestionAt(it) }
                .filter { it.isNotBlank() && it != word }
                .distinct()
                .take(3)
                .map { matchCase(word, it) }
            suggestions.value = Suggestions(
                word = word,
                options = list,
                typo = typo && !inDictionary,
                autoFix = if (typo && !inDictionary && attrs and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0) list.firstOrNull() else null,
            )
        }
    }
    private var pendingWord = ""

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateInputView(): View {
        // Compose looks up its lifecycle on the window's root view.
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@NetrunnerKeyboardService)
            setViewTreeSavedStateRegistryOwner(this@NetrunnerKeyboardService)
            setContent {
                NetrunnerTheme {
                    KeyboardUi(field = field.value, suggestions = suggestions.value, actions = actions)
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        field.value = FieldInfo.from(info, capsAtCursor(), KeyboardPrefs.autocorrect(this))
        if (field.value.spellcheck && spellSession == null) {
            spellSession = runCatching {
                getSystemService(TextServicesManager::class.java)
                    ?.newSpellCheckerSession(null, Locale.getDefault(), spellListener, true)
            }.getOrNull()
        }
        refreshSuggestions()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshSuggestions()
    }

    /** The letters right before the cursor, back to the previous space or punctuation. */
    private fun currentWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString() ?: return ""
        return before.takeLastWhile { it.isLetter() || it == '\'' || it == '’' || it == '-' }.trimStart('\'', '’', '-')
    }

    private fun refreshSuggestions() {
        val session = spellSession
        val word = currentWord()
        if (!field.value.spellcheck || session == null || word.length < 2) {
            pendingWord = ""
            if (suggestions.value != Suggestions()) suggestions.value = Suggestions()
            return
        }
        if (word == suggestions.value.word || word == pendingWord) return
        pendingWord = word
        suggestions.value = Suggestions(word = word)
        runCatching {
            session.cancel()
            session.getSentenceSuggestions(arrayOf(TextInfo(word)), 3)
        }
    }

    /** Keeps the user's capitalisation: "Bonjour" → "Bonsoir", "NUIT" → "NUITS". */
    private fun matchCase(typed: String, suggestion: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> suggestion.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> suggestion.replaceFirstChar { it.titlecase() }
        else -> suggestion
    }

    /** Swaps the word before the cursor for [replacement]. */
    private fun replaceWord(word: String, replacement: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(replacement, 1)
        ic.endBatchEdit()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        spellSession?.close()
        spellSession = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun capsAtCursor(): Boolean {
        val info = currentInputEditorInfo ?: return false
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
    }

    private var lastCorrection: Pair<String, String>? = null

    /** When the last space after a word was typed, for the double-space full stop. */
    private var spaceAfterWordAt = 0L

    private val actions = object : KeyboardActions {
        override fun type(text: String) {
            // Auto-correction: on a space or punctuation, a word the checker flags as a typo is
            // replaced by its recommended suggestion. Backspace right after restores the original.
            // Double space after a word gives ". ", like other keyboards (text fields only).
            val now = android.os.SystemClock.uptimeMillis()
            if (text == " " && field.value.kind == FieldKind.Text && now - spaceAfterWordAt < 1200) {
                spaceAfterWordAt = 0L
                val ic = currentInputConnection
                val before = ic?.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                if (ic != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                    lastCorrection = null
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                    ic.endBatchEdit()
                    return
                }
            }
            val s = suggestions.value
            val fix = s.autoFix
            if (fix != null && field.value.autocorrect && text.length == 1 && text[0] in " .,;:!?\n" && currentWord() == s.word) {
                replaceWord(s.word, fix)
                lastCorrection = s.word to fix
            } else {
                lastCorrection = null
            }
            val ic = currentInputConnection
            val wordBefore = text == " " && ic?.getTextBeforeCursor(1, 0)?.firstOrNull()?.isLetterOrDigit() == true
            ic?.commitText(text, 1)
            spaceAfterWordAt = if (wordBefore) now else 0L
        }

        override fun contextAction(id: Int) {
            currentInputConnection?.performContextMenuAction(id)
        }

        override fun pickSuggestion(text: String) {
            val word = suggestions.value.word
            if (word.isEmpty() || currentWord() != word) return
            replaceWord(word, "$text ")
            lastCorrection = null
            spaceAfterWordAt = android.os.SystemClock.uptimeMillis()
        }

        override fun backspace() {
            val ic = currentInputConnection ?: return
            // Undo an auto-correction: "fix·" + ⌫ gives back the word as it was typed.
            lastCorrection?.let { (original, fixed) ->
                lastCorrection = null
                val before = ic.getTextBeforeCursor(fixed.length + 1, 0)?.toString()
                if (before != null && before.startsWith(fixed) && before.length == fixed.length + 1) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(fixed.length + 1, 0)
                    ic.commitText(original, 1)
                    ic.endBatchEdit()
                    return
                }
            }
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }

        override fun enter() {
            val info = currentInputEditorInfo
            val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
            val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
            val multiLine = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            if (!noAction && !multiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                currentInputConnection?.performEditorAction(action)
            } else {
                sendKeyChar('\n')
            }
        }

        override fun cursor(left: Boolean) {
            sendDownUpKeyEvents(if (left) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        }

        override fun shouldCapitalize(): Boolean = capsAtCursor()

        override fun switchKeyboard() {
            if (!switchToNextInputMethod(false)) requestHideSelf(0)
        }

        override fun hide() = requestHideSelf(0)
    }
}

interface KeyboardActions {
    fun type(text: String)
    fun pickSuggestion(text: String)
    /** Paste, select all, copy: android.R.id values for the editor's context menu. */
    fun contextAction(id: Int)
    fun backspace()
    fun enter()
    fun cursor(left: Boolean)
    fun shouldCapitalize(): Boolean
    fun switchKeyboard()
    fun hide()
}

/** Suggestion strip state: the word being typed and what the spell checker proposes. */
data class Suggestions(
    val word: String = "",
    val options: List<String> = emptyList(),
    val typo: Boolean = false,
    /** Replacement applied on the next space when auto-correction is on. */
    val autoFix: String? = null,
)

/** Which key layout a field gets. */
enum class FieldKind { Text, Number, Phone, Email, Url, Password }

data class FieldInfo(
    val kind: FieldKind = FieldKind.Text,
    val capsStart: Boolean = false,
    val enterLabel: String = "⏎",
    /** Show spelling suggestions (normal text fields only). */
    val spellcheck: Boolean = false,
    val autocorrect: Boolean = false,
    /** Number fields that accept a sign or decimals. */
    val signed: Boolean = false,
    val decimal: Boolean = false,
) {
    val numeric: Boolean get() = kind == FieldKind.Number || kind == FieldKind.Phone

    companion object {
        fun from(info: EditorInfo, caps: Boolean, autocorrectPref: Boolean = true): FieldInfo {
            val type = info.inputType
            val klass = type and InputType.TYPE_MASK_CLASS
            val variation = type and InputType.TYPE_MASK_VARIATION
            val kind = when {
                klass == InputType.TYPE_CLASS_PHONE -> FieldKind.Phone
                klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> FieldKind.Number
                klass == InputType.TYPE_CLASS_NUMBER || klass == InputType.TYPE_CLASS_DATETIME -> FieldKind.Number
                klass == InputType.TYPE_CLASS_TEXT && variation in setOf(
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                ) -> FieldKind.Password
                klass == InputType.TYPE_CLASS_TEXT && variation in setOf(
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                ) -> FieldKind.Email
                klass == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.Url
                else -> FieldKind.Text
            }
            val multiLine = type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            val label = if (multiLine) "⏎" else when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_SEARCH -> "SCAN"
                EditorInfo.IME_ACTION_SEND -> "TX"
                EditorInfo.IME_ACTION_GO -> "GO"
                EditorInfo.IME_ACTION_NEXT -> "▶▶"
                EditorInfo.IME_ACTION_DONE -> "OK"
                else -> "⏎"
            }
            // Text fields only, and not when the app asks for no suggestions (codes, usernames…).
            val noSuggestions = type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0 ||
                variation == InputType.TYPE_TEXT_VARIATION_FILTER ||
                variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            val spell = kind == FieldKind.Text && !noSuggestions
            return FieldInfo(
                kind = kind,
                capsStart = caps,
                enterLabel = label,
                spellcheck = spell,
                autocorrect = spell && autocorrectPref && (type and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0 || variation == InputType.TYPE_TEXT_VARIATION_NORMAL || variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE || variation == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT),
                signed = type and InputType.TYPE_NUMBER_FLAG_SIGNED != 0,
                decimal = type and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0,
            )
        }
    }
}

/** Keyboard options set from Netrunner's Config screen. */
object KeyboardPrefs {
    fun autocorrect(context: android.content.Context) =
        context.getSharedPreferences("netrunner", android.content.Context.MODE_PRIVATE).getBoolean("kb_autocorrect", true)

    fun setAutocorrect(context: android.content.Context, on: Boolean) =
        context.getSharedPreferences("netrunner", android.content.Context.MODE_PRIVATE).edit().putBoolean("kb_autocorrect", on).apply()
}
