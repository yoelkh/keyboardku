package id.keyboardku.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StrictMode
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import id.keyboardku.Log
import id.keyboardku.Prefs
import id.keyboardku.input.AirMouseEngine
import id.keyboardku.input.HidKeymap
import id.keyboardku.input.InputSink
import id.keyboardku.input.KeyboardState
import id.keyboardku.input.Keys
import id.keyboardku.sensor.MotionSensorSource
import id.keyboardku.service.ConnectionService
import id.keyboardku.transport.BtHidTransport
import id.keyboardku.transport.Transport
import id.keyboardku.transport.TransportState
import id.keyboardku.transport.UdpTransport

class MainActivity : Activity(), Transport.Listener {
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    /** Forwards to the service's scheduler once bound; drops input before that. */
    private inner class ProxySink : InputSink {
        override fun move(dx: Float, dy: Float) { service?.scheduler?.move(dx, dy) }
        override fun scroll(v120: Float, h120: Float) { service?.scheduler?.scroll(v120, h120) }
        override fun buttons(mask: Int) { service?.scheduler?.buttons(mask) }
        override fun keyState(mods: Int, keys: ByteArray) { service?.scheduler?.keyState(mods, keys) }
        override fun text(cs: CharSequence) { service?.scheduler?.text(cs) }
        override fun consumer(usage: Int) { service?.scheduler?.consumer(usage) }
        override fun releaseAll() { service?.scheduler?.releaseAll() }
    }

    private val sink = ProxySink()
    private lateinit var keyboard: KeyboardState
    private lateinit var status: StatusBar
    private lateinit var trackpad: TrackpadView
    private lateinit var keysBar: SpecialKeysBar
    private lateinit var capture: KeyCaptureEditText
    private lateinit var airEngine: AirMouseEngine
    private lateinit var sensors: MotionSensorSource
    private lateinit var dimmer: IdleDimmer
    private var airMouseOn = false
    private var touchClutch = false

    private var service: ConnectionService? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as ConnectionService.LocalBinder).service
            service = s
            s.uiListener = this@MainActivity
            s.scheduler.onUnsentChanged = { status.setUnsent(s.scheduler.unsentChars) }
            s.manager.bt.pairingListener = btPairingListener
            val t = s.manager.current
            if (t != null) onStateChanged(t, t.state, null)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service?.uiListener = null
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // UDP sends happen on the main thread on purpose (no thread hand-off); a UDP send is one syscall.
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitNetwork().build())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        keyboard = KeyboardState(sink)
        airEngine = AirMouseEngine(sink)
        sensors = MotionSensorSource(this, airEngine)
        dimmer = IdleDimmer(this, main)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        status = StatusBar(this)
        trackpad = TrackpadView(this, sink)
        keysBar = SpecialKeysBar(this, keyboard, sink)
        capture = KeyCaptureEditText(this, keyboard, sink)
        val dp = resources.displayMetrics.density

        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(trackpad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(keysBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(makeMouseButton("Kiri", Keys.BTN_LEFT), LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f))
        buttons.addView(makeMouseButton("Kanan", Keys.BTN_RIGHT), LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f))
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(capture, LinearLayout.LayoutParams(1, 1))
        // Edge-to-edge (enforced on Android 15+): keep our own bars out of the status bar, display
        // cutout, gesture-navigation bar and the on-screen keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, maxOf(i.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)

        keyboard.listener = {
            keysBar.refreshMods()
            service?.scheduler?.externalMods = keyboard.effectiveMods
        }
        trackpad.engine.clutchListener = { active ->
            touchClutch = active
            // "Selalu aktif" must never be cancelled by a tap/lift on the pad
            airEngine.clutch = active || prefs.airAlwaysOn
            if (active) sensors.wake()
        }
        sensors.allowSleep = { !touchClutch }
        trackpad.onAnyTouch = { dimmer.activity() }
        dimmer.onDimmed = { trackpad.swallowNextGesture = true }
        dimmer.onWoken = { }

        status.onKeyboard = { toggleIme() }
        status.onAirMouse = { setAirMouse(!airMouseOn) }
        status.onAirMouseLong = { calibrateAirMouse() }
        airEngine.calibrationListener = { done, progress ->
            if (done) {
                prefs.airBiasX = airEngine.biasX; prefs.airBiasY = airEngine.biasY; prefs.airBiasZ = airEngine.biasZ
                trackpad.hintText = "Kalibrasi selesai"
                main.postDelayed({ if (airMouseOn) trackpad.hintText = "Air mouse aktif" }, 1500)
            } else trackpad.hintText = "Kalibrasi... $progress%"
        }
        status.onAction = { actionButton() }
        status.onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }

        if (!prefs.onboardingDone || prefs.onboardingOsVersion != Build.DISPLAY) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    private fun makeMouseButton(label: String, bit: Int): TextView {
        val dp = resources.displayMetrics.density
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(Color.WHITE)
        tv.gravity = Gravity.CENTER
        val normal = GradientDrawable().apply { cornerRadius = 10 * dp; setColor(0xFF161616.toInt()) }
        val pressed = GradientDrawable().apply { cornerRadius = 10 * dp; setColor(0xFF1E88E5.toInt()) }
        tv.background = normal
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.background = pressed; dimmer.activity(); trackpad.engine.physicalButton(bit, true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.background = normal; trackpad.engine.physicalButton(bit, false) }
            }
            true
        }
        return tv
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        if (ensurePermissions()) startAndBind()
    }

    override fun onResume() {
        super.onResume()
        applyPrefs()
        dimmer.activity()
        dimmer.setPocketMode(prefs.pocketMode)
        if (airMouseOn) sensors.start()
    }

    override fun onPause() {
        sink.releaseAll()
        keyboard.releaseAll()
        sensors.stop()
        dimmer.pause()
        super.onPause()
    }

    override fun onStop() {
        service?.let { it.uiListener = null; it.manager.bt.pairingListener = null }
        if (service != null) { unbindService(conn); service = null }
        super.onStop()
    }

    private fun startAndBind() {
        ConnectionService.start(this)
        bindService(Intent(this, ConnectionService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun applyPrefs() {
        val e = trackpad.engine
        e.pointerSpeed = prefs.pointerSpeed
        e.pointerAccel = prefs.pointerAccel
        e.scrollSpeed = prefs.scrollSpeed
        e.naturalScroll = prefs.naturalScroll
        e.tapToClick = prefs.tapToClick
        airEngine.degreesPerScreen = prefs.airDegreesPerScreen
        airEngine.tighteningDegPerSec = prefs.airSteadiness
        airEngine.smoothingDegPerSec = prefs.airSmoothing
        airEngine.accelMax = when (prefs.airAccel) { 0 -> 1.0f; 1 -> 1.3f; else -> 1.6f }
        airEngine.autoBias = prefs.airAutoBias
        airEngine.biasX = prefs.airBiasX; airEngine.biasY = prefs.airBiasY; airEngine.biasZ = prefs.airBiasZ
        airEngine.hostScreenWidthPx = if (prefs.hostScreenWidth > 0) prefs.hostScreenWidth.toFloat() else 1920f
        if (airMouseOn) {
            airEngine.clutch = prefs.airAlwaysOn || touchClutch
            trackpad.hintText = if (prefs.airAlwaysOn) "Air mouse: gerakkan HP" else "Air mouse: tahan jari di sini lalu gerakkan HP"
        }
        dimmer.dimAfterMs = prefs.dimAfterSeconds * 1000L
        if (prefs.airMouseEnabled != airMouseOn) setAirMouse(prefs.airMouseEnabled)
    }

    private fun setAirMouse(on: Boolean) {
        if (on && !sensors.available) {
            Toast.makeText(this, "HP ini tidak punya gyroscope", Toast.LENGTH_SHORT).show()
            return
        }
        airMouseOn = on
        prefs.airMouseEnabled = on
        trackpad.engine.airMouseMode = on
        status.setAirMouseActive(on)
        if (on) {
            sensors.start()
            airEngine.clutch = prefs.airAlwaysOn
            trackpad.hintText = if (prefs.airAlwaysOn) "Air mouse: gerakkan HP" else "Air mouse: tahan jari di sini lalu gerakkan HP"
        } else {
            sensors.stop()
            airEngine.clutch = false
            trackpad.hintText = ""
        }
    }

    private fun calibrateAirMouse() {
        if (!airMouseOn) setAirMouse(true)
        if (!airMouseOn) return
        sensors.wake()
        trackpad.hintText = "Diamkan HP di meja..."
        airEngine.startCalibration()
    }

    private fun toggleIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (capture.hasFocus() && imm.isAcceptingText) {
            imm.hideSoftInputFromWindow(capture.windowToken, 0)
            capture.clearFocus()
        } else {
            capture.requestFocus()
            imm.showSoftInput(capture, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ------------------------------------------------------------------ permissions

    private fun ensurePermissions(): Boolean {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            for (p in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p)
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) return true
        requestPermissions(need.toTypedArray(), 1)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            val btDenied = permissions.indices.any { permissions[it].startsWith("android.permission.BLUETOOTH") && grantResults[it] != PackageManager.PERMISSION_GRANTED }
            if (btDenied) {
                Toast.makeText(this, "Tanpa izin Bluetooth, hanya mode WiFi yang tersedia", Toast.LENGTH_LONG).show()
                if (prefs.transportMode == "AUTO") prefs.transportMode = "WIFI"
            }
            startAndBind()
        }
    }

    // ------------------------------------------------------------------ transport events (main thread)

    override fun onStateChanged(t: Transport, state: TransportState, detail: String?) {
        status.setStatus(t.name, ConnectionService.stateLabel(state), detail)
        status.setConnected(state == TransportState.CONNECTED)
        if (state != TransportState.CONNECTED) status.setRtt(-1f)
        status.setActionLabel(
            when {
                state == TransportState.CONNECTED -> "Ganti"
                t is BtHidTransport -> "Pair"
                else -> "Cari"
            }
        )
        if (t is UdpTransport && state == TransportState.CONNECTED && t.hostScreenWidth > 0) {
            airEngine.hostScreenWidthPx = t.hostScreenWidth.toFloat()
        }
    }

    override fun onRtt(t: Transport, rttMs: Float) { status.setRtt(rttMs) }

    override fun onPairingCodeNeeded(t: Transport, serverName: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6 digit"
        }
        AlertDialog.Builder(this)
            .setTitle("Kode pairing untuk $serverName")
            .setMessage("Ketik kode 6 digit yang ditampilkan server KeyboardKu di laptop.")
            .setView(input)
            .setPositiveButton("Sambung") { _, _ ->
                val code = input.text.toString().trim()
                if (code.length == 6 && code.all { it.isDigit() }) (t as? UdpTransport)?.pairWithCode(code)
                else Toast.makeText(this, "Kode harus 6 digit", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    override fun onServerChoice(t: Transport, names: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("Pilih laptop")
            .setItems(names.toTypedArray()) { _, i ->
                prefs.preferredServerName = names[i]
                (t as? UdpTransport)?.retry()
            }
            .show()
    }

    // ------------------------------------------------------------------ action button

    private fun actionButton() {
        val s = service ?: return
        val t = s.manager.current ?: return
        if (t.state == TransportState.CONNECTED) {
            val items = arrayOf("Pakai Bluetooth HID", "Pakai WiFi", "Otomatis", "Putuskan")
            AlertDialog.Builder(this).setTitle("Koneksi").setItems(items) { _, i ->
                when (i) {
                    0 -> s.manager.switchTo("BT")
                    1 -> s.manager.switchTo("WIFI")
                    2 -> s.manager.switchTo("AUTO")
                    3 -> { s.stopConnection(); stopService(Intent(this, ConnectionService::class.java)); finish() }
                }
            }.show()
            return
        }
        when (t) {
            is BtHidTransport -> btPairMenu(t)
            is UdpTransport -> {
                val items = arrayOf("Cari lagi", "Pairing ulang (lupakan kunci)", "Pakai Bluetooth HID")
                AlertDialog.Builder(this).setTitle("WiFi").setItems(items) { _, i ->
                    when (i) {
                        0 -> t.retry()
                        1 -> { t.serverName?.let { prefs.clearPsk(it) }; prefs.preferredServerName?.let { prefs.clearPsk(it) }; t.retry() }
                        2 -> s.manager.switchTo("BT")
                    }
                }.show()
            }
        }
    }

    private fun btPairMenu(bt: BtHidTransport) {
        val items = arrayOf("Cari & sandingkan laptop (dari HP)", "Jadikan HP terlihat 120 s (pairing dari Windows)", "Sambung ulang", "Pakai WiFi saja", "Coba lagi deteksi HID")
        AlertDialog.Builder(this).setTitle("Bluetooth HID").setItems(items) { _, i ->
            when (i) {
                0 -> showBtScanDialog(bt)
                1 -> {
                    try {
                        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                    } catch (e: Exception) { Toast.makeText(this, "Gagal: $e", Toast.LENGTH_SHORT).show() }
                }
                2 -> bt.connectNow()
                3 -> service?.manager?.switchTo("WIFI")
                4 -> service?.manager?.retry()
            }
        }.show()
    }

    private val btDevices = ArrayList<Pair<String, String>>()
    private var btAdapter: ArrayAdapter<String>? = null

    private val btPairingListener = object : BtHidTransport.PairingListener {
        override fun onDeviceFound(name: String, address: String, bonded: Boolean) {
            if (btDevices.any { it.second == address }) return
            btDevices.add(name to address)
            btAdapter?.add(if (bonded) "$name  (sudah tersanding)" else name)
            btAdapter?.notifyDataSetChanged()
        }
        override fun onScanFinished() {}
    }

    private fun showBtScanDialog(bt: BtHidTransport) {
        btDevices.clear()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        btAdapter = adapter
        val dlg = AlertDialog.Builder(this)
            .setTitle("Pilih laptop (Bluetooth harus aktif di laptop)")
            .setAdapter(adapter) { _, i -> bt.selectDevice(btDevices[i].second) }
            .setNegativeButton("Batal", null)
            .setOnDismissListener { bt.stopPairingScan(); btAdapter = null }
            .create()
        dlg.show()
        bt.startPairingScan()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) dimmer.activity()
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------ hardware keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val consumer = HidKeymap.consumerForKeyCode(event.keyCode)
        if (consumer != 0 && service?.manager?.current?.state == TransportState.CONNECTED) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) sink.consumer(consumer)
                KeyEvent.ACTION_UP -> sink.consumer(0)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("unused")
    private fun log(msg: String) = Log.d(msg)
}
