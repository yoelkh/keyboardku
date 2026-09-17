package id.keyboardku.input

import android.text.Editable
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection

/**
 * Turns whatever the system IME does to a shadow buffer into key presses and text for the host.
 *
 * Every IME operation (commit, compose, delete, selection) is applied to a real [Editable] so the
 * IME's own state stays coherent (Gboard swipe/autocorrect, Samsung deferred commits, MIUI Enter
 * quirks). After each operation the buffer is diffed against what has already been sent: common
 * prefix is kept, the rest becomes N Backspaces + the new suffix. Enter/Tab/Backspace arrive as keys.
 *
 * The diff logic lives in [TextDiff] so it can be unit-tested without Android.
 */
class ImeBridge(view: View, private val keyboard: KeyboardState, private val sink: InputSink) :
    BaseInputConnection(view, true) {

    private val buffer: Editable = SpannableStringBuilder()
    private val diff = TextDiff()
    private var suppress = 0

    override fun getEditable(): Editable = buffer

    private fun sync() {
        if (suppress > 0) return
        val cursor = android.text.Selection.getSelectionEnd(buffer).let { if (it < 0) buffer.length else it }
        diff.update(buffer, cursor) { backspaces, text ->
            repeat(backspaces) { keyboard.tap(Keys.BACKSPACE) }
            if (text.isNotEmpty()) sendText(text)
        }
        // keep the buffer bounded; a fresh buffer is fine once nothing is composing
        if (buffer.length > 256 && getComposingSpanStart(buffer) < 0) {
            suppress++
            buffer.clear()
            diff.reset()
            suppress--
        }
    }

    private fun sendText(text: CharSequence) {
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            if (c == '\n' || c == '\t') {
                if (i > start) sink.text(text.subSequence(start, i))
                keyboard.tap(if (c == '\n') Keys.ENTER else Keys.TAB)
                start = i + 1
            }
        }
        if (start < text.length) sink.text(text.subSequence(start, text.length))
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val r = super.commitText(text, newCursorPosition)
        sync()
        return r
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val r = super.setComposingText(text, newCursorPosition)
        sync()
        return r
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val r = super.setComposingRegion(start, end)
        sync()
        return r
    }

    override fun finishComposingText(): Boolean {
        val r = super.finishComposingText()
        sync()
        return r
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val r = super.deleteSurroundingText(beforeLength, afterLength)
        sync()
        return r
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val r = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        sync()
        return r
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val r = super.setSelection(start, end)
        // moving the caret inside already-sent text: forget the tail so edits go to the right place
        diff.onCursorMoved(buffer, start.coerceIn(0, buffer.length)) { backspaces, text ->
            repeat(backspaces) { keyboard.tap(Keys.BACKSPACE) }
            if (text.isNotEmpty()) sendText(text)
        }
        return r
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        keyboard.tap(Keys.ENTER)
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        val consumer = HidKeymap.consumerForKeyCode(event.keyCode)
        if (consumer != 0) {
            sink.consumer(if (event.action == KeyEvent.ACTION_DOWN) consumer else 0)
            return true
        }
        val usage = HidKeymap.usageForKeyCode(event.keyCode)
        if (usage == 0) {
            // printable char from a hardware keyboard with no keycode mapping
            val uc = event.unicodeChar
            if (event.action == KeyEvent.ACTION_DOWN && uc != 0 && uc and android.view.KeyCharacterMap.COMBINING_ACCENT == 0) {
                sink.text(uc.toChar().toString())
            }
            return true
        }
        val mods = HidKeymap.modsForMeta(event.metaState)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Backspace/Delete from the IME also change the shadow buffer through deleteSurroundingText;
                // most IMEs send only one of the two, so mirror the buffer here and let the diff decide.
                if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                    val cur = android.text.Selection.getSelectionEnd(buffer)
                    if (cur > 0 && getComposingSpanStart(buffer) < 0) {
                        suppress++
                        buffer.delete(cur - 1, cur)
                        suppress--
                        diff.update(buffer, cur - 1) { backspaces, text ->
                            repeat(backspaces) { keyboard.tap(Keys.BACKSPACE) }
                            if (text.isNotEmpty()) sendText(text)
                        }
                        return true
                    }
                    keyboard.press(usage, mods)
                    return true
                }
                keyboard.press(usage, mods)
            }
            KeyEvent.ACTION_UP -> keyboard.release(usage)
        }
        return true
    }

    fun clear() {
        suppress++
        buffer.clear()
        diff.reset()
        suppress--
    }
}

/**
 * Minimal-edit tracker: `sent` is what the host has, `buffer[0..cursor)` is what it should have.
 * Text after the cursor is ignored (the IME only ever edits at the caret in our hidden field).
 */
class TextDiff {
    private val sent = StringBuilder()

    fun reset() = sent.setLength(0)

    fun update(buffer: CharSequence, cursor: Int, emit: (backspaces: Int, text: CharSequence) -> Unit) {
        val target = buffer.subSequence(0, cursor.coerceIn(0, buffer.length))
        var p = 0
        val n = minOf(sent.length, target.length)
        while (p < n && sent[p] == target[p]) p++
        // never split a surrogate pair
        if (p > 0 && p < target.length && Character.isLowSurrogate(target[p]) && Character.isHighSurrogate(target[p - 1])) p--
        val backspaces = countCodePoints(sent, p, sent.length)
        val tail = target.subSequence(p, target.length)
        if (backspaces > 0 || tail.isNotEmpty()) emit(backspaces, tail)
        sent.setLength(0)
        sent.append(target)
    }

    /** Caret moved: text after the new caret is considered already-sent and untouchable; nothing is emitted. */
    fun onCursorMoved(buffer: CharSequence, cursor: Int, emit: (Int, CharSequence) -> Unit) {
        // If the caret moved backwards inside sent text, we can't move the host caret reliably
        // (no selection model on the host). Keep 'sent' as is; the next edit will diff from there.
        @Suppress("UNUSED_VARIABLE") val unused = emit
        if (cursor >= sent.length) return
    }

    val sentText: String get() = sent.toString()

    private fun countCodePoints(s: CharSequence, from: Int, to: Int): Int {
        var n = 0
        var i = from
        while (i < to) {
            n++
            i += if (Character.isHighSurrogate(s[i]) && i + 1 < to && Character.isLowSurrogate(s[i + 1])) 2 else 1
        }
        return n
    }
}
