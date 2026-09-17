//! Single-threaded UDP loop: discovery, handshake, encrypted data dispatch, watchdog and key-repeat ticks.

use std::net::{SocketAddr, UdpSocket};
use std::os::windows::io::AsRawSocket;
use std::time::{Duration, Instant};

use crate::crypto::{ct_eq16, hello_mac, pair_k0, pair_mac, psk_from_k0, SessionKeys};
use crate::inject::Injector;
use crate::platform;
use crate::protocol::*;
use crate::session::{random_bytes, Handshake, PeerStore, Session};

pub struct Config {
    pub port: u16,
    pub code: Option<[u8; 6]>,
    pub pairing_allowed: bool,
    pub no_auth: bool,
    pub verbose: bool,
    pub relative: bool,
}

const HELD_TIMEOUT: Duration = Duration::from_millis(500);
const IDLE_TIMEOUT: Duration = Duration::from_secs(6);

/// Windows: an ICMP port-unreachable from the peer would otherwise surface as WSAECONNRESET on recv.
fn disable_udp_connreset(sock: &UdpSocket) {
    use windows_sys::Win32::Networking::WinSock::{WSAIoctl, SIO_UDP_CONNRESET};
    let mut enable: u32 = 0;
    let mut ret: u32 = 0;
    unsafe {
        WSAIoctl(
            sock.as_raw_socket() as usize,
            SIO_UDP_CONNRESET,
            &mut enable as *mut u32 as *mut core::ffi::c_void,
            4,
            core::ptr::null_mut(),
            0,
            &mut ret,
            core::ptr::null_mut(),
            None,
        );
    }
}

fn print_code(hs: &Handshake) {
    let c = std::str::from_utf8(&hs.code).unwrap_or("??????");
    println!();
    println!("  ==========================================");
    println!("   PAIRING CODE:   {} {} {}  {} {} {}", &c[0..1], &c[1..2], &c[2..3], &c[3..4], &c[4..5], &c[5..6]);
    println!("  ==========================================");
    println!();
}

pub fn run(cfg: Config) -> std::io::Result<()> {
    let sock = UdpSocket::bind(("0.0.0.0", cfg.port))?;
    disable_udp_connreset(&sock);
    let mut store = PeerStore::load();
    // --no-auth: fixed all-zero code, advertised to phones so they skip the code prompt.
    let mut hs = Handshake::new(if cfg.no_auth { Some(*b"000000") } else { cfg.code }, cfg.pairing_allowed);
    let mut inj = Injector::new(cfg.relative, cfg.verbose);
    let mut session: Option<Session> = None;
    let name = platform::host_name();
    let start = Instant::now();

    println!("KeyboardKu server '{name}' listening on UDP {}", cfg.port);
    let ips = platform::all_ipv4();
    if ips.is_empty() {
        if let Some(ip) = platform::primary_ipv4() {
            println!("LAN address: {ip}");
        }
    } else {
        println!("LAN addresses: {}", ips.join(", "));
    }
    println!("Known paired phones: {} ({})", store.len(), store.path().display());
    if cfg.no_auth {
        println!("WARNING: --no-auth: any phone on the network can pair without a code.");
    }
    if cfg.relative {
        println!(
            "Mouse mode: relative (raw). Enhance pointer precision is {}.",
            if crate::inject::enhance_pointer_precision() { "ON (Windows will accelerate)" } else { "off" }
        );
    } else {
        println!("Mouse mode: absolute-from-delta (Windows pointer acceleration bypassed).");
    }
    platform::print_laptop_checks(cfg.port);
    if hs.pairing_allowed && !cfg.no_auth {
        print_code(&hs);
    }

    let mut buf = [0u8; 64];
    let mut out = [0u8; 64];
    let mut last_pkt_at = Instant::now();

    loop {
        // --- compute how long we may block ---
        let now = Instant::now();
        let mut deadline = now + Duration::from_secs(1);
        if let Some(t) = inj.next_deadline() {
            deadline = deadline.min(t);
        }
        if let Some(s) = &session {
            if s.held_flag {
                deadline = deadline.min(s.last_seen + HELD_TIMEOUT);
            } else if !s.idle_released {
                deadline = deadline.min(s.last_seen + IDLE_TIMEOUT);
            }
        }
        let wait = deadline.saturating_duration_since(now).max(Duration::from_millis(1));
        sock.set_read_timeout(Some(wait))?;

        match sock.recv_from(&mut buf) {
            Ok((n, from)) => {
                let now = Instant::now();
                if let Some(h) = parse_header(&buf[..n]) {
                    match (h.ty, n) {
                        (T_DISCOVER, DISCOVER_LEN) => {
                            let nonce = hs.new_offer_nonce();
                            let mut flags = if hs.pairing_allowed { OFFER_FLAG_PAIRING_ALLOWED } else { 0 };
                            if cfg.no_auth {
                                flags |= OFFER_FLAG_NO_CODE;
                            }
                            let mut o = [0u8; OFFER_LEN];
                            build_offer(&mut o, &nonce, flags, &name);
                            let _ = sock.send_to(&o, from);
                            if cfg.verbose {
                                println!("DISCOVER from {from} -> OFFER");
                            }
                        }
                        (T_HELLO, HELLO_LEN) | (T_PAIR, HELLO_LEN) => {
                            handle_handshake(&cfg, &sock, &mut store, &mut hs, &mut inj, &mut session, &buf[..n], from, h.ty);
                        }
                        (_, DATA_LEN) => {
                            if let Some(s) = session.as_mut() {
                                if from == s.peer && h.session == s.id && s.replay.accept(h.seq) {
                                    if s.keys.open_c2s(&mut buf[..DATA_LEN], s.id, h.seq) {
                                        if cfg.verbose {
                                            let gap = now.duration_since(last_pkt_at).as_micros();
                                            println!("{:>8} us  type={:#04x} seq={}", gap, h.ty, h.seq);
                                        }
                                        last_pkt_at = now;
                                        handle_data(&sock, s, &mut inj, h, &buf[8..16], start, &mut out);
                                        s.last_seen = now;
                                        s.idle_released = false;
                                        s.held_flag = inj.any_held() || s.held_flag;
                                    } else if cfg.verbose {
                                        println!("bad tag from {from} (seq {})", h.seq);
                                    }
                                }
                            }
                        }
                        _ => {}
                    }
                }
            }
            Err(e) => {
                let k = e.kind();
                if k != std::io::ErrorKind::WouldBlock && k != std::io::ErrorKind::TimedOut {
                    // ConnectionReset should be disabled; log anything else and keep going
                    if cfg.verbose {
                        println!("recv error: {e}");
                    }
                }
            }
        }

        // --- watchdog + key repeat ---
        let now = Instant::now();
        inj.tick(now);
        if let Some(s) = session.as_mut() {
            let since = now.duration_since(s.last_seen);
            if s.held_flag && since > HELD_TIMEOUT {
                if cfg.verbose {
                    println!("held-timeout: releasing all");
                }
                inj.release_all();
                s.held_flag = false;
            }
            if !s.idle_released && since > IDLE_TIMEOUT {
                inj.release_all();
                s.idle_released = true;
                s.held_flag = false;
            }
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn handle_handshake(
    cfg: &Config,
    sock: &UdpSocket,
    store: &mut PeerStore,
    hs: &mut Handshake,
    inj: &mut Injector,
    session: &mut Option<Session>,
    b: &[u8],
    from: SocketAddr,
    ty: u8,
) {
    let mut nonce_s = [0u8; 8];
    let mut nonce_c = [0u8; 8];
    let mut peer_id = [0u8; 8];
    nonce_s.copy_from_slice(&b[8..16]);
    nonce_c.copy_from_slice(&b[16..24]);
    peer_id.copy_from_slice(&b[24..32]);
    let mac = &b[32..48];
    let mut rej = [0u8; REJECT_LEN];

    if !hs.offer_valid(&nonce_s) {
        // stale/unknown nonce: make the phone re-DISCOVER
        build_reject(&mut rej, REJ_BAD_MAC, 0);
        let _ = sock.send_to(&rej, from);
        if cfg.verbose {
            println!("handshake from {from}: unknown/expired nonceS");
        }
        return;
    }

    let psk: [u8; 32] = if ty == T_HELLO {
        match store.find(&peer_id) {
            Some(k) if ct_eq16(&hello_mac(k, &nonce_s, &nonce_c, &peer_id), mac) => *k,
            Some(_) => {
                build_reject(&mut rej, REJ_BAD_MAC, 0);
                let _ = sock.send_to(&rej, from);
                if cfg.verbose {
                    println!("HELLO from {from}: bad mac");
                }
                return;
            }
            None => {
                build_reject(&mut rej, REJ_UNKNOWN_PEER, 0);
                let _ = sock.send_to(&rej, from);
                if cfg.verbose {
                    println!("HELLO from {from}: unknown peer -> asking for PAIR");
                }
                return;
            }
        }
    } else {
        // PAIR
        if !hs.pairing_allowed {
            build_reject(&mut rej, REJ_PAIRING_DISABLED, 0);
            let _ = sock.send_to(&rej, from);
            return;
        }
        if let Err(retry) = hs.pair_attempt_allowed() {
            build_reject(&mut rej, REJ_RATE_LIMITED, retry);
            let _ = sock.send_to(&rej, from);
            return;
        }
        let k0 = pair_k0(&hs.code, &nonce_s, &nonce_c);
        let ok = ct_eq16(&pair_mac(&k0, &nonce_s, &nonce_c, &peer_id), mac);
        if !ok {
            let rotated = hs.record_pair_failure();
            build_reject(&mut rej, REJ_BAD_MAC, 0);
            let _ = sock.send_to(&rej, from);
            println!("PAIR from {from}: wrong code");
            if rotated {
                println!("Too many failures: pairing code rotated.");
                print_code(hs);
            }
            return;
        }
        hs.record_pair_success();
        let psk = psk_from_k0(&k0);
        store.insert(peer_id, psk);
        println!("Paired new phone ({}). Stored in {}", hex8(&peer_id), store.path().display());
        psk
    };

    // New session (replaces any existing one)
    if session.is_some() {
        inj.release_all();
    }
    let mut idb = [0u8; 2];
    let mut id = 0u16;
    while id == 0 {
        random_bytes(&mut idb);
        id = u16::from_le_bytes(idb);
    }
    let keys = SessionKeys::derive(&psk, &nonce_s, &nonce_c);
    let s = Session::new(from, id, keys);

    // WELCOME (encrypted, s2c seq 0)
    let mut w = [0u8; DATA_LEN];
    write_header(&mut w, T_WELCOME, id, 0);
    wr_u16(&mut w, 8, VERSION);
    wr_u16(&mut w, 10, 0);
    let (sw, sh) = virtual_screen_size();
    wr_u16(&mut w, 12, sw);
    wr_u16(&mut w, 14, sh);
    s.keys.seal_s2c(&mut w, id, 0);
    let _ = sock.send_to(&w, from);
    println!("Session {id:#06x} with {from} ({})", if ty == T_HELLO { "HELLO" } else { "PAIR" });
    *session = Some(s);
}

fn handle_data(sock: &UdpSocket, s: &mut Session, inj: &mut Injector, h: Header, p: &[u8], start: Instant, out: &mut [u8; 64]) {
    match h.ty {
        T_MOUSE => {
            if s.last_mouse_seq.map_or(true, |l| seq_newer(h.seq, l)) {
                let cx = rd_i32(p, 0);
                let cy = rd_i32(p, 4);
                let dx = cx.wrapping_sub(s.cum_x);
                let dy = cy.wrapping_sub(s.cum_y);
                s.cum_x = cx;
                s.cum_y = cy;
                s.last_mouse_seq = Some(h.seq);
                inj.mouse_move(dx, dy);
            }
        }
        T_SCROLL => {
            if s.last_scroll_seq.map_or(true, |l| seq_newer(h.seq, l)) {
                let cv = rd_i32(p, 0);
                let ch = rd_i32(p, 4);
                let dv = cv.wrapping_sub(s.cum_v);
                let dh = ch.wrapping_sub(s.cum_h);
                s.cum_v = cv;
                s.cum_h = ch;
                s.last_scroll_seq = Some(h.seq);
                inj.scroll(dv, dh);
            }
        }
        T_BUTTONS => inj.buttons_state(p[0]),
        T_KEYS => {
            let mut keys = [0u8; 6];
            keys.copy_from_slice(&p[1..7]);
            inj.keys_state(p[0], &keys);
        }
        T_UNICODE => {
            let count = (p[0] as usize).clamp(1, 3);
            let tseq = p[1];
            let fresh = match s.last_text_seq {
                None => true,
                Some(l) => (tseq.wrapping_sub(l) as i8) > 0,
            };
            if fresh {
                s.last_text_seq = Some(tseq);
                let mut units = [0u16; 3];
                for i in 0..count {
                    units[i] = rd_u16(p, 2 + 2 * i);
                }
                inj.unicode(&units[..count]);
            }
        }
        T_CONSUMER => inj.consumer_state(rd_u16(p, 0)),
        T_HEARTBEAT => {
            let echo = rd_u32(p, 0);
            s.held_flag = p[4] != 0 || inj.any_held();
            let seq = s.next_s2c_seq();
            let pkt = &mut out[..DATA_LEN];
            pkt.fill(0);
            write_header(pkt, T_PONG, s.id, seq);
            wr_u32(pkt, 8, echo);
            wr_u32(pkt, 12, start.elapsed().as_millis() as u32);
            s.keys.seal_s2c(pkt, s.id, seq);
            let _ = sock.send_to(pkt, s.peer);
        }
        T_RELEASE_ALL => {
            inj.release_all();
            s.held_flag = false;
        }
        _ => {}
    }
}

fn virtual_screen_size() -> (u16, u16) {
    use windows_sys::Win32::UI::WindowsAndMessaging::{GetSystemMetrics, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN};
    unsafe {
        (
            GetSystemMetrics(SM_CXVIRTUALSCREEN).clamp(0, 65535) as u16,
            GetSystemMetrics(SM_CYVIRTUALSCREEN).clamp(0, 65535) as u16,
        )
    }
}

fn hex8(b: &[u8; 8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}
