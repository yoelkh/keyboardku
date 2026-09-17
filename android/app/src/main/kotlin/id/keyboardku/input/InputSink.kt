package id.keyboardku.input

/**
 * Everything the UI produces flows through this single interface, always on the main thread.
 * Mouse motion and scroll are fractional and accumulated by the implementation (ReportScheduler);
 * keys/buttons are full HID-style states so a lost packet can never leave something stuck.
 */
interface InputSink {
    /** Relative pointer motion in host pixels (fractional; remainder is carried). */
    fun move(dx: Float, dy: Float)

    /** Scroll in 1/120-notch units; +v = wheel away from the user (page up). */
    fun scroll(v120: Float, h120: Float)

    /** Full mouse button state (Keys.BTN_*). */
    fun buttons(mask: Int)

    /** Full keyboard state: HID modifier bits + up to 6 usages (zero-padded, exactly 6 entries). */
    fun keyState(mods: Int, keys: ByteArray)

    /** Free text (from the IME). ASCII goes as key taps; other characters as Unicode when supported. */
    fun text(cs: CharSequence)

    /** Consumer-control usage currently held (0 = release). */
    fun consumer(usage: Int)

    /** Release every key, button and consumer usage on the host. */
    fun releaseAll()
}
