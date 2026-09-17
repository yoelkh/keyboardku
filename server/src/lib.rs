//! KeyboardKu Windows host library: protocol, crypto, injection, session and the UDP loop.
//! Used by both the `keyboardku-server` and `testclient` binaries.

pub mod crypto;
pub mod inject;
pub mod keymap;
pub mod net;
pub mod platform;
pub mod protocol;
pub mod session;
