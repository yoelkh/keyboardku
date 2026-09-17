//! Pairing (HKDF/HMAC) and per-packet AEAD (ChaCha20-Poly1305), plus the replay window.

use chacha20poly1305::aead::AeadInPlace;
use chacha20poly1305::{ChaCha20Poly1305, KeyInit, Nonce, Tag};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use sha2::Sha256;

use crate::protocol::{HDR_LEN, PAYLOAD_LEN, TAG_LEN};

pub const INFO_PAIR: &[u8] = b"keyboardku-pair";
pub const INFO_PSK: &[u8] = b"keyboardku-psk";
pub const INFO_C2S: &[u8] = b"keyboardku-c2s";
pub const INFO_S2C: &[u8] = b"keyboardku-s2c";

pub fn hkdf32(ikm: &[u8], salt: &[u8], info: &[u8]) -> [u8; 32] {
    let hk = Hkdf::<Sha256>::new(Some(salt), ikm);
    let mut out = [0u8; 32];
    hk.expand(info, &mut out).expect("hkdf expand");
    out
}

pub fn hmac16(key: &[u8], parts: &[&[u8]]) -> [u8; 16] {
    let mut m = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("hmac key");
    for p in parts {
        m.update(p);
    }
    let full = m.finalize().into_bytes();
    let mut out = [0u8; 16];
    out.copy_from_slice(&full[..16]);
    out
}

/// Constant-time 16-byte compare.
pub fn ct_eq16(a: &[u8; 16], b: &[u8]) -> bool {
    if b.len() != 16 {
        return false;
    }
    let mut d = 0u8;
    for i in 0..16 {
        d |= a[i] ^ b[i];
    }
    d == 0
}

/// K0 from the 6-digit pairing code and both nonces.
pub fn pair_k0(code: &[u8; 6], nonce_s: &[u8; 8], nonce_c: &[u8; 8]) -> [u8; 32] {
    let mut salt = [0u8; 16];
    salt[..8].copy_from_slice(nonce_s);
    salt[8..].copy_from_slice(nonce_c);
    hkdf32(code, &salt, INFO_PAIR)
}

pub fn psk_from_k0(k0: &[u8; 32]) -> [u8; 32] {
    hkdf32(k0, &[], INFO_PSK)
}

pub fn hello_mac(psk: &[u8; 32], nonce_s: &[u8; 8], nonce_c: &[u8; 8], peer_id: &[u8; 8]) -> [u8; 16] {
    hmac16(psk, &[b"hello", nonce_s, nonce_c, peer_id])
}

pub fn pair_mac(k0: &[u8; 32], nonce_s: &[u8; 8], nonce_c: &[u8; 8], peer_id: &[u8; 8]) -> [u8; 16] {
    hmac16(k0, &[b"pair", nonce_s, nonce_c, peer_id])
}

#[inline]
pub fn nonce12(session: u16, seq: u32) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[..4].copy_from_slice(&(session as u32).to_le_bytes());
    n[4..12].copy_from_slice(&(seq as u64).to_le_bytes());
    n
}

pub struct SessionKeys {
    c2s: ChaCha20Poly1305,
    s2c: ChaCha20Poly1305,
}

impl SessionKeys {
    pub fn derive(psk: &[u8; 32], nonce_s: &[u8; 8], nonce_c: &[u8; 8]) -> Self {
        let mut salt = [0u8; 16];
        salt[..8].copy_from_slice(nonce_s);
        salt[8..].copy_from_slice(nonce_c);
        let kc = hkdf32(psk, &salt, INFO_C2S);
        let ks = hkdf32(psk, &salt, INFO_S2C);
        SessionKeys {
            c2s: ChaCha20Poly1305::new_from_slice(&kc).unwrap(),
            s2c: ChaCha20Poly1305::new_from_slice(&ks).unwrap(),
        }
    }

    fn open(c: &ChaCha20Poly1305, pkt: &mut [u8], session: u16, seq: u32) -> bool {
        if pkt.len() != HDR_LEN + PAYLOAD_LEN + TAG_LEN {
            return false;
        }
        let n = nonce12(session, seq);
        let (hdr, rest) = pkt.split_at_mut(HDR_LEN);
        let (body, tag) = rest.split_at_mut(PAYLOAD_LEN);
        let tag = Tag::clone_from_slice(tag);
        c.decrypt_in_place_detached(Nonce::from_slice(&n), hdr, body, &tag).is_ok()
    }

    fn seal(c: &ChaCha20Poly1305, pkt: &mut [u8], session: u16, seq: u32) {
        let n = nonce12(session, seq);
        let (hdr, rest) = pkt.split_at_mut(HDR_LEN);
        let (body, tag_out) = rest.split_at_mut(PAYLOAD_LEN);
        let tag = c.encrypt_in_place_detached(Nonce::from_slice(&n), hdr, body).expect("seal");
        tag_out.copy_from_slice(&tag);
    }

    /// Decrypt a 32-byte phone->server packet in place. On success `pkt[8..16]` holds the plaintext payload.
    pub fn open_c2s(&self, pkt: &mut [u8], session: u16, seq: u32) -> bool {
        Self::open(&self.c2s, pkt, session, seq)
    }
    /// Encrypt a server->phone packet in place (header written, plaintext payload in [8..16]); tag goes to [16..32].
    pub fn seal_s2c(&self, pkt: &mut [u8], session: u16, seq: u32) {
        Self::seal(&self.s2c, pkt, session, seq)
    }
    // Phone role (test client).
    pub fn seal_c2s(&self, pkt: &mut [u8], session: u16, seq: u32) {
        Self::seal(&self.c2s, pkt, session, seq)
    }
    pub fn open_s2c(&self, pkt: &mut [u8], session: u16, seq: u32) -> bool {
        Self::open(&self.s2c, pkt, session, seq)
    }
}

/// 64-packet sliding replay window (RFC 6479 style).
pub struct Replay {
    highest: u32,
    mask: u64,
    started: bool,
}

impl Default for Replay {
    fn default() -> Self {
        Self::new()
    }
}

impl Replay {
    pub fn new() -> Self {
        Replay { highest: 0, mask: 0, started: false }
    }
    /// Returns true (and records it) if `seq` is fresh.
    pub fn accept(&mut self, seq: u32) -> bool {
        if !self.started {
            self.started = true;
            self.highest = seq;
            self.mask = 1;
            return true;
        }
        if seq == self.highest {
            return false;
        }
        let ahead = seq.wrapping_sub(self.highest);
        if (ahead as i32) > 0 {
            if ahead >= 64 {
                self.mask = 1;
            } else {
                self.mask = (self.mask << ahead) | 1;
            }
            self.highest = seq;
            true
        } else {
            let behind = self.highest.wrapping_sub(seq);
            if behind >= 64 {
                return false;
            }
            let bit = 1u64 << behind;
            if self.mask & bit != 0 {
                false
            } else {
                self.mask |= bit;
                true
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::*;

    #[test]
    fn replay_window() {
        let mut r = Replay::new();
        assert!(r.accept(10));
        assert!(!r.accept(10));
        assert!(r.accept(12));
        assert!(r.accept(11));
        assert!(!r.accept(11));
        assert!(r.accept(100));
        assert!(!r.accept(12)); // out of window (100-12 >= 64)
        assert!(r.accept(99));
    }

    #[test]
    fn aead_roundtrip_and_tamper() {
        let psk = [7u8; 32];
        let ns = [1u8; 8];
        let nc = [2u8; 8];
        let k = SessionKeys::derive(&psk, &ns, &nc);
        let mut pkt = [0u8; DATA_LEN];
        write_header(&mut pkt, T_MOUSE, 5, 9);
        wr_i32(&mut pkt, 8, -3);
        wr_i32(&mut pkt, 12, 4);
        k.seal_c2s(&mut pkt, 5, 9);
        let mut copy = pkt;
        assert!(k.open_c2s(&mut copy, 5, 9));
        assert_eq!(rd_i32(&copy, 8), -3);
        assert_eq!(rd_i32(&copy, 12), 4);
        let mut bad = pkt;
        bad[20] ^= 1;
        assert!(!k.open_c2s(&mut bad, 5, 9));
        let mut wrong_seq = pkt;
        assert!(!k.open_c2s(&mut wrong_seq, 5, 10));
    }

    #[test]
    fn pairing_derivation_is_deterministic() {
        let code = *b"123456";
        let ns = [3u8; 8];
        let nc = [4u8; 8];
        let k0a = pair_k0(&code, &ns, &nc);
        let k0b = pair_k0(&code, &ns, &nc);
        assert_eq!(k0a, k0b);
        assert_ne!(pair_k0(b"123457", &ns, &nc), k0a);
        let psk = psk_from_k0(&k0a);
        let m = hello_mac(&psk, &ns, &nc, &[9u8; 8]);
        assert!(ct_eq16(&m, &m));
    }
}
