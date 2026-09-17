package id.keyboardku.input

/**
 * Keyboard state machine: which usages are held (max 6) and which modifiers are latched.
 * Emits full HID-style states to the sink, so the host never sees an inconsistent half-update.
 *
 * Modifier buttons: tap = one-shot (applies to the next key, then clears), long-press = locked
 * (stays until tapped again). While a modifier is latched it is also reported as held, so
 * Ctrl+click on the trackpad works.
 */
class KeyboardState(private val sink: InputSink) {
    private val held = ByteArray(6)
    var lockedMods = 0
        private set
    var oneShotMods = 0
        private set
    var listener: (() -> Unit)? = null

    val effectiveMods: Int get() = lockedMods or oneShotMods

    private fun emit(extraMods: Int = 0) {
        sink.keyState(effectiveMods or extraMods, held)
    }

    private fun add(usage: Int): Boolean {
        val u = usage.toByte()
        for (b in held) if (b == u) return true
        for (i in held.indices) if (held[i].toInt() == 0) { held[i] = u; return true }
        return false // rollover: 6 keys already held
    }

    private fun remove(usage: Int) {
        val u = usage.toByte()
        for (i in held.indices) if (held[i] == u) held[i] = 0
    }

    fun press(usage: Int, extraMods: Int = 0) {
        if (usage == 0) return
        if (add(usage)) emit(extraMods)
    }

    fun release(usage: Int) {
        if (usage == 0) return
        remove(usage)
        val hadOneShot = oneShotMods != 0
        oneShotMods = 0
        emit()
        if (hadOneShot) listener?.invoke()
    }

    /** Press + release with optional extra modifiers (e.g. Shift for '!'). */
    fun tap(usage: Int, extraMods: Int = 0) {
        if (usage == 0) return
        press(usage, extraMods)
        release(usage)
    }

    /** Type one char via the US keymap; returns false if it has no key. */
    fun typeChar(c: Char): Boolean {
        val u = HidKeymap.usageFor(c)
        if (u == 0) return false
        tap(u, HidKeymap.modsFor(c))
        return true
    }

    fun toggleOneShot(mod: Int) {
        if (lockedMods and mod != 0) {
            lockedMods = lockedMods and mod.inv()
        } else {
            oneShotMods = oneShotMods xor mod
        }
        emit()
        listener?.invoke()
    }

    fun toggleLock(mod: Int) {
        oneShotMods = oneShotMods and mod.inv()
        lockedMods = lockedMods xor mod
        emit()
        listener?.invoke()
    }

    fun isLocked(mod: Int) = lockedMods and mod != 0
    fun isOneShot(mod: Int) = oneShotMods and mod != 0

    fun releaseAll() {
        held.fill(0)
        lockedMods = 0
        oneShotMods = 0
        emit()
        listener?.invoke()
    }
}
