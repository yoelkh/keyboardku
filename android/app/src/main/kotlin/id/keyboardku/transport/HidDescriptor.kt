package id.keyboardku.transport

/**
 * Combo HID report descriptor: keyboard (report 1, 8 bytes), mouse with 16-bit relative X/Y +
 * wheel + AC pan (report 2, 7 bytes), consumer control (report 3, 2 bytes). Boot-compatible.
 * See docs/HID.md.
 */
object HidDescriptor {
    const val ID_KEYBOARD = 1
    const val ID_MOUSE = 2
    const val ID_CONSUMER = 3
    const val KEYBOARD_LEN = 8
    const val MOUSE_LEN = 7
    const val MOUSE_BOOT_LEN = 3
    const val CONSUMER_LEN = 2

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    val DESCRIPTOR: ByteArray = b(
        // ---------- Keyboard, report ID 1 ----------
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x06,       // Usage (Keyboard)
        0xA1, 0x01,       // Collection (Application)
        0x85, ID_KEYBOARD,//   Report ID (1)
        0x05, 0x07,       //   Usage Page (Key Codes)
        0x19, 0xE0,       //   Usage Min (Left Control)
        0x29, 0xE7,       //   Usage Max (Right GUI)
        0x15, 0x00,       //   Logical Min (0)
        0x25, 0x01,       //   Logical Max (1)
        0x75, 0x01,       //   Report Size (1)
        0x95, 0x08,       //   Report Count (8)
        0x81, 0x02,       //   Input (Data, Var, Abs)      modifiers
        0x95, 0x01,       //   Report Count (1)
        0x75, 0x08,       //   Report Size (8)
        0x81, 0x01,       //   Input (Const)               reserved
        0x95, 0x05,       //   Report Count (5)
        0x75, 0x01,       //   Report Size (1)
        0x05, 0x08,       //   Usage Page (LEDs)
        0x19, 0x01,       //   Usage Min (Num Lock)
        0x29, 0x05,       //   Usage Max (Kana)
        0x91, 0x02,       //   Output (Data, Var, Abs)     LEDs
        0x95, 0x01,       //   Report Count (1)
        0x75, 0x03,       //   Report Size (3)
        0x91, 0x01,       //   Output (Const)              LED padding
        0x95, 0x06,       //   Report Count (6)
        0x75, 0x08,       //   Report Size (8)
        0x15, 0x00,       //   Logical Min (0)
        0x26, 0xFF, 0x00, //   Logical Max (255)
        0x05, 0x07,       //   Usage Page (Key Codes)
        0x19, 0x00,       //   Usage Min (0)
        0x2A, 0xFF, 0x00, //   Usage Max (255)
        0x81, 0x00,       //   Input (Data, Array)         6 key codes
        0xC0,             // End Collection
        // ---------- Mouse, report ID 2 ----------
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x02,       // Usage (Mouse)
        0xA1, 0x01,       // Collection (Application)
        0x85, ID_MOUSE,   //   Report ID (2)
        0x09, 0x01,       //   Usage (Pointer)
        0xA1, 0x00,       //   Collection (Physical)
        0x05, 0x09,       //     Usage Page (Buttons)
        0x19, 0x01,       //     Usage Min (1)
        0x29, 0x03,       //     Usage Max (3)
        0x15, 0x00,       //     Logical Min (0)
        0x25, 0x01,       //     Logical Max (1)
        0x95, 0x03,       //     Report Count (3)
        0x75, 0x01,       //     Report Size (1)
        0x81, 0x02,       //     Input (Data, Var, Abs)    L R M
        0x95, 0x01,       //     Report Count (1)
        0x75, 0x05,       //     Report Size (5)
        0x81, 0x03,       //     Input (Const)             padding
        0x05, 0x01,       //     Usage Page (Generic Desktop)
        0x09, 0x30,       //     Usage (X)
        0x09, 0x31,       //     Usage (Y)
        0x16, 0x01, 0x80, //     Logical Min (-32767)
        0x26, 0xFF, 0x7F, //     Logical Max (32767)
        0x75, 0x10,       //     Report Size (16)
        0x95, 0x02,       //     Report Count (2)
        0x81, 0x06,       //     Input (Data, Var, Rel)    X, Y
        0x09, 0x38,       //     Usage (Wheel)
        0x15, 0x81,       //     Logical Min (-127)
        0x25, 0x7F,       //     Logical Max (127)
        0x75, 0x08,       //     Report Size (8)
        0x95, 0x01,       //     Report Count (1)
        0x81, 0x06,       //     Input (Data, Var, Rel)    wheel
        0x05, 0x0C,       //     Usage Page (Consumer)
        0x0A, 0x38, 0x02, //     Usage (AC Pan)
        0x15, 0x81,       //     Logical Min (-127)
        0x25, 0x7F,       //     Logical Max (127)
        0x75, 0x08,       //     Report Size (8)
        0x95, 0x01,       //     Report Count (1)
        0x81, 0x06,       //     Input (Data, Var, Rel)    horizontal pan
        0xC0,             //   End Collection
        0xC0,             // End Collection
        // ---------- Consumer control, report ID 3 ----------
        0x05, 0x0C,       // Usage Page (Consumer)
        0x09, 0x01,       // Usage (Consumer Control)
        0xA1, 0x01,       // Collection (Application)
        0x85, ID_CONSUMER,//   Report ID (3)
        0x15, 0x00,       //   Logical Min (0)
        0x26, 0xFF, 0x03, //   Logical Max (1023)
        0x19, 0x00,       //   Usage Min (0)
        0x2A, 0xFF, 0x03, //   Usage Max (1023)
        0x75, 0x10,       //   Report Size (16)
        0x95, 0x01,       //   Report Count (1)
        0x81, 0x00,       //   Input (Data, Array)         one 16-bit usage
        0xC0,             // End Collection
    )
}
