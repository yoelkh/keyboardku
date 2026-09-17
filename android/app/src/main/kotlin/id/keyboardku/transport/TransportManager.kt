package id.keyboardku.transport

import android.content.Context
import android.os.Handler
import id.keyboardku.Log
import id.keyboardku.Prefs
import id.keyboardku.input.ReportScheduler

/**
 * Picks and supervises the active transport. AUTO = Bluetooth HID when the ROM supports it,
 * otherwise WiFi; switching always releases everything on the old path first.
 */
class TransportManager(
    context: Context,
    private val prefs: Prefs,
    private val main: Handler,
    private val scheduler: ReportScheduler,
) : Transport.Listener {
    val bt = BtHidTransport(context, prefs, main)
    val udp = UdpTransport(context, prefs, main)

    var current: Transport? = null
        private set
    var uiListener: Transport.Listener? = null
    private var running = false

    init {
        bt.listener = this
        udp.listener = this
    }

    fun start() {
        running = true
        when (prefs.transportMode) {
            "BT" -> useBt()
            "WIFI" -> useUdp()
            else -> if (prefs.hidUnsupported) useUdp() else useBt()
        }
    }

    fun stop() {
        running = false
        scheduler.releaseAll()
        scheduler.transport = null
        bt.stop()
        udp.stop()
        current = null
    }

    fun switchTo(mode: String) {
        prefs.transportMode = mode
        if (running) start()
    }

    fun retry() {
        when (val c = current) {
            is UdpTransport -> c.retry()
            is BtHidTransport -> { prefs.hidUnsupported = false; c.stop(); c.start() }
            else -> start()
        }
    }

    private fun useBt() {
        if (current === bt && bt.state != TransportState.IDLE && bt.state != TransportState.UNSUPPORTED && bt.state != TransportState.ERROR) return
        if (current === udp) { scheduler.releaseAll(); udp.stop() }
        current = bt
        scheduler.transport = bt
        bt.start()
        Log.i("transport: Bluetooth")
    }

    private fun useUdp() {
        if (current === udp && udp.state != TransportState.IDLE) return
        if (current === bt) { scheduler.releaseAll(); bt.stop() }
        current = udp
        scheduler.transport = udp
        udp.start()
        Log.i("transport: WiFi")
    }

    override fun onStateChanged(t: Transport, state: TransportState, detail: String?) {
        if (t !== current) return
        if (t === bt && prefs.transportMode == "AUTO" && (state == TransportState.UNSUPPORTED || state == TransportState.ERROR)) {
            uiListener?.onStateChanged(t, state, detail)
            main.postDelayed({ if (running && current === bt && (bt.state == TransportState.UNSUPPORTED || bt.state == TransportState.ERROR)) useUdp() }, 1500)
            return
        }
        uiListener?.onStateChanged(t, state, detail)
    }

    override fun onRtt(t: Transport, rttMs: Float) { if (t === current) uiListener?.onRtt(t, rttMs) }
    override fun onPairingCodeNeeded(t: Transport, serverName: String) { if (t === current) uiListener?.onPairingCodeNeeded(t, serverName) }
    override fun onServerChoice(t: Transport, names: List<String>) { if (t === current) uiListener?.onServerChoice(t, names) }
}
