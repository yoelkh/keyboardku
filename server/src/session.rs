//! Pairing store, handshake state (nonces, rate limiting, code), and the active session.

use std::fs;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use crate::crypto::{Replay, SessionKeys};

/// Cryptographically random bytes from the OS (BCryptGenRandom).
pub fn random_bytes(out: &mut [u8]) {
    use windows_sys::Win32::Security::Cryptography::{BCryptGenRandom, BCRYPT_USE_SYSTEM_PREFERRED_RNG};
    let st = unsafe {
        BCryptGenRandom(core::ptr::null_mut(), out.as_mut_ptr(), out.len() as u32, BCRYPT_USE_SYSTEM_PREFERRED_RNG)
    };
    if st != 0 {
        // Extremely unlikely; fall back to a time-derived value rather than zeros.
        let t = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let h = crate::crypto::hkdf32(&t.to_le_bytes(), b"fallback", b"rng");
        for (i, b) in out.iter_mut().enumerate() {
            *b = h[i % 32];
        }
    }
}

pub fn random_code() -> [u8; 6] {
    let mut r = [0u8; 4];
    random_bytes(&mut r);
    let n = u32::from_le_bytes(r) % 1_000_000;
    let s = format!("{n:06}");
    let mut c = [0u8; 6];
    c.copy_from_slice(s.as_bytes());
    c
}

// ---------------- persistent peer store ----------------

pub struct PeerStore {
    path: PathBuf,
    peers: Vec<([u8; 8], [u8; 32])>,
}

impl PeerStore {
    pub fn load() -> Self {
        let base = std::env::var("APPDATA").map(PathBuf::from).unwrap_or_else(|_| PathBuf::from("."));
        let dir = base.join("KeyboardKu");
        let path = dir.join("peers.bin");
        let mut peers = Vec::new();
        if let Ok(data) = fs::read(&path) {
            for rec in data.chunks_exact(40) {
                let mut id = [0u8; 8];
                let mut psk = [0u8; 32];
                id.copy_from_slice(&rec[..8]);
                psk.copy_from_slice(&rec[8..40]);
                peers.push((id, psk));
            }
        }
        PeerStore { path, peers }
    }

    pub fn len(&self) -> usize {
        self.peers.len()
    }

    pub fn find(&self, id: &[u8; 8]) -> Option<&[u8; 32]> {
        self.peers.iter().find(|(pid, _)| pid == id).map(|(_, k)| k)
    }

    pub fn insert(&mut self, id: [u8; 8], psk: [u8; 32]) {
        if let Some(e) = self.peers.iter_mut().find(|(pid, _)| *pid == id) {
            e.1 = psk;
        } else {
            self.peers.push((id, psk));
        }
        let _ = self.save();
    }

    fn save(&self) -> std::io::Result<()> {
        if let Some(dir) = self.path.parent() {
            fs::create_dir_all(dir)?;
        }
        let mut data = Vec::with_capacity(self.peers.len() * 40);
        for (id, psk) in &self.peers {
            data.extend_from_slice(id);
            data.extend_from_slice(psk);
        }
        fs::write(&self.path, data)
    }

    pub fn path(&self) -> &PathBuf {
        &self.path
    }
}

// ---------------- handshake state ----------------

const OFFER_TTL: Duration = Duration::from_secs(10);
const MAX_OFFERS: usize = 4;

pub struct Handshake {
    offers: [Option<([u8; 8], Instant)>; MAX_OFFERS],
    next: usize,
    pub code: [u8; 6],
    pub code_fixed: bool,
    pub pairing_allowed: bool,
    fails: u32,
    last_fail: Option<Instant>,
}

impl Handshake {
    pub fn new(code: Option<[u8; 6]>, pairing_allowed: bool) -> Self {
        Handshake {
            offers: [None; MAX_OFFERS],
            next: 0,
            code_fixed: code.is_some(),
            code: code.unwrap_or_else(random_code),
            pairing_allowed,
            fails: 0,
            last_fail: None,
        }
    }

    pub fn new_offer_nonce(&mut self) -> [u8; 8] {
        let mut n = [0u8; 8];
        random_bytes(&mut n);
        self.offers[self.next] = Some((n, Instant::now()));
        self.next = (self.next + 1) % MAX_OFFERS;
        n
    }

    pub fn offer_valid(&self, nonce: &[u8]) -> bool {
        let now = Instant::now();
        self.offers
            .iter()
            .flatten()
            .any(|(n, t)| n == nonce && now.duration_since(*t) < OFFER_TTL)
    }

    /// Ok(()) if a PAIR attempt may be evaluated now, Err(retry_after_ms) otherwise.
    pub fn pair_attempt_allowed(&self) -> Result<(), u16> {
        if self.fails < 3 {
            return Ok(());
        }
        match self.last_fail {
            Some(t) => {
                let since = Instant::now().duration_since(t);
                if since >= Duration::from_secs(2) {
                    Ok(())
                } else {
                    Err((2000 - since.as_millis() as u16).max(100))
                }
            }
            None => Ok(()),
        }
    }

    /// Records a failed PAIR. Returns true if the code was rotated.
    pub fn record_pair_failure(&mut self) -> bool {
        self.fails += 1;
        self.last_fail = Some(Instant::now());
        if self.fails >= 10 && !self.code_fixed {
            self.code = random_code();
            self.fails = 0;
            return true;
        }
        false
    }

    pub fn record_pair_success(&mut self) {
        self.fails = 0;
        self.last_fail = None;
    }
}

// ---------------- active session ----------------

pub struct Session {
    pub peer: SocketAddr,
    pub id: u16,
    pub keys: SessionKeys,
    pub replay: Replay,
    pub last_seen: Instant,
    pub started: Instant,
    pub held_flag: bool,
    pub idle_released: bool,
    pub last_mouse_seq: Option<u32>,
    pub cum_x: i32,
    pub cum_y: i32,
    pub last_scroll_seq: Option<u32>,
    pub cum_v: i32,
    pub cum_h: i32,
    pub last_text_seq: Option<u8>,
    pub s2c_seq: u32,
}

impl Session {
    pub fn new(peer: SocketAddr, id: u16, keys: SessionKeys) -> Self {
        let now = Instant::now();
        Session {
            peer,
            id,
            keys,
            replay: Replay::new(),
            last_seen: now,
            started: now,
            held_flag: false,
            idle_released: false,
            last_mouse_seq: None,
            cum_x: 0,
            cum_y: 0,
            last_scroll_seq: None,
            cum_v: 0,
            cum_h: 0,
            last_text_seq: None,
            s2c_seq: 1, // 0 is used by WELCOME
        }
    }

    pub fn next_s2c_seq(&mut self) -> u32 {
        let s = self.s2c_seq;
        self.s2c_seq = self.s2c_seq.wrapping_add(1);
        s
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn code_is_six_digits() {
        let c = random_code();
        assert!(c.iter().all(|b| b.is_ascii_digit()));
    }
    #[test]
    fn rate_limit() {
        let mut h = Handshake::new(Some(*b"000000"), true);
        assert!(h.pair_attempt_allowed().is_ok());
        for _ in 0..3 {
            assert!(!h.record_pair_failure());
        }
        assert!(h.pair_attempt_allowed().is_err());
        h.record_pair_success();
        assert!(h.pair_attempt_allowed().is_ok());
    }
    #[test]
    fn offers_expire_and_rotate() {
        let mut h = Handshake::new(None, true);
        let a = h.new_offer_nonce();
        assert!(h.offer_valid(&a));
        for _ in 0..MAX_OFFERS {
            h.new_offer_nonce();
        }
        assert!(!h.offer_valid(&a));
    }
}
