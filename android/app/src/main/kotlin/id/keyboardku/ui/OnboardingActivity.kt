package id.keyboardku.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import id.keyboardku.Prefs

/**
 * One-time checklist for OEM battery killers (Xiaomi/HyperOS, ColorOS, Vivo, Realme). Shown again
 * after an OS update because MIUI resets these switches. See docs/OEM.md.
 */
class OnboardingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val dp = resources.displayMetrics.density
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (32 * dp).toInt())
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

        fun text(s: String, size: Float = 14f, color: Int = Color.WHITE) {
            col.addView(TextView(this).apply { text = s; textSize = size; setTextColor(color); setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) })
        }
        fun button(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply { text = label; setOnClickListener { onClick() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val brand = Build.MANUFACTURER.lowercase()
        text("Agar koneksi tidak diputus oleh sistem", 20f, 0xFF64B5F6.toInt())
        text("HP ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DISPLAY}) membatasi aplikasi latar secara agresif. KeyboardKu berjalan sebagai layanan foreground, tetapi ROM ini tetap bisa mematikannya beberapa detik setelah layar mati kecuali pengaturan berikut diubah:")

        text("1. Izinkan aplikasi berjalan otomatis / Autostart", 15f)
        button("Buka pengaturan Autostart") { openAutostart(brand) }

        text("2. Baterai: pilih \"Tanpa batasan\" / \"No restrictions\" untuk KeyboardKu", 15f)
        button("Buka info aplikasi (Baterai)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            button("Kecualikan dari optimasi baterai Android") {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }

        text("3. Di layar Recent Apps, kunci (gembok) KeyboardKu agar tidak dibersihkan.", 15f)
        if (brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) {
            text("HyperOS 2: Pengaturan > Aplikasi > Kelola aplikasi > KeyboardKu > Penghemat baterai > Tanpa batasan; dan matikan \"Batasi aktivitas latar\".", 13f, 0xFF9E9E9E.toInt())
        }
        if (brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus")) {
            text("ColorOS/Realme UI: Pengaturan > Baterai > Lainnya > Izinkan aktivitas latar; dan Manajer Startup.", 13f, 0xFF9E9E9E.toInt())
        }
        if (brand.contains("vivo") || brand.contains("iqoo")) {
            text("Vivo: i Manager > Manajer aplikasi > Autostart; Baterai > Konsumsi daya latar tinggi > izinkan.", 13f, 0xFF9E9E9E.toInt())
        }

        text("4. Bluetooth HID: bila koneksi gagal, sandingkan dari HP (menu Pair) bukan dari Windows. Untuk WiFi, jalankan keyboardku-server di laptop dan izinkan Windows Firewall (jaringan Privat).", 13f, 0xFF9E9E9E.toInt())

        button("Selesai") {
            prefs.onboardingDone = true
            prefs.onboardingOsVersion = Build.DISPLAY
            finish()
        }
    }

    private fun openAutostart(brand: String) {
        val candidates = ArrayList<Intent>()
        when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> {
                candidates.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            }
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> {
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")))
            }
            brand.contains("vivo") || brand.contains("iqoo") -> {
                candidates.add(Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")))
            }
        }
        candidates.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        for (i in candidates) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
        Toast.makeText(this, "Buka pengaturan Autostart secara manual", Toast.LENGTH_LONG).show()
    }
}
