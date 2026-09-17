package id.keyboardku.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import id.keyboardku.Log
import id.keyboardku.Prefs
import java.util.concurrent.Executors

/**
 * Bluetooth Classic HID Device transport: the phone *is* a keyboard + mouse to the laptop.
 *
 * Order matters: the foreground service must already be running before
 * registerApp; the phone initiates pairing/connection (HIDReconnectInitiate = true, and MIUI/HyperOS
 * mis-handles PC-initiated pairing). Reports are throttled to the 6 ms sniff anchor by the scheduler,
 * and a failed sendReport (congested) simply lets the delta accumulate.
 */
@SuppressLint("MissingPermission")
class BtHidTransport(context: Context, private val prefs: Prefs, private val main: Handler) : Transport {
    override val name = "Bluetooth"
    override val minMoveIntervalNanos = 6_000_000L
    override val supportsUnicode = false
    override var listener: Transport.Listener? = null
    override var state: TransportState = TransportState.IDLE
        private set

    interface PairingListener {
        fun onDeviceFound(name: String, address: String, bonded: Boolean)
        fun onScanFinished()
    }
    var pairingListener: PairingListener? = null

    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "kbku-hid") }

    private var started = false
    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private var device: BluetoothDevice? = null
    @Volatile private var bootProtocol = false
    private var connectAttempt = 0
    private var receiverRegistered = false

    // reports (report ID is prepended by the stack)
    private val kbd = ByteArray(HidDescriptor.KEYBOARD_LEN)
    private val mouse = ByteArray(HidDescriptor.MOUSE_LEN)
    private val mouseBoot = ByteArray(HidDescriptor.MOUSE_BOOT_LEN)
    private val consumer = ByteArray(HidDescriptor.CONSUMER_LEN)
    private val zeroKbd = ByteArray(HidDescriptor.KEYBOARD_LEN)
    private val zeroMouse = ByteArray(HidDescriptor.MOUSE_LEN)
    private val zeroConsumer = ByteArray(HidDescriptor.CONSUMER_LEN)
    private var buttons = 0
    private var carryV = 0
    private var carryH = 0
    private var kbdDirty = false
    private var kbdRetries = 0

    private fun setState(s: TransportState, detail: String? = null) {
        state = s
        listener?.onStateChanged(this, s, detail)
    }

    private fun unsupported(reason: String) {
        Log.w("HID unsupported: $reason")
        prefs.hidUnsupported = true
        setState(TransportState.UNSUPPORTED, reason)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun start() {
        if (started) return
        started = true
        val a = adapter
        if (a == null) { setState(TransportState.UNSUPPORTED, "Perangkat tidak punya Bluetooth"); return }
        if (!a.isEnabled) { setState(TransportState.ERROR, "Nyalakan Bluetooth"); return }
        setState(TransportState.PROBING, "Memeriksa profil HID...")
        registerReceiver()
        val ok = try {
            a.getProfileProxy(app, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) { false }
        if (!ok) { unsupported("getProfileProxy(HID_DEVICE) gagal"); return }
        main.postDelayed(probeTimeout, 4000)
    }

    private val probeTimeout = Runnable {
        if (started && state == TransportState.PROBING) unsupported("Profil HID Device tidak tersedia di ROM ini")
    }

    override fun stop() {
        started = false
        main.removeCallbacks(probeTimeout)
        main.removeCallbacks(connectRunnable)
        main.removeCallbacks(kbdRetryRunnable)
        try {
            if (registered) releaseAll()
            hid?.unregisterApp()
        } catch (_: Exception) {}
        registered = false
        device = null
        try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) } catch (_: Exception) {}
        hid = null
        unregisterReceiver()
        setState(TransportState.IDLE)
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            main.post {
                if (!started) return@post
                hid = proxy as BluetoothHidDevice
                registerApp()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            main.post {
                registered = false
                hid = null
                device = null
                if (started) setState(TransportState.ERROR, "Layanan Bluetooth terputus")
            }
        }
    }

    private fun registerApp() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "KeyboardKu", "Keyboard + trackpad", "KeyboardKu",
            BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.DESCRIPTOR,
        )
        // Inert on the wire in AOSP (never sent to L2CAP) but hosts expect *something*: WearMouse values.
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX,
        )
        val ok = try { h.registerApp(sdp, null, qos, executor, callback) } catch (e: Exception) { Log.e("registerApp threw", e); false }
        if (!ok) { unsupported("registerApp ditolak (ROM memblokir HID Device, atau app lain sedang memakainya)"); return }
        main.removeCallbacks(probeTimeout)
        main.postDelayed(probeTimeout, 4000)
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, reg: Boolean) {
            main.post {
                main.removeCallbacks(probeTimeout)
                registered = reg
                Log.i("HID app registered=$reg")
                if (reg) {
                    prefs.hidUnsupported = false
                    if (!started) return@post
                    connectAttempt = 0
                    if (prefs.btDeviceAddress != null) connectNow() else setState(TransportState.DISCOVERABLE, "Pilih laptop untuk pairing")
                } else if (started) {
                    setState(TransportState.RECONNECTING, "Registrasi HID hilang, mendaftar ulang...")
                    main.postDelayed({ if (started) registerApp() }, 1000)
                }
            }
        }

        override fun onConnectionStateChanged(dev: BluetoothDevice, st: Int) {
            main.post {
                when (st) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        device = dev
                        prefs.btDeviceAddress = dev.address
                        connectAttempt = 0
                        main.removeCallbacks(connectRunnable)
                        setState(TransportState.CONNECTED, safeName(dev))
                        releaseAll()
                    }
                    BluetoothProfile.STATE_CONNECTING -> setState(TransportState.CONNECTING, safeName(dev))
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (device == dev) device = null
                        if (started && registered) {
                            setState(TransportState.RECONNECTING, "Terputus, mencoba lagi...")
                            scheduleConnect()
                        }
                    }
                }
            }
        }

        override fun onGetReport(dev: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val h = hid ?: return
            val data = when (id.toInt()) {
                HidDescriptor.ID_KEYBOARD, 0 -> kbd
                HidDescriptor.ID_MOUSE -> if (bootProtocol) mouseBoot else mouse
                HidDescriptor.ID_CONSUMER -> consumer
                else -> null
            }
            if (data != null) h.replyReport(dev, type, id, data) else h.reportError(dev, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
        }

        override fun onSetReport(dev: BluetoothDevice, type: Byte, id: Byte, data: ByteArray?) {
            hid?.reportError(dev, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onSetProtocol(dev: BluetoothDevice, protocol: Byte) {
            bootProtocol = protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE
            Log.i("host set protocol boot=$bootProtocol")
        }

        override fun onInterruptData(dev: BluetoothDevice, reportId: Byte, data: ByteArray?) {
            // keyboard LEDs (Caps/Num lock); nothing to do
        }

        override fun onVirtualCableUnplug(dev: BluetoothDevice) {
            main.post {
                device = null
                prefs.btDeviceAddress = null
                if (started) setState(TransportState.DISCOVERABLE, "Laptop melepas koneksi (unplug)")
            }
        }
    }

    // ------------------------------------------------------------------ connection / pairing

    private val connectRunnable = Runnable { connectNow() }

    private fun scheduleConnect() {
        main.removeCallbacks(connectRunnable)
        val delay = when (connectAttempt) { 0 -> 1000L; 1 -> 2000L; 2 -> 5000L; else -> 10000L }
        connectAttempt++
        main.postDelayed(connectRunnable, delay)
    }

    fun connectNow() {
        val h = hid ?: return
        val addr = prefs.btDeviceAddress ?: return
        if (!started || !registered) return
        val dev = try { adapter?.getRemoteDevice(addr) } catch (_: Exception) { null } ?: return
        if (device != null) return
        setState(TransportState.CONNECTING, safeName(dev))
        val ok = try { h.connect(dev) } catch (e: Exception) { false }
        if (!ok) scheduleConnect()
        else main.postDelayed({ if (started && device == null) scheduleConnect() }, 8000)
    }

    /** Phone-initiated pairing: scan, then [selectDevice]. */
    fun startPairingScan() {
        val a = adapter ?: return
        registerReceiver()
        try {
            for (d in a.bondedDevices) pairingListener?.onDeviceFound(safeName(d), d.address, true)
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        } catch (e: Exception) {
            Log.w("scan failed: $e")
            pairingListener?.onScanFinished()
        }
    }

    fun stopPairingScan() {
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
    }

    fun selectDevice(address: String) {
        val a = adapter ?: return
        try { a.cancelDiscovery() } catch (_: Exception) {}
        prefs.btDeviceAddress = address
        val dev = try { a.getRemoteDevice(address) } catch (_: Exception) { null } ?: return
        if (dev.bondState == BluetoothDevice.BOND_BONDED) {
            connectAttempt = 0
            connectNow()
        } else {
            setState(TransportState.PAIRING, "Menyandingkan dengan ${safeName(dev)}...")
            try { dev.createBond() } catch (e: Exception) { setState(TransportState.ERROR, "createBond gagal: ${e.message}") }
        }
    }

    fun forgetDevice() {
        prefs.btDeviceAddress = null
        device?.let { try { hid?.disconnect(it) } catch (_: Exception) {} }
        device = null
    }

    val isRegistered: Boolean get() = registered
    val connectedDeviceName: String? get() = device?.let { safeName(it) }

    private fun safeName(d: BluetoothDevice): String = try { d.name ?: d.address } catch (_: Exception) { d.address }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val n = try { d.name } catch (_: Exception) { null } ?: return
                    pairingListener?.onDeviceFound(n, d.address, d.bondState == BluetoothDevice.BOND_BONDED)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> pairingListener?.onScanFinished()
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    if (d.address == prefs.btDeviceAddress) {
                        if (st == BluetoothDevice.BOND_BONDED) { connectAttempt = 0; main.postDelayed({ connectNow() }, 500) }
                        else if (st == BluetoothDevice.BOND_NONE && state == TransportState.PAIRING) setState(TransportState.DISCOVERABLE, "Pairing dibatalkan")
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (st == BluetoothAdapter.STATE_OFF && started) setState(TransportState.ERROR, "Bluetooth dimatikan")
                }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, f)
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try { app.unregisterReceiver(receiver) } catch (_: Exception) {}
        receiverRegistered = false
    }

    // ------------------------------------------------------------------ reports (main thread)

    private fun send(id: Int, data: ByteArray): Boolean {
        val h = hid ?: return false
        val d = device ?: return false
        return try { h.sendReport(d, id, data) } catch (e: Exception) { false }
    }

    override fun sendMouse(dx: Int, dy: Int): Boolean {
        if (device == null) return false
        if (bootProtocol) {
            mouseBoot[0] = buttons.toByte()
            mouseBoot[1] = dx.coerceIn(-127, 127).toByte()
            mouseBoot[2] = dy.coerceIn(-127, 127).toByte()
            return send(HidDescriptor.ID_MOUSE, mouseBoot)
        }
        val x = dx.coerceIn(-32767, 32767)
        val y = dy.coerceIn(-32767, 32767)
        mouse[0] = buttons.toByte()
        mouse[1] = (x and 0xFF).toByte(); mouse[2] = ((x shr 8) and 0xFF).toByte()
        mouse[3] = (y and 0xFF).toByte(); mouse[4] = ((y shr 8) and 0xFF).toByte()
        mouse[5] = 0; mouse[6] = 0
        return send(HidDescriptor.ID_MOUSE, mouse)
    }

    override fun sendScroll(v120: Int, h120: Int): Boolean {
        if (device == null) return false
        carryV += v120
        carryH += h120
        val nv = (carryV / 120).coerceIn(-127, 127)
        val nh = (carryH / 120).coerceIn(-127, 127)
        if (nv == 0 && nh == 0) return true
        mouse[0] = buttons.toByte()
        mouse[1] = 0; mouse[2] = 0; mouse[3] = 0; mouse[4] = 0
        mouse[5] = nv.toByte()
        mouse[6] = nh.toByte()
        val ok = if (bootProtocol) true else send(HidDescriptor.ID_MOUSE, mouse)
        if (ok) { carryV -= nv * 120; carryH -= nh * 120 }
        mouse[5] = 0; mouse[6] = 0
        return ok
    }

    override fun sendButtons(mask: Int) {
        buttons = mask and 0x07
        if (device == null) return
        if (bootProtocol) {
            mouseBoot[0] = buttons.toByte(); mouseBoot[1] = 0; mouseBoot[2] = 0
            send(HidDescriptor.ID_MOUSE, mouseBoot)
        } else {
            mouse[0] = buttons.toByte()
            for (i in 1 until 7) mouse[i] = 0
            send(HidDescriptor.ID_MOUSE, mouse)
        }
    }

    private val kbdRetryRunnable = Runnable {
        if (kbdDirty && device != null) {
            if (send(HidDescriptor.ID_KEYBOARD, kbd)) { kbdDirty = false; kbdRetries = 0 }
            else if (++kbdRetries < 10) main.postDelayed(kbdRetryRunnableRef, 5)
        }
    }
    private val kbdRetryRunnableRef: Runnable get() = kbdRetryRunnable

    override fun sendKeys(mods: Int, keys: ByteArray) {
        kbd[0] = mods.toByte()
        kbd[1] = 0
        System.arraycopy(keys, 0, kbd, 2, 6)
        if (device == null) return
        if (!send(HidDescriptor.ID_KEYBOARD, kbd)) {
            // congested: a key state must not be lost -> retry shortly
            kbdDirty = true
            kbdRetries = 0
            main.removeCallbacks(kbdRetryRunnable)
            main.postDelayed(kbdRetryRunnable, 5)
        } else kbdDirty = false
    }

    override fun sendText(cs: CharSequence, start: Int, end: Int): Int = end - start

    override fun sendConsumer(usage: Int) {
        consumer[0] = (usage and 0xFF).toByte()
        consumer[1] = ((usage shr 8) and 0xFF).toByte()
        if (device == null) return
        send(HidDescriptor.ID_CONSUMER, consumer)
    }

    override fun releaseAll() {
        buttons = 0
        kbd.fill(0)
        consumer.fill(0)
        if (device == null) return
        send(HidDescriptor.ID_KEYBOARD, zeroKbd)
        send(HidDescriptor.ID_MOUSE, if (bootProtocol) ByteArray(3) else zeroMouse)
        send(HidDescriptor.ID_CONSUMER, zeroConsumer)
    }

    override fun setActive(active: Boolean) { /* nothing to scope on Bluetooth */ }
}
