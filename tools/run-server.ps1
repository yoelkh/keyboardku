$root = Split-Path -Parent $PSScriptRoot
cargo run --release --bin keyboardku-server --manifest-path (Join-Path $root "server\Cargo.toml") -- @args
