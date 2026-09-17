# Bluetooth HID design notes

## Report descriptor (android/.../transport/HidDescriptor.kt)

Combo device (`SUBCLASS1_COMBO`, 0xC0), report protocol with IDs, boot-compatible:

| Report ID | Type | Length (without ID byte) | Layout |
|---|---|---|---|
| 1 | Keyboard | 8 | `[mods][0x00][k1][k2][k3][k4][k5][k6]` — mods bits 0x01 LCtrl 0x02 LShift 0x04 LAlt 0x08 LGui 0x10 RCtrl 0x20 RShift 0x40 RAlt 0x80 RGui |
| 2 | Mouse | 7 | `[buttons L R M][xL][xH][yL][yH][wheel][pan]` — X/Y **16-bit** relative (−32767..32767), wheel/pan 8-bit |
| 3 | Consumer | 2 | 16-bit usage LE (0xE9 Vol+, 0xEA Vol−, 0xE2 Mute, 0xCD Play/Pause, 0xB5 Next, 0xB6 Prev); `00 00` = release |
| 2 (boot) | Mouse boot | 3 | `[buttons][x8][y8]` when the host selects boot protocol |

`BluetoothHidDevice.sendReport(device, id, data)` — `data` **never** contains the ID byte; the stack prepends it (and strips it in boot mode). A wrong length is silently ignored by Windows.

Why 16-bit X/Y: reports are coalesced to the 6 ms sniff anchor; a flick easily exceeds ±127 per report, and clamping produces "cursor lags then stops short". Windows' inbox HID mouse driver handles 16-bit relative axes.

## What the Android stack does that you cannot change

- **Sniff mode** (`bta_dm_cfg.cc`, `bta_api.h`): HID-device role uses sniff 10–18 slots (6.25–11.25 ms) while busy, 30–54 slots (18.75–33.75 ms) after 5 s idle. Every report waits for the next anchor → the app sends at most one mouse report per ~6 ms (`ReportScheduler`), and never faster.
- **QoS settings are inert**: `BluetoothHidDeviceAppQosSettings` are stored but never sent on L2CAP (`hidd_conn.cc` only sets `mtu_present`). We pass the WearMouse constants for host compatibility.
- `sendReport` has no queue: it returns `false` when the L2CAP channel is congested → the scheduler keeps the delta and merges it into the next report. Keyboard states are retried (5 ms) because a lost key state would stick.
- `registerApp` requires process importance ≤ `IMPORTANCE_VISIBLE` → the foreground service is started **before** registering, and the registration survives screen-off.
- The SDP record advertises `HIDReconnectInitiate = true`: **the phone initiates connections**. `BtHidTransport` calls `connect()` with backoff 1/2/5/10 s after any disconnect (laptop sleep/resume).

## Pairing flow

Recommended (avoids the HyperOS "Incorrect PIN or passkey" bug that affects PC-initiated pairing):
1. App status `siap pairing` (HID app registered).
2. Pair → "Cari & sandingkan laptop (dari HP)" → scan (`BLUETOOTH_SCAN`, neverForLocation) → pick → `createBond()` → `BOND_BONDED` → `hid.connect()`.

Alternative: "Jadikan HP terlihat 120 s", then add the phone from Windows Settings → Bluetooth. If Windows had paired the phone **before** the HID app was registered, Windows keeps the old SDP record: remove the device in Windows and pair again.

Callbacks that must be answered (or Windows stalls / shows a driver error): `onGetReport` → `replyReport` with the current report (zeros are fine); `onSetReport` → `reportError(ERROR_RSP_SUCCESS)`; `onSetProtocol` → remember boot/report mode.

## Capability probe (UNSUPPORTED detection)

`getProfileProxy(HID_DEVICE)` returning false, `onServiceConnected` not arriving within 4 s, or `registerApp` returning false / no `onAppStatusChanged` within 4 s → `UNSUPPORTED`, cached in `Prefs.hidUnsupported`, and `TransportManager` falls back to WiFi in AUTO mode. There is **no runtime toggle** on ROMs that disable the profile (it is a build-time overlay), so detection + fallback is the only UX. Check on the device:

```
adb shell dumpsys bluetooth_manager | findstr /i hid
adb shell getprop | findstr /i hid
```

## Windows notes
- Device Manager should list *Bluetooth HID Device*, *HID Keyboard Device*, *HID-compliant mouse* under the phone.
- Untick "Allow the computer to turn off this device to save power" on the Bluetooth adapter and the HID entries if the cursor stutters after idle or reconnect fails after sleep.
- Windows may also connect A2DP/HFP to the phone; HID still works. Disable those services in the device's properties if audio routing is annoying.
- 2.4 GHz WiFi on the laptop or phone shares the antenna with BT: prefer 5 GHz.
