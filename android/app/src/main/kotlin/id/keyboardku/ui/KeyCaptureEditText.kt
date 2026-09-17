package id.keyboardku.ui

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import id.keyboardku.input.HidKeymap
import id.keyboardku.input.ImeBridge
import id.keyboardku.input.InputSink
import id.keyboardku.input.KeyboardState

/**
 * Invisible 0 dp field that hosts the system IME. Its InputConnection is an [ImeBridge] that mirrors
 * every IME operation to the host. Hardware-keyboard keys arrive through onKeyDown/Up.
 */
class KeyCaptureEditText(context: Context, private val keyboard: KeyboardState, private val sink: InputSink) : EditText(context) {
    private var bridge: ImeBridge? = null

    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        isFocusable = true
        isFocusableInTouchMode = true
        isCursorVisible = false
        setTextIsSelectable(false)
        alpha = 0f
        background = null
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType = inputType
        outAttrs.imeOptions = imeOptions
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        val b = ImeBridge(this, keyboard, sink)
        bridge = b
        return b
    }

    fun clearBuffer() = bridge?.clear()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val consumer = HidKeymap.consumerForKeyCode(keyCode)
        if (consumer != 0) { sink.consumer(consumer); return true }
        val usage = HidKeymap.usageForKeyCode(keyCode)
        if (usage != 0) {
            if (event.repeatCount == 0) keyboard.press(usage, HidKeymap.modsForMeta(event.metaState))
            return true
        }
        val uc = event.unicodeChar
        if (uc != 0 && uc and android.view.KeyCharacterMap.COMBINING_ACCENT == 0) {
            sink.text(uc.toChar().toString())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        if (HidKeymap.consumerForKeyCode(keyCode) != 0) { sink.consumer(0); return true }
        val usage = HidKeymap.usageForKeyCode(keyCode)
        if (usage != 0) { keyboard.release(usage); return true }
        return super.onKeyUp(keyCode, event)
    }
}
