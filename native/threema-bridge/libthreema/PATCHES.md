# Local modifications to `libthreema`

This is a **vendored, patched** copy of Threema's `libthreema`. It is not pristine upstream.

Every item below is load-bearing, and every one of them fails *silently* if it is lost —
nothing crashes, nothing turns red, the app simply stops doing something it used to do.
So: **do not replace this directory with an upstream checkout.** If you need to update it,
re-apply each patch below and verify each one individually.

Protocol version at time of vendoring: `threema-protocols/version.txt` = 23.

---

## 1. `blake2` crate patch

`../Cargo.toml` carries `[patch.crates-io] blake2 = { path = "patches/blake2" }`, pointing at
`patches/blake2` (also mirrored at `libthreema/patches/blake2`).

Upstream `blake2` does not expose what `libthreema` needs for the Threema key derivations.
Without this patch the crate does not build at all — which at least fails loudly, making this
the least dangerous of the three.

## 2. Forward-security envelope: continue instead of erroring

`lib/src/csp_e2e/message/task/incoming.rs`, search for `"Received FS envelope (Manual Patch:
Continuing anyway)"`.

Upstream treats an FS (forward security) envelope as unhandled. The patch logs it and
continues processing the inner message.

**If lost:** messages from contacts using forward security stop arriving. Not an error — they
simply never appear. Since forward security is on by default in current Threema clients, this
is close to "the app stops receiving messages" while looking perfectly healthy.

Related: group handling depends on this path plus `AddContacts` seeding on each handshake.

## 3. `identity/set_featuremask` support

- `lib/src/https/directory.rs` — added `SetFeatureMaskPhase1Request`,
  `SetFeatureMaskPhase2Request`, `set_feature_mask_phase1_request()`,
  `set_feature_mask_phase2_request()`, `handle_set_feature_mask_phase2_result()`, and widened
  `AuthenticationChallengeResponse` / `handle_authentication_challenge()` from `pub(crate)`
  to `pub` so the bridge can drive the two-phase flow.
- `lib/src/common/config.rs` — added `set_feature_mask_path()` returning
  `identity/set_featuremask`.

Upstream `libthreema` can only *fetch* other identities (`request_identities`); it has no way
to update our own feature mask. The bridge needs it to advertise
`O2O_AUDIO_CALL_SUPPORT (0x0010)`.

The challenge/response crypto itself is **upstream, not patched** —
`ClientKey::derive_directory_authentication_key` in `lib/src/common/keys.rs` already
implements it (X25519 shared secret, then Blake2b-MAC-256 with salt `dir` and personal
`3ma-csp`, then a second MAC over the token). It matches Threema for Android's
`APIConnector.calcTokenResponse`. Do not reimplement it.

**If lost:** her identity never advertises audio-call support, so her contacts' Threema apps
never show a call button for her. Every line of the VoIP code would be correct and the feature
would simply not exist. The bridge logs a failure here at `error` level and carries on by
design — a feature bit should not stop her messages — so the only evidence is a log line
nobody reads.

Reference: `<treema-light>/.../protocol/api/APIConnector.java:842` (`setFeatureMask`)
and `:874` (`checkFeatureMask`, useful for verifying the mask actually took without guessing).

## 4. `identity/turn_cred` support

- `lib/src/https/directory.rs` — added `ObtainTurnServersPhase1Request`,
  `ObtainTurnServersPhase2Request`, `TurnServerResponse`,
  `obtain_turn_servers_phase1_request()`, `obtain_turn_servers_phase2_request()`,
  `handle_obtain_turn_servers_phase2_result()`.
- `lib/src/common/config.rs` — added `turn_cred_path()` returning `identity/turn_cred`.

Used by the bridge to fetch temporary TURN server credentials from Threema's directory server
authenticated with her identity over the existing two-phase challenge/response flow.

## 5. Accept group messages from unseeded contacts

`lib/src/csp_e2e/message/task/incoming.rs`, search for `"Local patch: Upstream libthreema discards group messages"`.

Upstream `libthreema` discards group messages (`GroupText`, etc.) when the sender is not already in the in-memory contact database, because group messages have `requires_direct_contact = false`. In this embedded bridge, the contact database starts empty in memory on restart, so group messages would be discarded until seeded or replied to. The patch permits creating the contact via `CreateContact` directory lookup for all incoming messages.


