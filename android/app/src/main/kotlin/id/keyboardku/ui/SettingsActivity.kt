package id.keyboardku.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import id.keyboardku.Prefs

/** Plain-widget settings screen bound directly to [Prefs]. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private val dp: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (32 * dp).toInt())
        }
        scroll.addView(col)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            }
            insets
        }
        setContentView(scroll)
        build()
    }

    private fun header(text: String) {
        col.addView(TextView(this).apply {
            this.text = text
            textSize = 17f
            setTextColor(0xFF64B5F6.toInt())
            setPadding(0, (18 * dp).toInt(), 0, (6 * dp).toInt())
        })
    }

    private fun note(text: String) {
        col.addView(TextView(this).apply { this.text = text; textSize = 12f; setTextColor(0xFF9E9E9E.toInt()) })
    }

    private fun seek(label: String, min: Float, max: Float, value: Float, fmt: (Float) -> String, onChange: (Float) -> Unit) {
        val tv = TextView(this).apply { setTextColor(Color.WHITE); text = "$label: ${fmt(value)}" }
        val sb = SeekBar(this)
        sb.max = 1000
        sb.progress = (((value - min) / (max - min)) * 1000).toInt().coerceIn(0, 1000)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val v = min + (max - min) * p / 1000f
                tv.text = "$label: ${fmt(v)}"
                if (fromUser) onChange(v)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        col.addView(tv)
        col.addView(sb)
    }

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        @Suppress("UseSwitchCompatOrMaterialCode")
        val sw = Switch(this).apply { text = label; isChecked = value; setTextColor(Color.WHITE); setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        col.addView(sw)
    }

    private fun radios(options: List<Pair<String, String>>, current: String, onChange: (String) -> Unit) {
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((i, o) in options.withIndex()) {
            val rb = RadioButton(this).apply { text = o.first; setTextColor(Color.WHITE); id = 100 + i; isChecked = o.second == current }
            rg.addView(rb)
        }
        rg.setOnCheckedChangeListener { _, id -> onChange(options[id - 100].second) }
        col.addView(rg)
    }

    private fun button(label: String, onClick: () -> Unit) {
        col.addView(Button(this).apply { text = label; setOnClickListener { onClick() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * dp).toInt() })
    }

    private fun build() {
        header("Koneksi")
        radios(listOf("Otomatis" to "AUTO", "Bluetooth HID" to "BT", "WiFi" to "WIFI"), prefs.transportMode) { prefs.transportMode = it }
        note("Otomatis = Bluetooth HID bila ROM mendukung, kalau tidak WiFi. Perubahan berlaku saat menyambung ulang.")
        val ip = EditText(this).apply {
            hint = "IP laptop (opsional, untuk WiFi tanpa broadcast)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.manualServerIp)
            setTextColor(Color.WHITE)
        }
        col.addView(ip)
        button("Simpan IP") { prefs.manualServerIp = ip.text.toString().trim(); Toast.makeText(this, "Disimpan", Toast.LENGTH_SHORT).show() }
        button("Lupakan laptop Bluetooth") { prefs.btDeviceAddress = null; Toast.makeText(this, "Dilupakan", Toast.LENGTH_SHORT).show() }
        button("Lupakan pairing WiFi") {
            prefs.preferredServerName?.let { prefs.clearPsk(it) }
            prefs.preferredServerName = null
            Toast.makeText(this, "Dilupakan; kode akan diminta lagi", Toast.LENGTH_SHORT).show()
        }
        button("Reset deteksi HID (coba Bluetooth lagi)") { prefs.hidUnsupported = false; Toast.makeText(this, "OK", Toast.LENGTH_SHORT).show() }

        header("Trackpad")
        seek("Kecepatan pointer", 0.5f, 3.0f, prefs.pointerSpeed, { "%.2f".format(it) }) { prefs.pointerSpeed = it }
        seek("Akselerasi", 0f, 1f, prefs.pointerAccel, { "%.0f%%".format(it * 100) }) { prefs.pointerAccel = it }
        seek("Kecepatan scroll", 0.3f, 3.0f, prefs.scrollSpeed, { "%.2f".format(it) }) { prefs.scrollSpeed = it }
        switch("Natural scroll", prefs.naturalScroll) { prefs.naturalScroll = it }
        switch("Tap untuk klik", prefs.tapToClick) { prefs.tapToClick = it }

        header("Air mouse (gyro)")
        switch("Aktifkan air mouse", prefs.airMouseEnabled) { prefs.airMouseEnabled = it }
        seek("Derajat putaran per lebar layar", 20f, 90f, prefs.airDegreesPerScreen, { "%.0f°".format(it) }) { prefs.airDegreesPerScreen = it }
        seek("Steadiness (tightening)", 0f, 10f, prefs.airSteadiness, { "%.1f °/s".format(it) }) { prefs.airSteadiness = it }
        seek("Smoothing", 0f, 10f, prefs.airSmoothing, { if (it < 0.1f) "off" else "%.1f °/s".format(it) }) { prefs.airSmoothing = it }
        radios(listOf("Akselerasi off" to "0", "rendah" to "1", "standar" to "2"), prefs.airAccel.toString()) { prefs.airAccel = it.toInt() }
        switch("Selalu aktif (tanpa menahan jari)", prefs.airAlwaysOn) { prefs.airAlwaysOn = it }
        switch("Kalibrasi otomatis saat diam", prefs.airAutoBias) { prefs.airAutoBias = it }
        button("Hapus kalibrasi manual") {
            prefs.airBiasX = 0f; prefs.airBiasY = 0f; prefs.airBiasZ = 0f
            Toast.makeText(this, "Kalibrasi dihapus. Untuk kalibrasi ulang: tekan lama tombol air-mouse di layar utama.", Toast.LENGTH_LONG).show()
        }
        note("Tahan jari di trackpad untuk menggerakkan kursor dengan memutar HP (clutch). Tap tetap klik, dua jari tetap scroll.")

        header("Daya")
        seek("Redupkan layar setelah (detik, 0 = tidak)", 0f, 120f, prefs.dimAfterSeconds.toFloat(), { "%.0f s".format(it) }) { prefs.dimAfterSeconds = it.toInt() }
        switch("Mode saku (matikan layar saat sensor jarak tertutup)", prefs.pocketMode) { prefs.pocketMode = it }

        header("Perangkat")
        button("Checklist Xiaomi/Oppo/Vivo (autostart, baterai)") { startActivity(Intent(this, OnboardingActivity::class.java)) }
    }
}
