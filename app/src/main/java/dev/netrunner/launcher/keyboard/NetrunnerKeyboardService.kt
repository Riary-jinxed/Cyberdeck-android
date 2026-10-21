package dev.netrunner.launcher.keyboard

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
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
                    KeyboardUi(field = field.value, actions = actions)
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        field.value = FieldInfo.from(info, capsAtCursor())
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun capsAtCursor(): Boolean {
        val info = currentInputEditorInfo ?: return false
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
    }

    private val actions = object : KeyboardActions {
        override fun type(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        override fun backspace() {
            val ic = currentInputConnection ?: return
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
    fun backspace()
    fun enter()
    fun cursor(left: Boolean)
    fun shouldCapitalize(): Boolean
    fun switchKeyboard()
    fun hide()
}

data class FieldInfo(
    val numeric: Boolean = false,
    val capsStart: Boolean = false,
    val enterLabel: String = "⏎",
) {
    companion object {
        fun from(info: EditorInfo, caps: Boolean): FieldInfo {
            val klass = info.inputType and InputType.TYPE_MASK_CLASS
            val numeric = klass == InputType.TYPE_CLASS_NUMBER || klass == InputType.TYPE_CLASS_PHONE
            val multiLine = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            val label = if (multiLine) "⏎" else when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_SEARCH -> "SCAN"
                EditorInfo.IME_ACTION_SEND -> "TX"
                EditorInfo.IME_ACTION_GO -> "GO"
                EditorInfo.IME_ACTION_NEXT -> "▶▶"
                EditorInfo.IME_ACTION_DONE -> "OK"
                else -> "⏎"
            }
            return FieldInfo(numeric = numeric, capsStart = caps, enterLabel = label)
        }
    }
}
