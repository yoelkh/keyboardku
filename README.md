# KeyboardKu

Ubah HP Android menjadi **keyboard + trackpad + air-mouse nirkabel** untuk laptop Windows 11, dengan fokus pada latensi minimum, hemat daya, dan ketahanan koneksi.

Dua jalur koneksi:

| Jalur | Cara kerja | Perlu apa di laptop | Latensi tipikal |
|---|---|---|---|
| **Bluetooth HID** (utama) | HP terdaftar sebagai keyboard + mouse Bluetooth asli (`BluetoothHidDevice`) | Tidak ada, cukup Bluetooth | 15–25 ms (dibatasi sniff mode stack Android, 6–11 ms) |
| **WiFi / USB tethering / hotspot** (fallback) | Paket UDP terenkripsi (ChaCha20-Poly1305) ke server Rust kecil yang memanggil `SendInput` | `keyboardku-server.exe` | WiFi 8–15 ms, USB tethering < 5 ms |

Fallback WiFi diperlukan karena banyak ROM Xiaomi/Oppo/Vivo/Realme mematikan profil HID Device. App mendeteksinya otomatis.

## Struktur

```
android/   app Android (Kotlin murni, tanpa AndroidX; minSdk 28)
server/    server Windows (Rust, windows-sys, single-thread, tanpa alokasi di hot path)
tools/     skrip PowerShell: env, build, install, logcat, laptop-tune
docs/      PROTOCOL.md (format paket), HID.md (descriptor & pairing), LATENCY.md, OEM.md
```

## Membangun

Prasyarat di laptop: **Android Studio** (JBR + SDK-nya dipakai; tidak perlu JDK terpisah) dan **Rust** (MSVC toolchain).

```powershell
# sekali: verifikasi toolchain + buat Gradle wrapper bila belum ada
.\tools\setup-android-sdk.ps1

# APK debug (+ unit test dengan -Test)
.\tools\build-apk.ps1 -Test

# APK release bertanda tangan -> dist\KeyboardKu-0.1.0.apk
# kirim file itu ke HP lewat cara apa pun (WhatsApp, Drive, kabel) lalu buka untuk instal
.\tools\build-apk.ps1 -Release

# alternatif: instal lewat adb (USB debugging aktif; MIUI: juga "Install via USB")
.\tools\install-apk.ps1

# server Windows
.\tools\run-server.ps1              # cetak kode pairing + status pemeriksaan laptop
.\tools\run-server.ps1 --verbose    # log setiap paket
```

Proyek `android/` juga bisa dibuka langsung di Android Studio.

## Pemakaian

### Bluetooth HID
1. Buka app, beri izin Bluetooth & notifikasi. Status bar menampilkan `Bluetooth · siap pairing` bila ROM mendukung HID Device.
2. Tekan **Pair → "Cari & sandingkan laptop (dari HP)"**, pilih laptop, setujui passkey di kedua sisi. (Pairing yang dimulai dari HP menghindari bug "Incorrect PIN" HyperOS.)
3. Windows menampilkan *HID Keyboard Device* dan *HID-compliant mouse* di Device Manager. Selesai; app menyambung ulang otomatis setelah laptop tidur.

Jika status menjadi `tidak didukung`, ROM memblokir profil HID Device; app otomatis pindah ke WiFi.

### WiFi / USB
1. Jalankan `keyboardku-server.exe` di laptop, izinkan Windows Firewall (jaringan **Privat**). Jalankan `tools\laptop-tune.ps1` sebagai Administrator sekali untuk menambah rule firewall dan mematikan power-saving adapter WiFi (sumber jitter 50–100 ms).
2. Di app, tekan **Cari**. Server ditemukan lewat broadcast di semua interface (WiFi, USB tethering `rndis0/ncm0`, hotspot). Masukkan **kode 6 digit** yang dicetak server saat pertama kali; setelah itu kunci pairing tersimpan.
3. Untuk latensi paling rendah: nyalakan **USB tethering** di HP, sambungkan kabel; app memilihnya otomatis.

### Trackpad & keyboard
- 1 jari = gerak; tap = klik kiri; tap lalu sentuh-geser = drag; 2 jari = scroll; 2 jari tap = klik kanan; 3 jari tap = klik tengah. Tombol Kiri/Kanan di bawah untuk drag dengan dua tangan.
- Tombol ⌨ membuka keyboard sistem (Gboard dsb.). Teks ASCII dikirim sebagai ketukan tombol (shortcut Ctrl+C dsb. berfungsi), karakter lain (é, emoji) sebagai Unicode di jalur WiFi; di Bluetooth HID karakter non-ASCII tidak bisa diketik dan dihitung di status bar.
- Bar tombol khusus: Esc, Tab, Ctrl/Alt/Shift/Win (tap = sekali pakai, tekan lama = kunci), panah, Del, Home/End, PgUp/PgDn, F1–F12, media. Menahan tombol = auto-repeat di laptop.

### Air-mouse (gyro)
Tekan ↗. **Tahan jari di area trackpad lalu gerakkan/putar HP**: kursor mengikuti (clutch). Tap tetap klik, dua jari tetap scroll. Tekan lama ↗ untuk kalibrasi (diamkan HP 1 detik). Setelan: derajat per lebar layar, steadiness, smoothing, akselerasi, "selalu aktif". Sensor dimatikan otomatis setelah 30 s diam.

## Signing rilis
Salin `android/keystore.properties.example` ke `android/keystore.properties`, buat keystore sendiri (`keytool -genkeypair ... -keystore android/keystore/keyboardku.jks -alias keyboardku`), lalu `tools\build-apk.ps1 -Release`. Tanpa file itu, build release memakai debug key.

## Keamanan
- WiFi: tiap paket dienkripsi & diautentikasi (ChaCha20-Poly1305, kunci sesi dari PSK hasil pairing kode 6 digit + HKDF, jendela anti-replay). Server menolak brute-force kode (rate limit, rotasi kode).
- Bluetooth: enkripsi link BT standar (SSP).
- `--no-auth` di server hanya untuk jaringan yang dipercaya.

## Troubleshooting
- **Kursor tersendat setelah diam (BT)**: Device Manager → adapter Bluetooth → Power Management → hapus centang "Allow the computer to turn off this device". Pakai WiFi 5 GHz di laptop saat memakai BT (2.4 GHz berbagi antena).
- **Windows tidak menampilkan HID setelah pairing**: hapus device di Windows, pastikan app dalam status `siap pairing`, lalu pairing ulang (Windows meng-cache SDP).
- **Server tidak ditemukan**: firewall (profil jaringan harus Privat), AP dengan client isolation, atau masukkan IP laptop manual di Pengaturan.
- **Game raw-input / anti-cheat**: `SendInput` terlihat sebagai input tersintesis; jalankan server dengan `--relative` untuk gerak relatif mentah. Jendela UAC/admin butuh server yang dijalankan sebagai Administrator.
- **App mati saat layar padam (Xiaomi dkk.)**: ikuti checklist di app (Autostart, Baterai "Tanpa batasan", kunci di Recent). Lihat `docs/OEM.md`.
