package id.keyboardku.transport

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import id.keyboardku.Log
import id.keyboardku.Prefs
import id.keyboardku.transport.UdpProtocol as P
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.security.SecureRandom

/**
 * WiFi/USB-tethering/hotspot transport: encrypted UDP to the Rust server.
 *
 * Main thread: state machine, all sends (blocking channel, UDP send is a single syscall).
 * Worker thread: discovery + handshake (DISCOVER -> OFFER -> HELLO/PAIR -> WELCOME).
 * Rx thread: PONG (RTT) and REJECT.
 */
class UdpTransport(context: Context, private val prefs: Prefs, private val main: Handler) : Transport {
    override val name = "WiFi"
    override val minMoveIntervalNanos = 2_000_000L
    override val supportsUnicode = true
    override var listener: Transport.Listener? = null
    override var state: TransportState = TransportState.IDLE
        private set

    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val wifiLock: WifiManager.WifiLock = wifi.createWifiLock(
        if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "KeyboardKu",
    ).also { it.setReferenceCounted(false) }

    // ---- session (main thread) ----
    private var started = false
    private var channel: DatagramChannel? = null
    private var aead: Aead? = null
    private var sessionId = 0
    private var seq = 0
    private var cumX = 0
    private var cumY = 0
    private var cumV = 0
    private var cumH = 0
    private var textSeq = 0
    private val pkt = ByteArray(P.DATA_LEN)
    private val pktBuf: ByteBuffer = ByteBuffer.wrap(pkt)
    private var rxThread: Thread? = null
    private var worker: Thread? = null
    var serverName: String? = null
        private set
    var hostScreenWidth = 0
        private set
    var hostScreenHeight = 0
        private set

    private var lastMods = 0
    private val lastKeys = ByteArray(6)
    private var lastButtons = 0
    private var lastConsumer = 0
    private var active = false
    @Volatile private var lastPongMs = 0L
    private var beatsSincePong = 0
    private var pendingCode: String? = null
    private var reconnectAttempt = 0

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (state != TransportState.CONNECTED) return
            sendHeartbeat()
            beatsSincePong++
            val now = SystemClock.uptimeMillis()
            if (beatsSincePong >= 3 && now - lastPongMs > 3000) {
                Log.w("no PONG for ${now - lastPongMs} ms -> reconnecting")
                beginReconnect()
                return
            }
            main.postDelayed(this, if (active || heldFlag()) 100L else 2000L)
        }
    }
    private val resendKeysRunnable = Runnable { if (state == TransportState.CONNECTED) sendKeysPacket() }
    private val resendButtonsRunnable = Runnable { if (state == TransportState.CONNECTED) sendButtonsPacket() }
    private val resendConsumerRunnable = Runnable { if (state == TransportState.CONNECTED) sendConsumerPacket() }

    private fun heldFlag(): Boolean = lastButtons != 0 || lastMods != 0 || lastKeys.any { it.toInt() != 0 } || lastConsumer != 0

    private fun setState(s: TransportState, detail: String? = null) {
        state = s
        listener?.onStateChanged(this, s, detail)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun start() {
        if (started) return
        started = true
        reconnectAttempt = 0
        connectAsync()
    }

    override fun stop() {
        started = false
        main.removeCallbacks(heartbeatRunnable)
        if (state == TransportState.CONNECTED) {
            try { releaseAll() } catch (_: Exception) {}
        }
        closeSession()
        wifiLock.release()
        setState(TransportState.IDLE)
    }

    /** UI supplies the pairing code after [Transport.Listener.onPairingCodeNeeded]. */
    fun pairWithCode(code: String) {
        pendingCode = code
        if (started) connectAsync()
    }

    fun retry() {
        if (started) connectAsync()
    }

    private fun closeSession() {
        rxThread?.interrupt()
        rxThread = null
        try { channel?.close() } catch (_: Exception) {}
        channel = null
        aead = null
    }

    private fun beginReconnect() {
        main.removeCallbacks(heartbeatRunnable)
        closeSession()
        setState(TransportState.RECONNECTING)
        connectAsync()
    }

    private fun connectAsync() {
        if (worker?.isAlive == true) return
        if (state != TransportState.RECONNECTING) setState(TransportState.CONNECTING, "Mencari server...")
        val code = pendingCode
        val manualIp = prefs.manualServerIp.trim()
        val preferred = prefs.preferredServerName
        val peerId = prefs.peerId
        worker = Thread({
            val r = Handshaker(prefs, peerId, manualIp, preferred, code).run()
            main.post { onHandshakeResult(r) }
        }, "kbku-handshake").also { it.start() }
    }

    private fun onHandshakeResult(r: HandshakeResult) {
        if (!started) { r.channel?.close(); return }
        when (r.kind) {
            HandshakeResult.Kind.OK -> {
                pendingCode = null
                reconnectAttempt = 0
                channel = r.channel
                aead = r.aead
                sessionId = r.sessionId
                seq = 0
                cumX = 0; cumY = 0; cumV = 0; cumH = 0
                serverName = r.serverName
                hostScreenWidth = r.screenW
                hostScreenHeight = r.screenH
                if (r.screenW > 0) prefs.hostScreenWidth = r.screenW
                prefs.preferredServerName = r.serverName
                lastPongMs = SystemClock.uptimeMillis()
                beatsSincePong = 0
                startRx()
                setState(TransportState.CONNECTED, r.serverName)
                // first packets: clean slate on the host
                releaseAll()
                if (active) wifiLock.acquire()
                main.removeCallbacks(heartbeatRunnable)
                main.post(heartbeatRunnable)
            }
            HandshakeResult.Kind.NEED_CODE -> {
                setState(TransportState.PAIRING, r.serverName)
                listener?.onPairingCodeNeeded(this, r.serverName ?: "?")
            }
            HandshakeResult.Kind.WRONG_CODE -> {
                pendingCode = null
                setState(TransportState.PAIRING, "Kode salah")
                listener?.onPairingCodeNeeded(this, r.serverName ?: "?")
            }
            HandshakeResult.Kind.CHOOSE -> {
                setState(TransportState.CONNECTING, "Pilih server")
                listener?.onServerChoice(this, r.names)
            }
            HandshakeResult.Kind.FAIL -> {
                if (started) {
                    reconnectAttempt++
                    val delay = when (reconnectAttempt) { 1 -> 1000L; 2 -> 2000L; 3 -> 3000L; else -> 5000L }
                    setState(if (reconnectAttempt > 1) TransportState.RECONNECTING else TransportState.CONNECTING, r.detail)
                    main.postDelayed({ if (started && state != TransportState.CONNECTED) connectAsync() }, delay)
                }
            }
        }
    }

    private fun startRx() {
        val ch = channel ?: return
        val a = aead ?: return
        val sid = sessionId
        rxThread = Thread({
            val buf = ByteBuffer.allocate(64)
            val arr = buf.array()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    buf.clear()
                    val n = ch.read(buf)
                    if (n != P.DATA_LEN || !P.isMagic(arr, n)) continue
                    val ty = P.headerType(arr)
                    val sq = P.headerSeq(arr)
                    if (ty == P.T_PONG && P.headerSession(arr) == sid && a.open(arr, sid, sq)) {
                        val echo = P.rdU32(arr, 8)
                        val now = SystemClock.uptimeMillis()
                        val rtt = (now.toInt() - echo)
                        lastPongMs = now
                        main.post {
                            beatsSincePong = 0
                            listener?.onRtt(this, rtt.toFloat())
                        }
                    }
                }
            } catch (_: IOException) {
                // channel closed
            }
        }, "kbku-rx").also { it.isDaemon = true; it.start() }
    }

    // ------------------------------------------------------------------ sending (main thread)

    private fun sendPacket(type: Int): Boolean {
        val ch = channel ?: return false
        val a = aead ?: return false
        seq++
        P.writeHeader(pkt, type, sessionId, seq)
        a.seal(pkt, sessionId, seq)
        pktBuf.clear()
        return try {
            ch.write(pktBuf) == P.DATA_LEN
        } catch (e: IOException) {
            Log.w("send failed: $e")
            false
        }
    }

    private fun zeroPayload() {
        for (i in 8 until 16) pkt[i] = 0
    }

    override fun sendMouse(dx: Int, dy: Int): Boolean {
        if (state != TransportState.CONNECTED) return false
        val nx = cumX + dx
        val ny = cumY + dy
        zeroPayload()
        P.wrU32(pkt, 8, nx)
        P.wrU32(pkt, 12, ny)
        if (!sendPacket(P.T_MOUSE)) return false
        cumX = nx; cumY = ny
        return true
    }

    override fun sendScroll(v120: Int, h120: Int): Boolean {
        if (state != TransportState.CONNECTED) return false
        val nv = cumV + v120
        val nh = cumH + h120
        zeroPayload()
        P.wrU32(pkt, 8, nv)
        P.wrU32(pkt, 12, nh)
        if (!sendPacket(P.T_SCROLL)) return false
        cumV = nv; cumH = nh
        return true
    }

    private fun sendButtonsPacket() {
        zeroPayload()
        pkt[8] = lastButtons.toByte()
        sendPacket(P.T_BUTTONS)
    }

    override fun sendButtons(mask: Int) {
        lastButtons = mask
        if (state != TransportState.CONNECTED) return
        sendButtonsPacket()
        main.removeCallbacks(resendButtonsRunnable)
        main.postDelayed(resendButtonsRunnable, 2)
        main.postDelayed(resendButtonsRunnable, 4)
    }

    private fun sendKeysPacket() {
        zeroPayload()
        pkt[8] = lastMods.toByte()
        System.arraycopy(lastKeys, 0, pkt, 9, 6)
        sendPacket(P.T_KEYS)
    }

    override fun sendKeys(mods: Int, keys: ByteArray) {
        lastMods = mods
        System.arraycopy(keys, 0, lastKeys, 0, 6)
        if (state != TransportState.CONNECTED) return
        sendKeysPacket()
        main.removeCallbacks(resendKeysRunnable)
        main.postDelayed(resendKeysRunnable, 2)
        main.postDelayed(resendKeysRunnable, 4)
    }

    override fun sendText(cs: CharSequence, start: Int, end: Int): Int {
        if (state != TransportState.CONNECTED) return end - start
        var i = start
        while (i < end) {
            zeroPayload()
            var count = 0
            var j = i
            // up to 3 UTF-16 units, never splitting a surrogate pair
            while (j < end && count < 3) {
                val c = cs[j]
                if (Character.isHighSurrogate(c) && j + 1 < end && Character.isLowSurrogate(cs[j + 1])) {
                    if (count >= 2) break
                    P.wrU16(pkt, 10 + 2 * count, c.code); P.wrU16(pkt, 12 + 2 * count, cs[j + 1].code)
                    count += 2; j += 2
                } else {
                    P.wrU16(pkt, 10 + 2 * count, c.code)
                    count++; j++
                }
            }
            textSeq = (textSeq + 1) and 0xFF
            pkt[8] = count.toByte()
            pkt[9] = textSeq.toByte()
            val save = pkt.copyOfRange(8, 16)
            sendPacket(P.T_UNICODE)
            System.arraycopy(save, 0, pkt, 8, 8)
            sendPacket(P.T_UNICODE)
            i = j
        }
        return 0
    }

    private fun sendConsumerPacket() {
        zeroPayload()
        P.wrU16(pkt, 8, lastConsumer)
        sendPacket(P.T_CONSUMER)
    }

    override fun sendConsumer(usage: Int) {
        lastConsumer = usage
        if (state != TransportState.CONNECTED) return
        sendConsumerPacket()
        main.removeCallbacks(resendConsumerRunnable)
        main.postDelayed(resendConsumerRunnable, 2)
        main.postDelayed(resendConsumerRunnable, 4)
    }

    private fun sendHeartbeat() {
        zeroPayload()
        P.wrU32(pkt, 8, SystemClock.uptimeMillis().toInt())
        pkt[12] = if (heldFlag() || active) 1 else 0
        sendPacket(P.T_HEARTBEAT)
    }

    override fun releaseAll() {
        lastMods = 0; lastKeys.fill(0); lastButtons = 0; lastConsumer = 0
        if (state != TransportState.CONNECTED) return
        zeroPayload()
        sendPacket(P.T_RELEASE_ALL)
        sendKeysPacket()
        sendButtonsPacket()
    }

    override fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (state != TransportState.CONNECTED) return
        if (active) {
            wifiLock.acquire()
            main.removeCallbacks(heartbeatRunnable)
            main.post(heartbeatRunnable)
        } else {
            wifiLock.release()
        }
    }

    // ------------------------------------------------------------------ handshake (worker thread)

    class HandshakeResult(
        val kind: Kind,
        val channel: DatagramChannel? = null,
        val aead: Aead? = null,
        val sessionId: Int = 0,
        val serverName: String? = null,
        val screenW: Int = 0,
        val screenH: Int = 0,
        val names: List<String> = emptyList(),
        val detail: String? = null,
    ) {
        enum class Kind { OK, NEED_CODE, WRONG_CODE, CHOOSE, FAIL }
    }

    private class Offer(val addr: InetSocketAddress, val nonceS: ByteArray, val flags: Int, val name: String)

    private class Handshaker(
        private val prefs: Prefs,
        private val peerId: ByteArray,
        private val manualIp: String,
        private val preferred: String?,
        private val code: String?,
    ) {
        fun run(): HandshakeResult {
            var ch: DatagramChannel? = null
            try {
                ch = DatagramChannel.open()
                ch.socket().broadcast = true
                ch.socket().bind(InetSocketAddress(0))
                ch.socket().trafficClass = 0xC0 // CS6 -> AC_VO on precedence-mapping WiFi drivers
                ch.configureBlocking(false)
                val sel = Selector.open()
                ch.register(sel, SelectionKey.OP_READ)

                val offers = discover(ch, sel)
                if (offers.isEmpty()) return HandshakeResult(HandshakeResult.Kind.FAIL, detail = "Server tidak ditemukan")
                val chosen = offers.firstOrNull { it.name == preferred } ?: if (offers.size == 1) offers[0] else null
                if (chosen == null) {
                    ch.close()
                    return HandshakeResult(HandshakeResult.Kind.CHOOSE, names = offers.map { it.name })
                }

                var psk = prefs.pskFor(chosen.name)
                val pairCode: ByteArray? = when {
                    psk != null -> null
                    code != null -> code.toByteArray()
                    chosen.flags and P.OFFER_FLAG_NO_CODE != 0 -> "000000".toByteArray()
                    else -> { ch.close(); return HandshakeResult(HandshakeResult.Kind.NEED_CODE, serverName = chosen.name) }
                }
                val nonceC = ByteArray(8).also { SecureRandom().nextBytes(it) }
                val hello = ByteArray(P.HELLO_LEN)
                val k0: ByteArray?
                if (pairCode != null) {
                    k0 = Crypto.pairK0(pairCode, chosen.nonceS, nonceC)
                    P.writeHeader(hello, P.T_PAIR, 0, 0)
                    System.arraycopy(Crypto.pairMac(k0, chosen.nonceS, nonceC, peerId), 0, hello, 32, 16)
                    psk = Crypto.pskFromK0(k0)
                } else {
                    P.writeHeader(hello, P.T_HELLO, 0, 0)
                    System.arraycopy(Crypto.helloMac(psk!!, chosen.nonceS, nonceC, peerId), 0, hello, 32, 16)
                }
                System.arraycopy(chosen.nonceS, 0, hello, 8, 8)
                System.arraycopy(nonceC, 0, hello, 16, 8)
                System.arraycopy(peerId, 0, hello, 24, 8)

                val aead = Crypto.sessionKeys(psk!!, chosen.nonceS, nonceC)
                val buf = ByteBuffer.allocate(64)
                for (attempt in 0 until 3) {
                    ch.send(ByteBuffer.wrap(hello), chosen.addr)
                    val deadline = SystemClock.uptimeMillis() + 1500
                    while (true) {
                        val wait = deadline - SystemClock.uptimeMillis()
                        if (wait <= 0) break
                        if (sel.select(wait) == 0) continue
                        sel.selectedKeys().clear()
                        buf.clear()
                        val from = ch.receive(buf) ?: continue
                        val n = buf.position()
                        val arr = buf.array()
                        if (from != chosen.addr || !P.isMagic(arr, n)) continue
                        when (P.headerType(arr)) {
                            P.T_WELCOME -> {
                                if (n != P.DATA_LEN) continue
                                val sid = P.headerSession(arr)
                                if (!aead.open(arr, sid, 0)) {
                                    Log.w("WELCOME failed to decrypt")
                                    continue
                                }
                                val ver = P.rdU16(arr, 8)
                                val sw = P.rdU16(arr, 12)
                                val sh = P.rdU16(arr, 14)
                                if (pairCode != null) prefs.setPsk(chosen.name, psk)
                                sel.close()
                                ch.configureBlocking(true)
                                ch.connect(chosen.addr)
                                Log.i("connected to ${chosen.name} @ ${chosen.addr} session=$sid proto=$ver screen=${sw}x$sh")
                                return HandshakeResult(HandshakeResult.Kind.OK, ch, aead, sid, chosen.name, sw, sh)
                            }
                            P.T_REJECT -> {
                                val reason = arr[8].toInt()
                                val retryMs = P.rdU16(arr, 9)
                                ch.close()
                                return when (reason) {
                                    P.REJ_UNKNOWN_PEER -> { prefs.clearPsk(chosen.name); HandshakeResult(HandshakeResult.Kind.NEED_CODE, serverName = chosen.name) }
                                    P.REJ_BAD_MAC -> if (pairCode != null) HandshakeResult(HandshakeResult.Kind.WRONG_CODE, serverName = chosen.name)
                                                     else { prefs.clearPsk(chosen.name); HandshakeResult(HandshakeResult.Kind.NEED_CODE, serverName = chosen.name) }
                                    P.REJ_RATE_LIMITED -> { Thread.sleep(retryMs.toLong().coerceIn(200, 3000)); HandshakeResult(HandshakeResult.Kind.FAIL, detail = "Terlalu banyak percobaan") }
                                    P.REJ_PAIRING_DISABLED -> HandshakeResult(HandshakeResult.Kind.FAIL, detail = "Server menolak pairing baru")
                                    else -> HandshakeResult(HandshakeResult.Kind.FAIL, detail = "Ditolak ($reason)")
                                }
                            }
                        }
                    }
                }
                ch.close()
                return HandshakeResult(HandshakeResult.Kind.FAIL, detail = "Server tidak menjawab")
            } catch (e: Exception) {
                Log.e("handshake error", e)
                try { ch?.close() } catch (_: Exception) {}
                return HandshakeResult(HandshakeResult.Kind.FAIL, detail = e.message)
            }
        }

        /** Broadcast DISCOVER on every interface (WiFi, USB tethering, hotspot) and collect OFFERs for ~1.2 s. */
        private fun discover(ch: DatagramChannel, sel: Selector): List<Offer> {
            val targets = ArrayList<InetSocketAddress>()
            try {
                val ifs = NetworkInterface.getNetworkInterfaces()
                while (ifs != null && ifs.hasMoreElements()) {
                    val nif = ifs.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    for (ia in nif.interfaceAddresses) {
                        val b = ia.broadcast ?: continue
                        targets.add(InetSocketAddress(b, P.PORT))
                    }
                }
            } catch (e: Exception) {
                Log.w("interface enumeration failed: $e")
            }
            targets.add(InetSocketAddress(InetAddress.getByName("255.255.255.255"), P.PORT))
            if (manualIp.isNotEmpty()) {
                try { targets.add(InetSocketAddress(InetAddress.getByName(manualIp), P.PORT)) } catch (_: Exception) {}
            }
            val d = ByteArray(P.DISCOVER_LEN)
            P.writeHeader(d, P.T_DISCOVER, 0, 0)
            val offers = LinkedHashMap<String, Offer>()
            val buf = ByteBuffer.allocate(64)
            val start = SystemClock.uptimeMillis()
            var lastSend = 0L
            var sends = 0
            while (true) {
                val now = SystemClock.uptimeMillis()
                if (now - start > 1500 || (offers.isNotEmpty() && now - start > 700) || offers.keys.contains(preferred)) break
                if (sends < 3 && now - lastSend >= 400) {
                    for (t in targets) {
                        try { ch.send(ByteBuffer.wrap(d), t) } catch (_: Exception) {}
                    }
                    lastSend = now
                    sends++
                }
                if (sel.select(100) == 0) continue
                sel.selectedKeys().clear()
                while (true) {
                    buf.clear()
                    val from = ch.receive(buf) as? InetSocketAddress ?: break
                    val n = buf.position()
                    val arr = buf.array()
                    if (n != P.OFFER_LEN || !P.isMagic(arr, n) || P.headerType(arr) != P.T_OFFER) continue
                    if (P.rdU16(arr, 16) != P.VERSION) continue
                    val flags = P.rdU16(arr, 18)
                    var end = 20
                    while (end < 52 && arr[end].toInt() != 0) end++
                    val name = String(arr, 20, end - 20, Charsets.UTF_8)
                    if (!offers.containsKey(name)) offers[name] = Offer(from, arr.copyOfRange(8, 16), flags, name)
                }
            }
            return offers.values.toList()
        }
    }
}
