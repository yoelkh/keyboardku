# Checklist ROM Xiaomi / Oppo / Vivo / Realme

ROM ini mematikan layanan latar beberapa detik setelah layar padam, bahkan foreground service, kecuali app dikecualikan. KeyboardKu menampilkan checklist ini saat pertama dibuka dan lagi setiap ada update OS (MIUI/HyperOS mengembalikan setelan ke default setelah update).

## Xiaomi / Redmi / POCO (MIUI 13–14, HyperOS 1–2)
1. **Security → Permissions → Autostart** → aktifkan KeyboardKu.
   (deep link: `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`)
2. **Pengaturan → Aplikasi → Kelola aplikasi → KeyboardKu → Penghemat baterai → Tanpa batasan (No restrictions)**.
3. HyperOS 2: di halaman yang sama matikan **"Batasi aktivitas latar" / "Pause app activity when unused"**.
4. Recent apps → tekan lama kartu KeyboardKu → **kunci (gembok)**.
5. Untuk `adb install`: Developer options → **USB debugging** dan **Install via USB** (mungkin minta login akun Mi; alternatif: salin APK ke Download lalu instal dari Files).
6. Bluetooth HID: pairing **dari HP** (menu Pair di app). Beberapa build HyperOS gagal "Incorrect PIN or passkey" bila pairing dimulai dari PC.

## Oppo / Realme / OnePlus (ColorOS, Realme UI)
1. **Pengaturan → Baterai → Lainnya → izinkan aktivitas latar** untuk KeyboardKu (atau "Battery usage → Allow background activity").
2. **Manajer Startup / Startup manager** → izinkan.
3. Kunci app di Recent apps.
Catatan: OnePlus era Android 9 menghilangkan profil HID Device dari ROM; app akan otomatis memakai WiFi.

## Vivo / iQOO (Funtouch / OriginOS)
1. **i Manager → App manager → Autostart** → izinkan KeyboardKu.
2. **Baterai → Konsumsi daya latar tinggi** → izinkan.
3. Kunci app di Recent apps.

## Semua merek
- Izinkan **notifikasi** (Android 13+) agar layanan foreground terlihat dan tidak dibunuh.
- "Kecualikan dari optimasi baterai" (tombol di checklist app) menambah perlindungan Doze standar Android.
- Jika koneksi Bluetooth putus saat layar padam padahal semua sudah diatur, coba matikan "Optimasi MIUI" (Developer options) atau pakai jalur WiFi/USB.

Referensi: dontkillmyapp.com (Xiaomi, Oppo, Realme, Vivo).
