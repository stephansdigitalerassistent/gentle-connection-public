# Vendored Patches for Threema Bridge & libthreema

This directory and the local `libthreema` commits document the patches required for `threema-gemini-bridge` and `libthreema` to function correctly.

## 1. `patches/blake2` Crate Patch
- **Target**: `blake2` crate (backported to tag `blake2-v0.10.6`).
- **Source**: `https://github.com/threema-theo/hashes/tree/blake2b-keyless`
- **Purpose**: Generates non-keyed hashes with parameters (see RustCrypto/hashes issue #482).
- **Why it matters**: `libthreema` uses BLAKE2b keyless parameter hashing for key derivation. Without this `[patch.crates-io]` in `Cargo.toml`, key derivation fails to compile or run.

## 2. `libthreema` DesyncError Patch (Group Messages)
- **Location**: `libthreema/lib/src/csp/e2e/state.rs` (around line 955 in `State::handle_message`).
- **Patch description**:
  Upstream `libthreema` drops group message bodies (`IncomingMessageBody::Group(_)`) by returning a fatal `CspE2eProtocolError::DesyncError("Ain't nobody got time to implement handling group messages")`.
- **Fix applied**:
  Group message bodies are passed to the conversation provider (`context.conversations.borrow_mut().add_or_update_incoming_message(message.inner.clone())`). Group state management is handled upstream by the listener / bridge caller.
- **Why it matters**: An upstream sync or re-import of `libthreema` that quietly overwrites `state.rs` will cause every incoming group message to fail with `DesyncError` and immediately drop the connection.
