package id.keyboardku.transport

enum class TransportState {
    IDLE, PROBING, DISCOVERABLE, PAIRING, CONNECTING, CONNECTED, RECONNECTING, UNSUPPORTED, ERROR
}

/**
 * A link to the host. All `send*` methods are called on the main thread only and must never block
 * for longer than a syscall. Returning `false` from a motion send means "not sent, keep the delta".
 */
interface Transport {
    val name: String
    val state: TransportState
    /** Minimum spacing between motion reports: 6 ms on Bluetooth (sniff anchor), 2 ms on WiFi. */
    val minMoveIntervalNanos: Long
    val supportsUnicode: Boolean
    var listener: Listener?

    fun start()
    fun stop()

    /** Relative pointer delta in pixels (already accelerated). */
    fun sendMouse(dx: Int, dy: Int): Boolean
    /** Scroll delta in 1/120-notch units. The transport keeps its own carry if it needs whole notches. */
    fun sendScroll(v120: Int, h120: Int): Boolean
    fun sendButtons(mask: Int)
    fun sendKeys(mods: Int, keys: ByteArray)
    /** Sends non-ASCII text; returns the number of chars that could NOT be sent (BT HID cannot type them). */
    fun sendText(cs: CharSequence, start: Int, end: Int): Int
    fun sendConsumer(usage: Int)
    fun releaseAll()
    /** Called when user activity starts/stops (WiFi low-latency lock scoping, heartbeat rate). */
    fun setActive(active: Boolean)

    interface Listener {
        fun onStateChanged(t: Transport, state: TransportState, detail: String?)
        fun onRtt(t: Transport, rttMs: Float)
        /** The transport needs a 6-digit pairing code from the user (WiFi first pairing). */
        fun onPairingCodeNeeded(t: Transport, serverName: String)
        /** More than one server answered discovery; the UI should let the user choose. */
        fun onServerChoice(t: Transport, names: List<String>)
    }
}
