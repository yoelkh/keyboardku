//! Input injection with Win32 SendInput. Tracks held state so everything can be released on link loss.

use std::time::{Duration, Instant};

use windows_sys::Win32::Foundation::POINT;
use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
    SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, INPUT_MOUSE, KEYBDINPUT, KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP,
    KEYEVENTF_SCANCODE, KEYEVENTF_UNICODE, MOUSEEVENTF_ABSOLUTE, MOUSEEVENTF_HWHEEL, MOUSEEVENTF_LEFTDOWN,
    MOUSEEVENTF_LEFTUP, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP, MOUSEEVENTF_MOVE, MOUSEEVENTF_RIGHTDOWN,
    MOUSEEVENTF_RIGHTUP, MOUSEEVENTF_VIRTUALDESK, MOUSEEVENTF_WHEEL, MOUSEEVENTF_XDOWN, MOUSEEVENTF_XUP, MOUSEINPUT,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    GetCursorPos, GetSystemMetrics, SystemParametersInfoW, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN,
    SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN, SPI_GETKEYBOARDDELAY, SPI_GETKEYBOARDSPEED, SPI_GETMOUSE,
};

use crate::keymap::{consumer_vk, SCAN, USAGE_PAUSE, VK_PAUSE};

const XBUTTON1: u32 = 1;
const XBUTTON2: u32 = 2;

pub struct Injector {
    pub relative: bool,
    pub verbose: bool,
    held_buttons: u8,
    held_mods: u8,
    held_keys: [u8; 6],
    held_consumer: u16,
    repeat_usage: u8,
    repeat_next: Option<Instant>,
    repeat_delay: Duration,
    repeat_period: Duration,
}

#[inline]
fn mouse_input(dx: i32, dy: i32, data: u32, flags: u32) -> INPUT {
    INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 {
            mi: MOUSEINPUT { dx, dy, mouseData: data, dwFlags: flags, time: 0, dwExtraInfo: 0 },
        },
    }
}

#[inline]
fn key_input(vk: u16, scan: u16, flags: u32) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT { wVk: vk, wScan: scan, dwFlags: flags, time: 0, dwExtraInfo: 0 },
        },
    }
}

#[inline]
fn send(inputs: &[INPUT]) -> u32 {
    if inputs.is_empty() {
        return 0;
    }
    unsafe { SendInput(inputs.len() as u32, inputs.as_ptr(), core::mem::size_of::<INPUT>() as i32) }
}

impl Injector {
    pub fn new(relative: bool, verbose: bool) -> Self {
        let (delay, period) = keyboard_repeat_params();
        Injector {
            relative,
            verbose,
            held_buttons: 0,
            held_mods: 0,
            held_keys: [0; 6],
            held_consumer: 0,
            repeat_usage: 0,
            repeat_next: None,
            repeat_delay: delay,
            repeat_period: period,
        }
    }

    pub fn any_held(&self) -> bool {
        self.held_buttons != 0 || self.held_mods != 0 || self.held_keys.iter().any(|&k| k != 0) || self.held_consumer != 0
    }

    // ---------------- mouse ----------------

    pub fn mouse_move(&mut self, dx: i32, dy: i32) {
        if dx == 0 && dy == 0 {
            return;
        }
        if self.relative {
            send(&[mouse_input(dx, dy, 0, MOUSEEVENTF_MOVE)]);
            return;
        }
        unsafe {
            let mut p = POINT { x: 0, y: 0 };
            if GetCursorPos(&mut p) == 0 {
                send(&[mouse_input(dx, dy, 0, MOUSEEVENTF_MOVE)]);
                return;
            }
            let vx = GetSystemMetrics(SM_XVIRTUALSCREEN);
            let vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
            let vw = GetSystemMetrics(SM_CXVIRTUALSCREEN).max(1);
            let vh = GetSystemMetrics(SM_CYVIRTUALSCREEN).max(1);
            let nx = (p.x + dx).clamp(vx, vx + vw - 1);
            let ny = (p.y + dy).clamp(vy, vy + vh - 1);
            // centre of the target pixel in 0..65535 normalized coordinates
            let ax = (((nx - vx) as f64 + 0.5) * 65536.0 / vw as f64).clamp(0.0, 65535.0) as i32;
            let ay = (((ny - vy) as f64 + 0.5) * 65536.0 / vh as f64).clamp(0.0, 65535.0) as i32;
            send(&[mouse_input(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK)]);
        }
    }

    pub fn scroll(&mut self, v: i32, h: i32) {
        let mut buf = [mouse_input(0, 0, 0, 0); 2];
        let mut n = 0;
        if v != 0 {
            buf[n] = mouse_input(0, 0, v as u32, MOUSEEVENTF_WHEEL);
            n += 1;
        }
        if h != 0 {
            buf[n] = mouse_input(0, 0, h as u32, MOUSEEVENTF_HWHEEL);
            n += 1;
        }
        send(&buf[..n]);
    }

    /// Apply a full button mask (bit0 L, bit1 R, bit2 M, bit3 X1, bit4 X2).
    pub fn buttons_state(&mut self, mask: u8) {
        let changed = self.held_buttons ^ mask;
        if changed == 0 {
            return;
        }
        let mut buf = [mouse_input(0, 0, 0, 0); 5];
        let mut n = 0;
        let table: [(u8, u32, u32, u32); 5] = [
            (0x01, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP, 0),
            (0x02, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP, 0),
            (0x04, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP, 0),
            (0x08, MOUSEEVENTF_XDOWN, MOUSEEVENTF_XUP, XBUTTON1),
            (0x10, MOUSEEVENTF_XDOWN, MOUSEEVENTF_XUP, XBUTTON2),
        ];
        // releases first, then presses
        for &(bit, _down, up, data) in &table {
            if changed & bit != 0 && mask & bit == 0 {
                buf[n] = mouse_input(0, 0, data, up);
                n += 1;
            }
        }
        for &(bit, down, _up, data) in &table {
            if changed & bit != 0 && mask & bit != 0 {
                buf[n] = mouse_input(0, 0, data, down);
                n += 1;
            }
        }
        send(&buf[..n]);
        self.held_buttons = mask;
        if self.verbose {
            println!("buttons -> {mask:#04x}");
        }
    }

    // ---------------- keyboard ----------------

    fn key_event(&self, usage: u8, down: bool) -> Option<INPUT> {
        if usage == USAGE_PAUSE {
            return Some(key_input(VK_PAUSE, 0, if down { 0 } else { KEYEVENTF_KEYUP }));
        }
        let (sc, ext) = SCAN[usage as usize];
        if sc == 0 {
            return None;
        }
        let mut flags = KEYEVENTF_SCANCODE;
        if ext {
            flags |= KEYEVENTF_EXTENDEDKEY;
        }
        if !down {
            flags |= KEYEVENTF_KEYUP;
        }
        Some(key_input(0, sc as u16, flags))
    }

    /// Apply a full HID-style keyboard state (modifier bits + up to 6 usages).
    pub fn keys_state(&mut self, mods: u8, keys: &[u8; 6]) {
        let mut buf: [INPUT; 28] = [key_input(0, 0, 0); 28];
        let mut n = 0;
        // 1. release keys no longer present
        for &k in self.held_keys.iter() {
            if k != 0 && !keys.contains(&k) {
                if let Some(e) = self.key_event(k, false) {
                    buf[n] = e;
                    n += 1;
                }
                if k == self.repeat_usage {
                    self.repeat_usage = 0;
                    self.repeat_next = None;
                }
            }
        }
        // 2. release modifiers no longer present
        for i in 0..8u8 {
            let bit = 1 << i;
            if self.held_mods & bit != 0 && mods & bit == 0 {
                if let Some(e) = self.key_event(0xE0 + i, false) {
                    buf[n] = e;
                    n += 1;
                }
            }
        }
        // 3. press new modifiers
        for i in 0..8u8 {
            let bit = 1 << i;
            if self.held_mods & bit == 0 && mods & bit != 0 {
                if let Some(e) = self.key_event(0xE0 + i, true) {
                    buf[n] = e;
                    n += 1;
                }
            }
        }
        // 4. press new keys
        for &k in keys.iter() {
            if k != 0 && !self.held_keys.contains(&k) {
                if let Some(e) = self.key_event(k, true) {
                    buf[n] = e;
                    n += 1;
                }
                self.repeat_usage = k;
                self.repeat_next = Some(Instant::now() + self.repeat_delay);
            }
        }
        send(&buf[..n]);
        self.held_mods = mods;
        self.held_keys = *keys;
        if self.verbose && n > 0 {
            println!("keys -> mods={mods:#04x} keys={keys:02x?}");
        }
    }

    /// Type UTF-16 code units (surrogate pairs must be contiguous). Max 3 units per call.
    pub fn unicode(&mut self, units: &[u16]) {
        let mut buf: [INPUT; 6] = [key_input(0, 0, 0); 6];
        let mut n = 0;
        for &u in units.iter().take(3) {
            buf[n] = key_input(0, u, KEYEVENTF_UNICODE);
            buf[n + 1] = key_input(0, u, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP);
            n += 2;
        }
        send(&buf[..n]);
    }

    /// Consumer control: `usage` = currently held usage, 0 = released.
    pub fn consumer_state(&mut self, usage: u16) {
        if usage == self.held_consumer {
            return;
        }
        let mut buf: [INPUT; 2] = [key_input(0, 0, 0); 2];
        let mut n = 0;
        if self.held_consumer != 0 {
            let vk = consumer_vk(self.held_consumer);
            if vk != 0 {
                buf[n] = key_input(vk, 0, KEYEVENTF_KEYUP);
                n += 1;
            }
        }
        if usage != 0 {
            let vk = consumer_vk(usage);
            if vk != 0 {
                buf[n] = key_input(vk, 0, 0);
                n += 1;
            }
        }
        send(&buf[..n]);
        self.held_consumer = usage;
    }

    /// Release every key, modifier, button and consumer usage we currently hold.
    pub fn release_all(&mut self) {
        let had = self.any_held();
        self.keys_state(0, &[0; 6]);
        self.buttons_state(0);
        self.consumer_state(0);
        self.repeat_usage = 0;
        self.repeat_next = None;
        if self.verbose && had {
            println!("release_all");
        }
    }

    // ---------------- key repeat (host-side, like a real keyboard) ----------------

    /// Next time `tick` must be called, if a key is being auto-repeated.
    pub fn next_deadline(&self) -> Option<Instant> {
        self.repeat_next
    }

    pub fn tick(&mut self, now: Instant) {
        if let Some(t) = self.repeat_next {
            if now >= t && self.repeat_usage != 0 {
                if let Some(e) = self.key_event(self.repeat_usage, true) {
                    send(&[e]);
                }
                self.repeat_next = Some(now + self.repeat_period);
            }
        }
    }
}

/// (initial delay, repeat period) from the user's Windows keyboard settings.
fn keyboard_repeat_params() -> (Duration, Duration) {
    let mut delay: u32 = 1;
    let mut speed: u32 = 31;
    unsafe {
        SystemParametersInfoW(SPI_GETKEYBOARDDELAY, 0, &mut delay as *mut u32 as *mut core::ffi::c_void, 0);
        SystemParametersInfoW(SPI_GETKEYBOARDSPEED, 0, &mut speed as *mut u32 as *mut core::ffi::c_void, 0);
    }
    let delay_ms = 250 * (delay.min(3) + 1);
    let hz = 2.5 + 27.5 * (speed.min(31) as f64) / 31.0;
    (Duration::from_millis(delay_ms as u64), Duration::from_secs_f64(1.0 / hz))
}

/// True if "Enhance pointer precision" is enabled (only matters in --relative mode).
pub fn enhance_pointer_precision() -> bool {
    let mut v: [u32; 3] = [0; 3];
    unsafe {
        SystemParametersInfoW(SPI_GETMOUSE, 0, v.as_mut_ptr() as *mut core::ffi::c_void, 0);
    }
    v[2] != 0
}
