# Latency & power: targets, budget, how to measure

## Targets (from the perception literature)
- Indirect input (touchpad/mouse) just-noticeable difference: **~55 ms for dragging, ~96 ms for tapping** (Deber et al. CHI'15); mouse-interaction thresholds ~60 ms (Attig 2017). Direct touch is far stricter (2–11 ms) but does not apply here.
- Goal: **motion-to-cursor ≤ 30–40 ms** with **low jitter**; below ~20 ms there is no measurable user benefit. Jitter/stutter is noticed before absolute latency, so the pipeline never drops motion (cumulative counters, fractional carry) and never sleeps the radio mid-gesture.

## Budget per path

| Stage | Bluetooth HID | WiFi (infrastructure) | USB tethering |
|---|---|---|---|
| Touch sampling (240–480 Hz) | ≤ 2–4 ms | ≤ 2–4 ms | ≤ 2–4 ms |
| Unbuffered dispatch → engine → scheduler | < 0.5 ms | < 0.5 ms | < 0.5 ms |
| Report coalescing tick | ≤ 6 ms (sniff anchor) | ≤ 2 ms | ≤ 2 ms |
| Link | 6–11 ms sniff anchor + retransmits | 2–8 ms (+ power-save spikes if `laptop-tune.ps1` not applied) | 0.5–1 ms |
| Host injection (HID class driver / SendInput) | ~1 ms | ~0.1 ms | ~0.1 ms |
| **Typical total** | **15–25 ms** | **8–15 ms** | **5–8 ms** |

Gyro air-mouse adds ~10 ms sensor-path latency (sensor hub → app) plus half a sample (2.5 ms at 200 Hz).

## What was done for latency
- Everything on one thread (no producer/consumer hand-off, no big.LITTLE wake-up jitter).
- `requestUnbufferedDispatch` per gesture; historical samples consumed as a fallback.
- BT: 6 ms throttle-with-trailing-flush matched to the stack's sniff interval; congested `sendReport` folds into the next report.
- WiFi: 32-byte packets, DSCP CS6 (AC_VO on Qualcomm/Broadcom precedence mapping), `WIFI_MODE_FULL_LOW_LATENCY` only while a gesture is active, no packets at all when idle.
- Server: `HIGH_PRIORITY_CLASS` + `TIME_CRITICAL`, EcoQoS/timer-resolution throttling opt-out, no allocation in the loop, `SIO_UDP_CONNRESET` disabled, absolute-from-delta injection (Windows pointer acceleration cannot distort the phone's curve).

## What was done for power
- Send nothing when idle; BT drops to idle sniff automatically after 5 s; WiFi radio returns to power-save (~200 ms tail) and the low-latency lock is released after 3 s.
- Heartbeat 0.5 Hz idle, 10 Hz only while something is held.
- Screen: pure black UI, **dim after 20 s idle** (largest lever), first touch only wakes; optional proximity pocket mode. `FLAG_KEEP_SCREEN_ON` only, no wake locks.
- Gyro at 200 Hz only while air-mouse is on; unregistered after 30 s of stillness, re-armed by the 20 Hz accelerometer.
- Foreground service type `connectedDevice` (no timeout), no sensor/network work in the background.

## How to measure

Server side (WiFi): `keyboardku-server --verbose` prints inter-packet time per packet; `testclient` prints RTT (loopback ≈ 50–70 µs; expect 2–8 ms over WiFi, < 1 ms over USB).

Phone side (RTT shown in the status bar = HEARTBEAT → PONG round trip; motion latency ≈ RTT/2 + coalescing tick).

Bluetooth: enable *Bluetooth HCI snoop log* in Developer options, reproduce, pull `btsnoop_hci.log` (`adb bugreport`), open in Wireshark: time from touch (logcat `KbKu` timestamps) to the ACL TX of the interrupt-channel report; the gap between consecutive reports shows the sniff anchor (~6–11 ms).

End-to-end: high-speed camera (240 fps phone) filming both the finger and the laptop cursor; count frames. Repeat 20×, report p50/p95.

Power: `adb shell dumpsys batterystats --reset`, use 10 minutes per scenario (idle dimmed / BT gesture / WiFi gesture / air-mouse), then `adb shell dumpsys batterystats id.keyboardku`.

## Results
_(fill in after on-device runs; keep p50 / p95 per path and phone model)_

| Phone | Path | p50 | p95 | Notes |
|---|---|---|---|---|
| | | | | |
