use std::time::Duration;

use keyboardku_server::inject::Injector;
use keyboardku_server::net::{self, Config};
use keyboardku_server::platform;
use keyboardku_server::protocol::PORT;

const USAGE: &str = "KeyboardKu server (Windows host)

USAGE: keyboardku-server [options]
  --port N          UDP port (default 47800)
  --code DDDDDD     fixed 6-digit pairing code (default: random per launch)
  --no-pair         refuse new pairings (only already-paired phones may connect)
  --no-auth         accept pairing without a code (LAN must be trusted)
  --relative        inject raw relative mouse motion (for raw-input games); default is
                    absolute-from-delta, which bypasses Windows pointer acceleration
  --verbose         log every packet with inter-arrival time
  --test-move       move the cursor in a square using SendInput, then exit
  --check-laptop    print WiFi power-save / firewall checks, then exit
  -h, --help        this help
";

fn main() {
    let mut cfg = Config { port: PORT, code: None, pairing_allowed: true, no_auth: false, verbose: false, relative: false };
    let mut test_move = false;
    let mut check_only = false;
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        match a.as_str() {
            "--port" => cfg.port = args.next().and_then(|v| v.parse().ok()).unwrap_or_else(|| die("--port needs a number")),
            "--code" => {
                let v = args.next().unwrap_or_else(|| die("--code needs 6 digits"));
                if v.len() != 6 || !v.bytes().all(|b| b.is_ascii_digit()) {
                    die("--code must be exactly 6 digits");
                }
                let mut c = [0u8; 6];
                c.copy_from_slice(v.as_bytes());
                cfg.code = Some(c);
            }
            "--no-pair" => cfg.pairing_allowed = false,
            "--no-auth" => cfg.no_auth = true,
            "--relative" => cfg.relative = true,
            "--verbose" | "-v" => cfg.verbose = true,
            "--test-move" => test_move = true,
            "--check-laptop" => check_only = true,
            "-h" | "--help" => {
                print!("{USAGE}");
                return;
            }
            other => die(&format!("unknown argument: {other}\n\n{USAGE}")),
        }
    }

    platform::tune_process();

    if check_only {
        platform::print_laptop_checks(cfg.port);
        return;
    }
    if test_move {
        let mut inj = Injector::new(cfg.relative, true);
        println!("Drawing a 200 px square with the cursor in 2 s...");
        std::thread::sleep(Duration::from_secs(2));
        let steps: [(i32, i32); 4] = [(2, 0), (0, 2), (-2, 0), (0, -2)];
        for (dx, dy) in steps {
            for _ in 0..100 {
                inj.mouse_move(dx, dy);
                std::thread::sleep(Duration::from_millis(4));
            }
        }
        inj.scroll(-120, 0);
        inj.scroll(120, 0);
        println!("Done.");
        return;
    }

    if let Err(e) = net::run(cfg) {
        eprintln!("fatal: {e}");
        std::process::exit(1);
    }
}

fn die(msg: &str) -> ! {
    eprintln!("{msg}");
    std::process::exit(2);
}
