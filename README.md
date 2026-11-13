# gentle connection

A calm, all-in-one Android **launcher** for an aging parent whose memory is fading.
It replaces the home screen with one quiet surface where she can always:

- **talk** to the people she loves (tap a face → it calls),
- **read messages and react** to them with one warm tap (a heart, a thumbs-up — no typing needed),
- **read her post** (email), quietly and safely — read-only.

The underlying messengers are **invisible**. A message is a message and a person is a
person; she never has to know or choose which app it came from.

Built as a sibling to [`gentle phone`](../Simple-Phone2) — same philosophy (large targets,
high contrast, caregiver-configured from afar), broadened from *calls only* to a full
connection hub.

## The one screen — "Zuhause" (Home)

The launcher itself is the whole app. Top to bottom:

1. **Time & date** — big, always readable; the thing she checks anyway.
2. **A single nudge** — at most one gentle prompt (“Lisa hat geschrieben”), never a wall.
3. **Favorite faces** — big photos; tap one to **call** that person.
4. **Unread messages** — unified across all messengers, each answerable with a big reaction.
5. **Unread mail** — read-only, clearly marked.

No voice messages. No app pickers. No menus to get lost in.

## Messaging & Calling Architecture

Threema and WhatsApp run directly on the device as native subprocesses cross-compiled into the APK: a patched `libthreema` (Rust) holding her identity as its primary device, and `whatsmeow` (Go) running as a WhatsApp companion — see `docs/in-app-bridges.md`. SMS is read on-device, and the app holds both the default dialler and SMS roles. The Firestore listener in `data/BridgeMessageRepository.kt` remains wired up and consumed; whether a server-side bridge is still writing to `devices/{deviceId}/messages` is a question this repository does not answer. Calling defaults to cellular (`ACTION_CALL`), but automatically routes through Threema calls when the device is roaming to avoid roaming fees.

## Caregiver Remote Configuration & Setup

She never sets anything up. A **caregiver** pairs the phone and configures favorites, text size, dark mode, and active channels remotely via the Caregiver Web Portal (`caregiver-portal/`).

- **Pairing:** Tap the clock on the phone 5 times within 1.5 seconds to display the 6-character pairing code, then enter it in the caregiver portal.
- **Firebase Deployment Guide:** Follow the step-by-step owner guide in [`docs/firebase-setup.md`](docs/firebase-setup.md) to set up Firestore, Authentication, security rules, and hosting.

## Status

See [`SPEC.md`](SPEC.md) for the full build brief.
Implementation is being done by an automated coding agent (agy).

> Note: this repo cannot be compiled on the aarch64 host it lives on (no aarch64 `aapt2`);
> APK builds happen in CI. Code is written to compile cleanly, not verified by local build.
