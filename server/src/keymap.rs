//! HID keyboard usage (page 0x07) -> PC/AT set-1 scan code (+ extended flag), and consumer usage -> virtual key.

/// (scancode, extended). scancode 0 = unmapped.
pub const SCAN: [(u8, bool); 256] = build_scan_table();

const fn build_scan_table() -> [(u8, bool); 256] {
    let mut t = [(0u8, false); 256];
    // a..z (0x04..0x1D)
    let letters: [u8; 26] = [
        0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32, 0x31, 0x18, 0x19, 0x10, 0x13,
        0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C,
    ];
    let mut i = 0;
    while i < 26 {
        t[0x04 + i] = (letters[i], false);
        i += 1;
    }
    // 1..9,0 (0x1E..0x27)
    let mut d = 0;
    while d < 9 {
        t[0x1E + d] = (0x02 + d as u8, false);
        d += 1;
    }
    t[0x27] = (0x0B, false);
    t[0x28] = (0x1C, false); // Enter
    t[0x29] = (0x01, false); // Esc
    t[0x2A] = (0x0E, false); // Backspace
    t[0x2B] = (0x0F, false); // Tab
    t[0x2C] = (0x39, false); // Space
    t[0x2D] = (0x0C, false); // -
    t[0x2E] = (0x0D, false); // =
    t[0x2F] = (0x1A, false); // [
    t[0x30] = (0x1B, false); // ]
    t[0x31] = (0x2B, false); // backslash
    t[0x32] = (0x2B, false); // non-US #
    t[0x33] = (0x27, false); // ;
    t[0x34] = (0x28, false); // '
    t[0x35] = (0x29, false); // `
    t[0x36] = (0x33, false); // ,
    t[0x37] = (0x34, false); // .
    t[0x38] = (0x35, false); // /
    t[0x39] = (0x3A, false); // CapsLock
    // F1..F10 (0x3A..0x43) -> 0x3B..0x44
    let mut f = 0;
    while f < 10 {
        t[0x3A + f] = (0x3B + f as u8, false);
        f += 1;
    }
    t[0x44] = (0x57, false); // F11
    t[0x45] = (0x58, false); // F12
    t[0x46] = (0x37, true); // PrintScreen
    t[0x47] = (0x46, false); // ScrollLock
    // 0x48 Pause handled via VK_PAUSE in inject.rs
    t[0x49] = (0x52, true); // Insert
    t[0x4A] = (0x47, true); // Home
    t[0x4B] = (0x49, true); // PageUp
    t[0x4C] = (0x53, true); // Delete
    t[0x4D] = (0x4F, true); // End
    t[0x4E] = (0x51, true); // PageDown
    t[0x4F] = (0x4D, true); // Right
    t[0x50] = (0x4B, true); // Left
    t[0x51] = (0x50, true); // Down
    t[0x52] = (0x48, true); // Up
    t[0x53] = (0x45, false); // NumLock
    t[0x54] = (0x35, true); // KP /
    t[0x55] = (0x37, false); // KP *
    t[0x56] = (0x4A, false); // KP -
    t[0x57] = (0x4E, false); // KP +
    t[0x58] = (0x1C, true); // KP Enter
    let kp: [u8; 9] = [0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49];
    let mut k = 0;
    while k < 9 {
        t[0x59 + k] = (kp[k], false);
        k += 1;
    }
    t[0x62] = (0x52, false); // KP 0
    t[0x63] = (0x53, false); // KP .
    t[0x64] = (0x56, false); // non-US backslash
    t[0x65] = (0x5D, true); // Application / Menu
    // modifiers
    t[0xE0] = (0x1D, false); // LCtrl
    t[0xE1] = (0x2A, false); // LShift
    t[0xE2] = (0x38, false); // LAlt
    t[0xE3] = (0x5B, true); // LGui
    t[0xE4] = (0x1D, true); // RCtrl
    t[0xE5] = (0x36, false); // RShift
    t[0xE6] = (0x38, true); // RAlt
    t[0xE7] = (0x5C, true); // RGui
    t
}

pub const USAGE_PAUSE: u8 = 0x48;
pub const VK_PAUSE: u16 = 0x13;

/// Consumer-control usage (page 0x0C) -> Windows virtual key, 0 if unmapped.
pub fn consumer_vk(usage: u16) -> u16 {
    match usage {
        0x00E9 => 0xAF, // VK_VOLUME_UP
        0x00EA => 0xAE, // VK_VOLUME_DOWN
        0x00E2 => 0xAD, // VK_VOLUME_MUTE
        0x00CD => 0xB3, // VK_MEDIA_PLAY_PAUSE
        0x00B5 => 0xB0, // VK_MEDIA_NEXT_TRACK
        0x00B6 => 0xB1, // VK_MEDIA_PREV_TRACK
        0x00B7 => 0xB2, // VK_MEDIA_STOP
        0x0223 => 0xAC, // VK_BROWSER_HOME
        0x0221 => 0xAA, // VK_BROWSER_SEARCH
        0x022A => 0xAB, // VK_BROWSER_FAVORITES
        0x018A => 0xB4, // VK_LAUNCH_MAIL
        0x0192 => 0xB7, // VK_LAUNCH_APP2 (calculator)
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn spot_checks() {
        assert_eq!(SCAN[0x04], (0x1E, false)); // a
        assert_eq!(SCAN[0x1D], (0x2C, false)); // z
        assert_eq!(SCAN[0x1E], (0x02, false)); // 1
        assert_eq!(SCAN[0x27], (0x0B, false)); // 0
        assert_eq!(SCAN[0x43], (0x44, false)); // F10
        assert_eq!(SCAN[0x4F], (0x4D, true)); // Right
        assert_eq!(SCAN[0xE3], (0x5B, true)); // LGui
        assert_eq!(SCAN[0x00], (0, false));
        assert_eq!(consumer_vk(0xE9), 0xAF);
    }
}
