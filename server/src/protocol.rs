//! Wire format shared with the Android app (see docs/PROTOCOL.md). No allocation.

pub const MAGIC: u8 = 0xA8;
pub const PORT: u16 = 47800;
pub const VERSION: u16 = 2;

pub const HDR_LEN: usize = 8;
pub const PAYLOAD_LEN: usize = 8;
pub const TAG_LEN: usize = 16;
pub const DATA_LEN: usize = HDR_LEN + PAYLOAD_LEN + TAG_LEN; // 32
pub const DISCOVER_LEN: usize = 16;
pub const OFFER_LEN: usize = 52;
pub const HELLO_LEN: usize = 48;
pub const REJECT_LEN: usize = 16;

// phone -> server
pub const T_MOUSE: u8 = 0x01;
pub const T_SCROLL: u8 = 0x02;
pub const T_BUTTONS: u8 = 0x03;
pub const T_KEYS: u8 = 0x04;
pub const T_UNICODE: u8 = 0x05;
pub const T_CONSUMER: u8 = 0x06;
pub const T_HEARTBEAT: u8 = 0x07;
pub const T_RELEASE_ALL: u8 = 0x08;
pub const T_HELLO: u8 = 0x10;
pub const T_PAIR: u8 = 0x13;
pub const T_DISCOVER: u8 = 0x20;
// server -> phone
pub const T_WELCOME: u8 = 0x11;
pub const T_REJECT: u8 = 0x12;
pub const T_OFFER: u8 = 0x21;
pub const T_PONG: u8 = 0x22;

pub const REJ_UNKNOWN_PEER: u8 = 1;
pub const REJ_BAD_MAC: u8 = 2;
pub const REJ_RATE_LIMITED: u8 = 3;
pub const REJ_VERSION: u8 = 4;
pub const REJ_PAIRING_DISABLED: u8 = 5;

pub const OFFER_FLAG_PAIRING_ALLOWED: u16 = 1;
pub const OFFER_FLAG_NO_CODE: u16 = 2; // server runs with --no-auth: use code "000000" without asking

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Header {
    pub ty: u8,
    pub session: u16,
    pub seq: u32,
}

#[inline]
pub fn parse_header(b: &[u8]) -> Option<Header> {
    if b.len() < HDR_LEN || b[0] != MAGIC {
        return None;
    }
    Some(Header {
        ty: b[1],
        session: u16::from_le_bytes([b[2], b[3]]),
        seq: u32::from_le_bytes([b[4], b[5], b[6], b[7]]),
    })
}

#[inline]
pub fn write_header(b: &mut [u8], ty: u8, session: u16, seq: u32) {
    b[0] = MAGIC;
    b[1] = ty;
    b[2..4].copy_from_slice(&session.to_le_bytes());
    b[4..8].copy_from_slice(&seq.to_le_bytes());
}

#[inline]
pub fn rd_i32(b: &[u8], off: usize) -> i32 {
    i32::from_le_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
}
#[inline]
pub fn rd_u32(b: &[u8], off: usize) -> u32 {
    u32::from_le_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
}
#[inline]
pub fn rd_u16(b: &[u8], off: usize) -> u16 {
    u16::from_le_bytes([b[off], b[off + 1]])
}
#[inline]
pub fn wr_u16(b: &mut [u8], off: usize, v: u16) {
    b[off..off + 2].copy_from_slice(&v.to_le_bytes());
}
#[inline]
pub fn wr_u32(b: &mut [u8], off: usize, v: u32) {
    b[off..off + 4].copy_from_slice(&v.to_le_bytes());
}
#[inline]
pub fn wr_i32(b: &mut [u8], off: usize, v: i32) {
    b[off..off + 4].copy_from_slice(&v.to_le_bytes());
}

/// Build an OFFER into `out` (52 bytes).
pub fn build_offer(out: &mut [u8; OFFER_LEN], nonce_s: &[u8; 8], flags: u16, name: &str) {
    out.fill(0);
    write_header(out, T_OFFER, 0, 0);
    out[8..16].copy_from_slice(nonce_s);
    wr_u16(out, 16, VERSION);
    wr_u16(out, 18, flags);
    let nb = name.as_bytes();
    let mut n = nb.len().min(32);
    // do not cut a UTF-8 sequence in half
    while n > 0 && n < nb.len() && (nb[n] & 0xC0) == 0x80 {
        n -= 1;
    }
    out[20..20 + n].copy_from_slice(&nb[..n]);
}

pub fn build_reject(out: &mut [u8; REJECT_LEN], reason: u8, retry_after_ms: u16) {
    out.fill(0);
    write_header(out, T_REJECT, 0, 0);
    out[8] = reason;
    wr_u16(out, 9, retry_after_ms);
}

/// Wrap-aware "a is newer than b" for u32 sequence numbers.
#[inline]
pub fn seq_newer(a: u32, b: u32) -> bool {
    (a.wrapping_sub(b) as i32) > 0
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn header_roundtrip() {
        let mut b = [0u8; 8];
        write_header(&mut b, T_MOUSE, 0x1234, 0xDEADBEEF);
        let h = parse_header(&b).unwrap();
        assert_eq!(h, Header { ty: T_MOUSE, session: 0x1234, seq: 0xDEADBEEF });
        assert!(parse_header(&[0u8; 8]).is_none());
    }
    #[test]
    fn offer_name_utf8_safe() {
        let mut o = [0u8; OFFER_LEN];
        build_offer(&mut o, &[1; 8], 1, "ééééééééééééééééé"); // 34 bytes
        let end = o[20..52].iter().position(|&c| c == 0).unwrap_or(32);
        assert!(std::str::from_utf8(&o[20..20 + end]).is_ok());
    }
    #[test]
    fn seq_cmp() {
        assert!(seq_newer(1, 0));
        assert!(!seq_newer(0, 1));
        assert!(seq_newer(0, u32::MAX));
        assert!(!seq_newer(u32::MAX, 0));
    }
}
