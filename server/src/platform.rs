//! Windows process/thread tuning, host name, LAN addresses, laptop checks.

use std::net::{Ipv4Addr, UdpSocket};
use std::process::Command;

use windows_sys::Win32::System::Threading::{
    GetCurrentProcess, GetCurrentThread, SetPriorityClass, SetProcessInformation, SetThreadPriority,
    HIGH_PRIORITY_CLASS, PROCESS_POWER_THROTTLING_CURRENT_VERSION, PROCESS_POWER_THROTTLING_EXECUTION_SPEED,
    PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION, PROCESS_POWER_THROTTLING_STATE, THREAD_PRIORITY_TIME_CRITICAL,
};

/// Raise priority and opt out of Windows 11 EcoQoS / timer-resolution throttling for a hidden console process.
pub fn tune_process() {
    unsafe {
        SetPriorityClass(GetCurrentProcess(), HIGH_PRIORITY_CLASS);
        SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL);
        let state = PROCESS_POWER_THROTTLING_STATE {
            Version: PROCESS_POWER_THROTTLING_CURRENT_VERSION,
            ControlMask: PROCESS_POWER_THROTTLING_EXECUTION_SPEED | PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION,
            StateMask: 0,
        };
        // ProcessPowerThrottling = 4
        SetProcessInformation(
            GetCurrentProcess(),
            4,
            &state as *const _ as *const core::ffi::c_void,
            core::mem::size_of::<PROCESS_POWER_THROTTLING_STATE>() as u32,
        );
    }
}

pub fn host_name() -> String {
    std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows".to_string())
}

/// Primary LAN IPv4 via the UDP-connect trick (no packet is sent).
pub fn primary_ipv4() -> Option<Ipv4Addr> {
    let s = UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect("8.8.8.8:80").ok()?;
    match s.local_addr().ok()? {
        std::net::SocketAddr::V4(a) => Some(*a.ip()),
        _ => None,
    }
}

/// All IPv4 addresses reported by `ipconfig` (best effort, startup only).
pub fn all_ipv4() -> Vec<String> {
    let mut out = Vec::new();
    if let Ok(o) = Command::new("ipconfig").output() {
        let text = String::from_utf8_lossy(&o.stdout);
        for line in text.lines() {
            let l = line.trim();
            if l.starts_with("IPv4") {
                if let Some(idx) = l.rfind(": ") {
                    let ip = l[idx + 2..].trim();
                    if !ip.starts_with("127.") && !ip.starts_with("169.254.") {
                        out.push(ip.to_string());
                    }
                }
            }
        }
    }
    out
}

/// Reads the wireless adapter "Power Saving Mode" of the active power scheme (0 = Maximum Performance).
/// Returns (ac_index, dc_index) or None if it could not be read.
pub fn wifi_power_saving_indexes() -> Option<(u32, u32)> {
    let o = Command::new("powercfg").arg("/getactivescheme").output().ok()?;
    let s = String::from_utf8_lossy(&o.stdout);
    let guid_start = s.find("GUID: ")? + 6;
    let guid: String = s[guid_start..].chars().take_while(|c| c.is_ascii_hexdigit() || *c == '-').collect();
    let o = Command::new("powercfg")
        .args(["/q", &guid, "19cbb8fa-5279-450e-9fac-8a3d5fedd0c1", "12bbebe6-58d6-4636-95bb-3217ef867c1a"])
        .output()
        .ok()?;
    let s = String::from_utf8_lossy(&o.stdout);
    let mut ac = None;
    let mut dc = None;
    for line in s.lines() {
        let l = line.trim();
        if let Some(idx) = l.find("0x") {
            let v = u32::from_str_radix(l[idx + 2..].trim(), 16).ok();
            if l.contains("AC") && ac.is_none() {
                ac = v;
            } else if l.contains("DC") && dc.is_none() {
                dc = v;
            }
        }
    }
    Some((ac?, dc?))
}

pub fn firewall_rule_present(port: u16) -> Option<bool> {
    let name = format!("KeyboardKu UDP {port}");
    let o = Command::new("netsh")
        .args(["advfirewall", "firewall", "show", "rule", &format!("name={name}")])
        .output()
        .ok()?;
    let s = String::from_utf8_lossy(&o.stdout);
    Some(s.contains("Enabled:") || s.contains("Diaktifkan:"))
}

pub fn print_laptop_checks(port: u16) {
    match wifi_power_saving_indexes() {
        Some((ac, dc)) => {
            let ok = ac == 0 && dc == 0;
            println!(
                "WiFi adapter power saving: AC={} DC={}  {}",
                mode_name(ac),
                mode_name(dc),
                if ok { "OK" } else { "-> run tools\\laptop-tune.ps1 as Administrator (Maximum Performance removes 50-100 ms buffering)" }
            );
        }
        None => println!("WiFi adapter power saving: could not read (powercfg)"),
    }
    match firewall_rule_present(port) {
        Some(true) => println!("Firewall inbound rule for UDP {port}: OK"),
        Some(false) => println!("Firewall inbound rule for UDP {port}: MISSING -> accept the Windows Firewall prompt (Private networks) or run tools\\laptop-tune.ps1"),
        None => println!("Firewall rule check skipped"),
    }
}

fn mode_name(i: u32) -> &'static str {
    match i {
        0 => "Maximum Performance",
        1 => "Low Power Saving",
        2 => "Medium Power Saving",
        3 => "Maximum Power Saving",
        _ => "?",
    }
}
