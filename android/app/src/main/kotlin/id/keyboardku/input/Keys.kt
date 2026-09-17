package id.keyboardku.input

/** HID usages (keyboard page 0x07), modifier bits, consumer usages, and the special-key bar table. */
object Keys {
    // modifier bits (HID keyboard report byte 0)
    const val MOD_LCTRL = 0x01
    const val MOD_LSHIFT = 0x02
    const val MOD_LALT = 0x04
    const val MOD_LGUI = 0x08
    const val MOD_RCTRL = 0x10
    const val MOD_RSHIFT = 0x20
    const val MOD_RALT = 0x40
    const val MOD_RGUI = 0x80

    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val CAPS = 0x39
    const val F1 = 0x3A
    const val PRTSC = 0x46
    const val SCROLL_LOCK = 0x47
    const val PAUSE = 0x48
    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PGUP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PGDN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val MENU = 0x65

    // consumer page 0x0C
    const val C_VOL_UP = 0xE9
    const val C_VOL_DOWN = 0xEA
    const val C_MUTE = 0xE2
    const val C_PLAY_PAUSE = 0xCD
    const val C_NEXT = 0xB5
    const val C_PREV = 0xB6
    const val C_STOP = 0xB7

    // mouse button bits
    const val BTN_LEFT = 0x01
    const val BTN_RIGHT = 0x02
    const val BTN_MIDDLE = 0x04
    const val BTN_X1 = 0x08
    const val BTN_X2 = 0x10

    /** One entry of the special-keys bar. `mod` != 0 marks a latching modifier button; `consumer` marks a media key. */
    class Special(val label: String, val usage: Int = 0, val mod: Int = 0, val consumer: Int = 0, val mods: Int = 0)

    val SPECIALS: List<Special> = listOf(
        Special("Esc", usage = ESC),
        Special("Tab", usage = TAB),
        Special("Ctrl", mod = MOD_LCTRL),
        Special("Alt", mod = MOD_LALT),
        Special("Shift", mod = MOD_LSHIFT),
        Special("Win", mod = MOD_LGUI),
        Special("←", usage = LEFT),
        Special("↑", usage = UP),
        Special("↓", usage = DOWN),
        Special("→", usage = RIGHT),
        Special("Del", usage = DELETE),
        Special("Ins", usage = INSERT),
        Special("Home", usage = HOME),
        Special("End", usage = END),
        Special("PgUp", usage = PGUP),
        Special("PgDn", usage = PGDN),
        Special("Alt+Tab", usage = TAB, mods = MOD_LALT),
        Special("Win+D", usage = 0x07, mods = MOD_LGUI),
        Special("F1", usage = F1), Special("F2", usage = F1 + 1), Special("F3", usage = F1 + 2), Special("F4", usage = F1 + 3),
        Special("F5", usage = F1 + 4), Special("F6", usage = F1 + 5), Special("F7", usage = F1 + 6), Special("F8", usage = F1 + 7),
        Special("F9", usage = F1 + 8), Special("F10", usage = F1 + 9), Special("F11", usage = F1 + 10), Special("F12", usage = F1 + 11),
        Special("PrtSc", usage = PRTSC),
        Special("Menu", usage = MENU),
        Special("Vol-", consumer = C_VOL_DOWN),
        Special("Vol+", consumer = C_VOL_UP),
        Special("Mute", consumer = C_MUTE),
        Special("⏯", consumer = C_PLAY_PAUSE),
        Special("⏮", consumer = C_PREV),
        Special("⏭", consumer = C_NEXT),
    )
}
