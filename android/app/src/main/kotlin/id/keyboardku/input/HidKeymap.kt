package id.keyboardku.input

import android.view.KeyEvent

/**
 * Character -> HID usage + modifiers (US layout) and Android KeyEvent keycode -> HID usage.
 * Pure Kotlin, table-driven, allocation-free lookups.
 */
object HidKeymap {
    /** Packed table for chars 0..127: low byte = usage, bit 8 = needs Shift. 0 = unmappable. */
    private val ASCII = IntArray(128)

    private const val SHIFT = 0x100

    init {
        for (c in 'a'..'z') ASCII[c.code] = 0x04 + (c - 'a')
        for (c in 'A'..'Z') ASCII[c.code] = (0x04 + (c - 'A')) or SHIFT
        for (c in '1'..'9') ASCII[c.code] = 0x1E + (c - '1')
        ASCII['0'.code] = 0x27
        ASCII['\n'.code] = Keys.ENTER
        ASCII['\r'.code] = Keys.ENTER
        ASCII[0x1B] = Keys.ESC
        ASCII[0x08] = Keys.BACKSPACE
        ASCII['\t'.code] = Keys.TAB
        ASCII[' '.code] = Keys.SPACE
        ASCII['-'.code] = 0x2D; ASCII['_'.code] = 0x2D or SHIFT
        ASCII['='.code] = 0x2E; ASCII['+'.code] = 0x2E or SHIFT
        ASCII['['.code] = 0x2F; ASCII['{'.code] = 0x2F or SHIFT
        ASCII[']'.code] = 0x30; ASCII['}'.code] = 0x30 or SHIFT
        ASCII['\\'.code] = 0x31; ASCII['|'.code] = 0x31 or SHIFT
        ASCII[';'.code] = 0x33; ASCII[':'.code] = 0x33 or SHIFT
        ASCII['\''.code] = 0x34; ASCII['"'.code] = 0x34 or SHIFT
        ASCII['`'.code] = 0x35; ASCII['~'.code] = 0x35 or SHIFT
        ASCII[','.code] = 0x36; ASCII['<'.code] = 0x36 or SHIFT
        ASCII['.'.code] = 0x37; ASCII['>'.code] = 0x37 or SHIFT
        ASCII['/'.code] = 0x38; ASCII['?'.code] = 0x38 or SHIFT
        ASCII['!'.code] = 0x1E or SHIFT
        ASCII['@'.code] = 0x1F or SHIFT
        ASCII['#'.code] = 0x20 or SHIFT
        ASCII['$'.code] = 0x21 or SHIFT
        ASCII['%'.code] = 0x22 or SHIFT
        ASCII['^'.code] = 0x23 or SHIFT
        ASCII['&'.code] = 0x24 or SHIFT
        ASCII['*'.code] = 0x25 or SHIFT
        ASCII['('.code] = 0x26 or SHIFT
        ASCII[')'.code] = 0x27 or SHIFT
        ASCII[0x7F] = Keys.DELETE
    }

    /** HID usage for a char, or 0 if it cannot be typed on a US layout. */
    fun usageFor(c: Char): Int = if (c.code < 128) ASCII[c.code] and 0xFF else 0

    /** Modifier bits needed to type [c] (Shift or 0). */
    fun modsFor(c: Char): Int = if (c.code < 128 && (ASCII[c.code] and SHIFT) != 0) Keys.MOD_LSHIFT else 0

    /** Android keycode -> HID usage (0 if none). Covers hardware keyboards and IME-sent key events. */
    fun usageForKeyCode(keyCode: Int): Int = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x04 + (keyCode - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1E + (keyCode - KeyEvent.KEYCODE_1)
        KeyEvent.KEYCODE_0 -> 0x27
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Keys.ENTER
        KeyEvent.KEYCODE_ESCAPE -> Keys.ESC
        KeyEvent.KEYCODE_DEL -> Keys.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> Keys.DELETE
        KeyEvent.KEYCODE_TAB -> Keys.TAB
        KeyEvent.KEYCODE_SPACE -> Keys.SPACE
        KeyEvent.KEYCODE_MINUS -> 0x2D
        KeyEvent.KEYCODE_EQUALS -> 0x2E
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
        KeyEvent.KEYCODE_BACKSLASH -> 0x31
        KeyEvent.KEYCODE_SEMICOLON -> 0x33
        KeyEvent.KEYCODE_APOSTROPHE -> 0x34
        KeyEvent.KEYCODE_GRAVE -> 0x35
        KeyEvent.KEYCODE_COMMA -> 0x36
        KeyEvent.KEYCODE_PERIOD -> 0x37
        KeyEvent.KEYCODE_SLASH -> 0x38
        KeyEvent.KEYCODE_CAPS_LOCK -> Keys.CAPS
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Keys.F1 + (keyCode - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_SYSRQ -> Keys.PRTSC
        KeyEvent.KEYCODE_SCROLL_LOCK -> Keys.SCROLL_LOCK
        KeyEvent.KEYCODE_BREAK -> Keys.PAUSE
        KeyEvent.KEYCODE_INSERT -> Keys.INSERT
        KeyEvent.KEYCODE_MOVE_HOME -> Keys.HOME
        KeyEvent.KEYCODE_PAGE_UP -> Keys.PGUP
        KeyEvent.KEYCODE_MOVE_END -> Keys.END
        KeyEvent.KEYCODE_PAGE_DOWN -> Keys.PGDN
        KeyEvent.KEYCODE_DPAD_RIGHT -> Keys.RIGHT
        KeyEvent.KEYCODE_DPAD_LEFT -> Keys.LEFT
        KeyEvent.KEYCODE_DPAD_DOWN -> Keys.DOWN
        KeyEvent.KEYCODE_DPAD_UP -> Keys.UP
        KeyEvent.KEYCODE_MENU -> Keys.MENU
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xE0
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xE1
        KeyEvent.KEYCODE_ALT_LEFT -> 0xE2
        KeyEvent.KEYCODE_META_LEFT -> 0xE3
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xE4
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xE5
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xE6
        KeyEvent.KEYCODE_META_RIGHT -> 0xE7
        else -> 0
    }

    /** Android meta state -> HID modifier bits. */
    fun modsForMeta(meta: Int): Int {
        var m = 0
        if (meta and KeyEvent.META_CTRL_ON != 0) m = m or Keys.MOD_LCTRL
        if (meta and KeyEvent.META_SHIFT_ON != 0) m = m or Keys.MOD_LSHIFT
        if (meta and KeyEvent.META_ALT_ON != 0) m = m or Keys.MOD_LALT
        if (meta and KeyEvent.META_META_ON != 0) m = m or Keys.MOD_LGUI
        return m
    }

    /** Consumer usage for volume/media hardware keys, 0 otherwise. */
    fun consumerForKeyCode(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> Keys.C_VOL_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> Keys.C_VOL_DOWN
        KeyEvent.KEYCODE_VOLUME_MUTE -> Keys.C_MUTE
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> Keys.C_PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT -> Keys.C_NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Keys.C_PREV
        else -> 0
    }
}
