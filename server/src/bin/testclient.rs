//! Laptop-side test client that plays the phone's role against a local server.
//!
//!   testclient [--server 127.0.0.1:47800] [--code DDDDDD] [--type] [--hold-and-vanish]
//!   testclient --vectors        # print protocol test vectors for the Kotlin unit tests
//!   testclient --forget         # forget the stored PSK (forces a new PAIR next run)
//!
//! Flow: DISCOVER -> OFFER -> (PAIR with code | HELLO with stored PSK) -> WELCOME -> RTT -> mouse square
//! -> one scroll notch down/up. With --type (focus Notepad first!): types "hello", unicode, Shift+A held
//! 1.5 s (auto-repeat). With --hold-and-vanish: holds Shift and exits without releasing; the server must
//! release it within ~500 ms (watch for "held-timeout" with --verbose).

use std::net::{SocketAddr, UdpSocket};
use std::time::{Duration, Instant};

use keyboardku_server::crypto::*;
use keyboardku_server::protocol::*;
use keyboardku_server::session::random_bytes;

struct Client {
    sock: UdpSocket,
    server: SocketAddr,
    keys: Option<SessionKeys>,
    session: u16,
    seq: u32,
    cum_x: i32,
    cum_y: i32,
    cum_v: i32,
    cum_h: i32,
    text_seq: u8,
}

impl Client {
    fn send_data(&mut self, ty: u8, payload: &[u8; 8]) {
        let mut p = [0u8; DATA_LEN];
        self.seq += 1;
        write_header(&mut p, ty, self.session, self.seq);
        p[8..16].copy_from_slice(payload);
        self.keys.as_ref().unwrap().seal_c2s(&mut p, self.session, self.seq);
        self.sock.send_to(&p, self.server).unwrap();
    }
    fn mouse(&mut self, dx: i32, dy: i32) {
        self.cum_x = self.cum_x.wrapping_add(dx);
        self.cum_y = self.cum_y.wrapping_add(dy);
        let mut p = [0u8; 8];
        wr_i32(&mut p, 0, self.cum_x);
        wr_i32(&mut p, 4, self.cum_y);
        self.send_data(T_MOUSE, &p);
    }
    fn scroll(&mut self, v: i32, h: i32) {
        self.cum_v = self.cum_v.wrapping_add(v);
        self.cum_h = self.cum_h.wrapping_add(h);
        let mut p = [0u8; 8];
        wr_i32(&mut p, 0, self.cum_v);
        wr_i32(&mut p, 4, self.cum_h);
        self.send_data(T_SCROLL, &p);
    }
    fn keys(&mut self, mods: u8, keys: &[u8]) {
        let mut p = [0u8; 8];
        p[0] = mods;
        for (i, k) in keys.iter().take(6).enumerate() {
            p[1 + i] = *k;
        }
        for _ in 0..3 {
            self.send_data(T_KEYS, &p);
            std::thread::sleep(Duration::from_millis(2));
        }
    }
    fn buttons(&mut self, mask: u8) {
        let mut p = [0u8; 8];
        p[0] = mask;
        for _ in 0..3 {
            self.send_data(T_BUTTONS, &p);
            std::thread::sleep(Duration::from_millis(2));
        }
    }
    fn unicode(&mut self, units: &[u16]) {
        let mut p = [0u8; 8];
        p[0] = units.len().min(3) as u8;
        self.text_seq = self.text_seq.wrapping_add(1);
        p[1] = self.text_seq;
        for (i, u) in units.iter().take(3).enumerate() {
            wr_u16(&mut p, 2 + 2 * i, *u);
        }
        for _ in 0..2 {
            self.send_data(T_UNICODE, &p);
            std::thread::sleep(Duration::from_millis(2));
        }
    }
    fn heartbeat(&mut self, held: bool) -> Option<Duration> {
        let mut p = [0u8; 8];
        let t0 = Instant::now();
        wr_u32(&mut p, 0, 12345);
        p[4] = held as u8;
        self.send_data(T_HEARTBEAT, &p);
        let mut buf = [0u8; 64];
        self.sock.set_read_timeout(Some(Duration::from_millis(500))).unwrap();
        if let Ok((n, _)) = self.sock.recv_from(&mut buf) {
            if n == DATA_LEN {
                if let Some(h) = parse_header(&buf) {
                    if h.ty == T_PONG && self.keys.as_ref().unwrap().open_s2c(&mut buf[..DATA_LEN], self.session, h.seq) {
                        return Some(t0.elapsed());
                    }
                }
            }
        }
        None
    }
    fn release_all(&mut self) {
        self.send_data(T_RELEASE_ALL, &[0u8; 8]);
    }
}

fn type_ascii(c: &mut Client, s: &str) {
    for ch in s.chars() {
        let (usage, mods) = ascii_usage(ch);
        if usage == 0 {
            continue;
        }
        c.keys(mods, &[usage]);
        std::thread::sleep(Duration::from_millis(15));
        c.keys(0, &[]);
        std::thread::sleep(Duration::from_millis(15));
    }
}

fn ascii_usage(ch: char) -> (u8, u8) {
    match ch {
        'a'..='z' => (0x04 + (ch as u8 - b'a'), 0),
        'A'..='Z' => (0x04 + (ch as u8 - b'A'), 0x02),
        '1'..='9' => (0x1E + (ch as u8 - b'1'), 0),
        '0' => (0x27, 0),
        ' ' => (0x2C, 0),
        '\n' => (0x28, 0),
        '.' => (0x37, 0),
        ',' => (0x36, 0),
        '!' => (0x1E, 0x02),
        _ => (0, 0),
    }
}

fn psk_path() -> std::path::PathBuf {
    std::env::temp_dir().join("keyboardku-testclient.psk")
}

fn main() {
    let mut server: SocketAddr = "127.0.0.1:47800".parse().unwrap();
    let mut code: Option<[u8; 6]> = None;
    let mut vanish = false;
    let mut typing = false;
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        match a.as_str() {
            "--server" => server = args.next().unwrap().parse().expect("addr"),
            "--code" => {
                let v = args.next().unwrap();
                let mut c = [0u8; 6];
                c.copy_from_slice(v.as_bytes());
                code = Some(c);
            }
            "--hold-and-vanish" => vanish = true,
            "--type" => typing = true,
            "--vectors" => {
                print_vectors();
                return;
            }
            "--forget" => {
                let _ = std::fs::remove_file(psk_path());
                println!("forgot stored PSK");
                return;
            }
            o => panic!("unknown arg {o}"),
        }
    }

    let sock = UdpSocket::bind("0.0.0.0:0").unwrap();
    sock.set_read_timeout(Some(Duration::from_secs(2))).unwrap();

    // DISCOVER
    let mut d = [0u8; DISCOVER_LEN];
    write_header(&mut d, T_DISCOVER, 0, 0);
    sock.send_to(&d, server).unwrap();
    let mut buf = [0u8; 64];
    let (n, from) = sock.recv_from(&mut buf).expect("no OFFER (is the server running?)");
    assert_eq!(n, OFFER_LEN, "bad OFFER length");
    let h = parse_header(&buf).unwrap();
    assert_eq!(h.ty, T_OFFER);
    let mut nonce_s = [0u8; 8];
    nonce_s.copy_from_slice(&buf[8..16]);
    let ver = rd_u16(&buf, 16);
    let flags = rd_u16(&buf, 18);
    let name_end = buf[20..52].iter().position(|&b| b == 0).unwrap_or(32);
    let name = String::from_utf8_lossy(&buf[20..20 + name_end]).to_string();
    println!("OFFER from {from}: name='{name}' ver={ver} flags={flags:#x}");

    // peer id + PSK (persisted between runs)
    let stored = std::fs::read(psk_path()).ok().filter(|v| v.len() == 40);
    let (peer_id, psk, do_pair) = match (&stored, code) {
        (Some(v), None) => {
            let mut id = [0u8; 8];
            let mut k = [0u8; 32];
            id.copy_from_slice(&v[..8]);
            k.copy_from_slice(&v[8..]);
            (id, k, false)
        }
        _ => {
            let mut id = [0u8; 8];
            random_bytes(&mut id);
            (id, [0u8; 32], true)
        }
    };
    let mut nonce_c = [0u8; 8];
    random_bytes(&mut nonce_c);

    let mut hello = [0u8; HELLO_LEN];
    let psk = if do_pair {
        let code = code.unwrap_or_else(|| {
            if flags & OFFER_FLAG_NO_CODE != 0 {
                *b"000000"
            } else {
                panic!("first run: pass --code DDDDDD (printed by the server)")
            }
        });
        let k0 = pair_k0(&code, &nonce_s, &nonce_c);
        write_header(&mut hello, T_PAIR, 0, 0);
        hello[8..16].copy_from_slice(&nonce_s);
        hello[16..24].copy_from_slice(&nonce_c);
        hello[24..32].copy_from_slice(&peer_id);
        hello[32..48].copy_from_slice(&pair_mac(&k0, &nonce_s, &nonce_c, &peer_id));
        psk_from_k0(&k0)
    } else {
        write_header(&mut hello, T_HELLO, 0, 0);
        hello[8..16].copy_from_slice(&nonce_s);
        hello[16..24].copy_from_slice(&nonce_c);
        hello[24..32].copy_from_slice(&peer_id);
        hello[32..48].copy_from_slice(&hello_mac(&psk, &nonce_s, &nonce_c, &peer_id));
        psk
    };
    sock.send_to(&hello, server).unwrap();
    let (n, _) = sock.recv_from(&mut buf).expect("no WELCOME/REJECT");
    let h = parse_header(&buf[..n]).unwrap();
    if h.ty == T_REJECT {
        println!("REJECT reason={} retryAfter={} ms", buf[8], rd_u16(&buf, 9));
        std::process::exit(1);
    }
    assert_eq!(h.ty, T_WELCOME);
    let keys = SessionKeys::derive(&psk, &nonce_s, &nonce_c);
    assert!(keys.open_s2c(&mut buf[..DATA_LEN], h.session, 0), "WELCOME failed to decrypt");
    let sw = rd_u16(&buf, 12);
    let sh = rd_u16(&buf, 14);
    println!("WELCOME session={:#06x} proto={} screen={}x{}", h.session, rd_u16(&buf, 8), sw, sh);
    if do_pair {
        let mut v = Vec::with_capacity(40);
        v.extend_from_slice(&peer_id);
        v.extend_from_slice(&psk);
        std::fs::write(psk_path(), v).unwrap();
        println!("paired; PSK stored at {}", psk_path().display());
    }

    let mut c = Client {
        sock,
        server,
        keys: Some(keys),
        session: h.session,
        seq: 0,
        cum_x: 0,
        cum_y: 0,
        cum_v: 0,
        cum_h: 0,
        text_seq: 0,
    };

    match c.heartbeat(false) {
        Some(rtt) => println!("RTT {:?}", rtt),
        None => println!("no PONG!"),
    }

    println!("mouse square...");
    for (dx, dy) in [(2, 0), (0, 2), (-2, 0), (0, -2)] {
        for _ in 0..100 {
            c.mouse(dx, dy);
            std::thread::sleep(Duration::from_millis(3));
        }
    }
    println!("scroll one notch down, then up...");
    c.scroll(-120, 0);
    std::thread::sleep(Duration::from_millis(200));
    c.scroll(120, 0);

    if typing {
        println!("typing (focus a text editor)...");
        std::thread::sleep(Duration::from_millis(500));
        type_ascii(&mut c, "hello from KeyboardKu ");
        c.unicode(&"é".encode_utf16().collect::<Vec<_>>());
        std::thread::sleep(Duration::from_millis(20));
        c.unicode(&"😀".encode_utf16().collect::<Vec<_>>());
        std::thread::sleep(Duration::from_millis(20));
        println!("holding Shift+A for 1.5 s (auto-repeat should appear)...");
        c.keys(0x02, &[0x04]);
        let t = Instant::now();
        while t.elapsed() < Duration::from_millis(1500) {
            c.heartbeat(true);
            std::thread::sleep(Duration::from_millis(100));
        }
        c.keys(0, &[]);
        type_ascii(&mut c, "
");
    }

    if vanish {
        println!("holding Shift and vanishing; server must release it within ~500 ms");
        c.keys(0x02, &[]);
        c.heartbeat(true);
        return;
    }
    c.buttons(0);
    c.release_all();
    println!("done");
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Deterministic vectors mirrored by android/app/src/test/.../UdpProtocolTest.kt
fn print_vectors() {
    let code = *b"123456";
    let nonce_s = [0x11u8; 8];
    let nonce_c = [0x22u8; 8];
    let peer_id = [0x33u8; 8];
    let k0 = pair_k0(&code, &nonce_s, &nonce_c);
    let psk = psk_from_k0(&k0);
    println!("code=123456 nonceS={} nonceC={} peerId={}", hex(&nonce_s), hex(&nonce_c), hex(&peer_id));
    println!("k0={}", hex(&k0));
    println!("psk={}", hex(&psk));
    println!("pairMac={}", hex(&pair_mac(&k0, &nonce_s, &nonce_c, &peer_id)));
    println!("helloMac={}", hex(&hello_mac(&psk, &nonce_s, &nonce_c, &peer_id)));
    let keys = SessionKeys::derive(&psk, &nonce_s, &nonce_c);
    let mut p = [0u8; DATA_LEN];
    write_header(&mut p, T_MOUSE, 0x0102, 7);
    wr_i32(&mut p, 8, -5);
    wr_i32(&mut p, 12, 9);
    keys.seal_c2s(&mut p, 0x0102, 7);
    println!("mouseC2S(session=0x0102,seq=7,cumX=-5,cumY=9)={}", hex(&p));
    let mut q = [0u8; DATA_LEN];
    write_header(&mut q, T_PONG, 0x0102, 3);
    wr_u32(&mut q, 8, 1000);
    wr_u32(&mut q, 12, 2000);
    keys.seal_s2c(&mut q, 0x0102, 3);
    println!("pongS2C(session=0x0102,seq=3,echo=1000,uptime=2000)={}", hex(&q));
}
