# In-app bridges: Threema and WhatsApp, both on the device

Status: **Stage 1 implemented. Stage 3 built and CI-green, not yet verified on a device.
Stage 2 not started. Stage 4 Layer 2 done, Layers 1 and 3 not started.**

The rest of this document was written as a specification and still reads like one. Where it
says "do this", check whether it has already been done — Stage 3 in particular is now code, not
a plan, and the sections below have been annotated where reality has overtaken them.

Three pieces of work that all move messenger handling *onto the device* and away from any
server. (Notification scraping, the third thing they moved away from, was removed outright on
2026-07-30.)

1. **Threema data-backup import** — a one-time import of her existing Threema backup, giving
   the app real names, Threema IDs and photos for the people she talks to.
2. **WhatsApp via Baileys, embedded in the app** — a Node runtime inside the APK running a
   WhatsApp multi-device companion session.
3. **Threema client, embedded in the app** — `libthreema` cross-compiled for Android, holding
   her Threema ID as its primary device, replacing both the Threema app and the server-side
   bridge.

Stage 1 is self-contained and genuinely testable. Stages 2 and 3 are large lifts that the
current CI cannot verify at all. Build in that order.

## Why everything ends up on the device

The governing fact is that **Threema has no companion-device concept and WhatsApp does.**

A Threema identity has exactly one primary device. Two connections for one identity means the
server keeps dropping one of them. So a server-side Threema bridge holding her ID is not an
addition to her phone — it is a *replacement* for it, and it takes her inbound Threema calls
with it, because a call would ring on a server with no microphone.

WhatsApp is the opposite: the phone app stays primary on her number and companions attach
alongside it, which is the only reason Baileys works at all.

That asymmetry is why the two bridges look different, and why Threema in particular has to run
in the app rather than on a server: the app *is* the device that holds her ID.

---

## Constraints that apply to both

- This host is aarch64 and cannot build Android (no `aapt2`). CI compiles, runs JVM unit
  tests and lints — **nothing else is verified**. No emulator, no device, no Node execution.
- All user-facing copy goes through `strings.xml` in both `values/` and `values-de/`, with
  identical key sets.
- Nothing about a messenger is ever shown to her. A person is a person.
- Her contacts and message content must never leave the device except where the existing
  Firestore bridge contract already sends them (`docs/bridge-contract.md`).

---

# Stage 1 — Threema data-backup import

## What this is and is not

A Threema **data backup** is an encrypted ZIP the user exports from the Threema app
(Settings → Backups → Data backup), protected by a password they choose. It contains CSV
files: contacts, groups, and one message file per conversation, plus media.

**It is history, not a live feed.** Importing it does not connect anything. Live Threema
messages still arrive by the server-side bridge. Do not present this to anyone as "Threema now works".

Three other Threema backup artefacts exist and are **out of scope**: the *ID backup* (a short
string holding the private key), *Threema Safe* (server-side ID + contacts, no messages), and
the raw app database. Only the data-backup ZIP is handled here.

## Why it is worth doing

The bridge contract identifies people by raw Threema ID (`ECHO1001`). Her screen must show
"Anna" and Anna's face. The backup is the only offline source that maps IDs to names and
photos without asking her anything. That mapping is the actual deliverable; the message
history is secondary.

## Hard requirement: imported messages are never "new"

Every message that comes out of a backup must be stored with `isRead = true` and must be
excluded from the nudge and unread paths. Importing a backup must not put a decade of old
messages on her home screen as things needing attention. This is the single most important
behavioural rule in Stage 1 — get it wrong and the app becomes unusable in one tap.

## Implementation

### Parsing (pure, unit-testable — put the real effort here)

Create `data/threema/ThreemaBackupParser.kt` as a plain object with **no Android imports**,
operating on strings and byte arrays:

- `parseContactsCsv(csv: String): List<ThreemaBackupContact>`
- `parseMessagesCsv(csv: String, conversationIdentity: String): List<ThreemaBackupMessage>`

The CSV layout below is **from documentation and inspection, not verified against a real
export**. Treat every column as optional, match columns by *header name* rather than
position, and fall back rather than throw on anything unexpected. If the real backup differs,
the parser changes and the tests change — nothing else should.

Expected `contacts.csv` columns include: `identity`, `publickey`, `firstname`, `lastname`,
`nickname`, `verification`.

Expected `message_<identity>.csv` columns include: `apiid`, `uid`, `isoutbox`, `identity`,
`body`, `type`, `posted_at`/`createdat`, `isread`.

Rules for the parser:
- Quoted fields with embedded commas and newlines must survive. Write a small proper CSV
  reader; do not `split(",")`.
- `isoutbox` true means she sent it — keep it, but mark it outgoing.
- Non-text message types (image, video, audio, location) become a short German placeholder
  (`"[Foto]"`, `"[Sprachnachricht]"`) rather than being dropped, so a conversation does not
  silently lose its shape.
- Malformed rows are skipped, not fatal. A backup with one bad line must still import.

### Decryption and file handling

`data/threema/ThreemaBackupImporter.kt` (Android side):

- The ZIP is AES-encrypted. Use `net.lingala.zip4j:zip4j` (add to the version catalog), which
  handles AES-256 ZIPs; `java.util.zip` cannot.
- Read the archive through the Storage Access Framework (`ACTION_OPEN_DOCUMENT`), copy to
  app-private cache, extract, parse, then **delete the extracted copy and the cache copy**.
  Her entire message history in plaintext must not linger in cache.
- A wrong password must produce a clear caregiver-facing error, not a crash.
- Do the whole thing off the main thread.

### Storage

The current `LocalMessageStore` is in-memory and dies with the process, which is fine for
notifications but useless for an import. Add Room (SPEC.md §3 already permits "Room or
DataStore — pick one"):

- `ThreemaContactEntity(identity, firstName, lastName, nickname, photoPath)`
- `ImportedMessageEntity(id, partnerIdentity, text, timestamp, isOutgoing)`

Imported contacts feed name and photo resolution for `Partner` and `Message` — when a bridged
message arrives with `senderId = "ECHO1001"` and no `senderName`, look it up here.

Do **not** merge imported history into the `messages` StateFlow that feeds the home screen.
Keep it a separate query used only for lookups and, optionally, a conversation view.

### Where the caregiver triggers it

Not on her home screen. Reuse the existing hidden gesture: five taps on the clock within 1.5 seconds
already opens the pairing dialog (`ClockHeader.kt`, `PairingCodeDialog.kt`). Add the import
entry point there, in the same caregiver-only surface. No visible button anywhere.

### Tests (JVM)

- `ThreemaBackupParserTest` — well-formed CSV; quoted fields containing commas and newlines;
  a malformed row in the middle (surrounding rows still parse); unknown message type becomes
  a placeholder; empty file yields an empty list.
- `ThreemaImportRulesTest` — every imported message is `isRead = true` and never appears in
  the unread or nudge selection.

---

# Stage 2 — WhatsApp via Baileys inside the app

## Read this before starting

Baileys is a Node.js library. Running it "in the app" means shipping a Node runtime inside
the APK. This is possible and people do it, but be clear-eyed about the cost:

- **APK size.** `libnode.so` is large. Restrict `abiFilters` to `arm64-v8a` to keep it to one
  architecture; the APK still grows by tens of megabytes.
- **Staying alive — build it WITHOUT a foreground service.** Owner decision, 2026-07-27.
  An earlier draft of this document said a foreground service (and therefore a permanent
  status-bar notification, against SPEC.md §2) was unavoidable. That was too pessimistic for
  *this* app, for two reasons:
    - This app is the **default HOME launcher**. Its process is the home process, which the
      system keeps at a far better out-of-memory adjustment than an ordinary backgrounded app,
      and it is outright *visible* whenever she is on the home screen — which is most of the
      time, because she has no reason to leave it. Process death from memory pressure is not
      the realistic threat here.
    - The thing that actually kills a long-lived socket is **Doze and OEM app-sleep, and a
      foreground service does not exempt you from either.** What exempts you is the
      battery-optimisation whitelist. So the notification would buy very little of what it
      costs.
  Build it with no foreground service. If real-device testing shows the socket dying anyway,
  bring the evidence back and let the owner decide then — do not quietly add a notification.
- **Battery optimisation.** The caregiver must grant
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, or the session dies on an idle phone. On a phone
  that sits on a table all day — exactly this phone — this is the difference between
  working and not. This is a **one-time dialog during caregiver setup, not a permanent
  notification**, and it is now the primary mechanism keeping the session alive.
- **Samsung specifically.** The target device is Samsung One UI. Samsung's
  app-sleep is the most aggressive of any OEM: it will put the app to sleep on its own
  schedule regardless of adj. Caregiver setup must also walk through
  Settings → Battery → Background usage limits → **Never sleeping apps**. Treat this as part
  of setup, not as an optional tweak.
- **Node version.** Recent Baileys expects a modern Node; the Node bundled with
  nodejs-mobile may be older. Pin a Baileys version known to run on whatever Node the runtime
  actually provides, and record the pin and the reason in the code. Do not assume latest
  works.
- **Terms of service.** Multi-device companion linking is unofficial and against Meta's
  terms. Account bans happen. This is her personal account.
- **Verifiability.** CI compiles Kotlin and runs JVM tests. It will not run Node, will not
  link a session, and will not catch a runtime failure in any of this. Everything below is
  unverified until it runs on a real phone.

If any of this makes the server-side option look better after all, say so rather than
building it badly.

## Runtime

- Integrate `nodejs-mobile` for Android (Janea Systems). Add the native library, and a
  `service/NodeRuntimeService.kt` that starts the Node engine and keeps it running. A plain
  started service, **not** `startForeground()` — see "Staying alive" above.
- Ship the JS as `app/src/main/assets/nodejs-project/`, containing `main.js` and a
  pre-installed `node_modules`. Copy it to app-private storage on first run (assets cannot be
  `require`d in place), and re-copy when the app version changes.
- Persist auth state with Baileys' multi-file auth state, in app-private storage only. This
  folder is the WhatsApp session — treat it as a credential. Never log it, never back it up,
  exclude it from Android auto-backup.

## Pairing

Use the **phone-number pairing code**, not the QR code. Baileys can request an 8-character
code for a given number; the caregiver enters her WhatsApp number, the app shows the code,
and the caregiver types it into WhatsApp on her phone under Linked Devices. A QR code would
need a second device to scan the first, which is awkward when the caregiver is remote.

Put this in the same hidden caregiver surface as the pairing dialog and the Threema import.
She must never see it.

## Kotlin ↔ Node channel

nodejs-mobile provides a bidirectional message channel. Define one JSON envelope and keep
both sides dumb:

- Node → Kotlin: `{"type":"message","id":…,"senderId":…,"senderName":…,"text":…,"timestamp":…}`
  and `{"type":"status","state":"connected|disconnected|pairing_required"}`.
- Kotlin → Node: `{"type":"send","to":…,"kind":"reaction|text","emoji":…,"text":…,"inReplyToMessageId":…}`.

Mirror the field names in `docs/bridge-contract.md` exactly, even though these never touch
Firestore. One vocabulary across both paths is worth more than a locally tidier one.

## Model changes

`MessageChannel` currently has `NOTIFICATION` and `BRIDGE`. Add `ON_DEVICE` for messages that
came from the embedded Baileys session, and route replies for those messages to the Node
channel rather than the Firestore outbox or the notification replier. `BridgeTransport` stays
as it is — an `ON_DEVICE` + `WHATSAPP` message is exactly what this produces.

## Reactions

Baileys sends real WhatsApp reactions. When `inReplyToMessageId` is present, send a native
reaction; otherwise send the emoji as a text message. This is the "connect directly, emoji as
text as fallback" behaviour, and unlike the notification path it can actually deliver a real
reaction.

## WhatsApp is not arriving by any other route

**Superseded 2026-07-30.** This section used to say: once Baileys is live, remove
`com.whatsapp` from the notification listener, and do it in the same change so WhatsApp never
stops working with nothing to replace it.

The notification listener was removed first. WhatsApp therefore reaches her by nothing at all
until Baileys lands — that is the current, deliberate state, not an oversight.

## Tests (JVM)

Only the pure parts are testable. Test those and do not fake the rest:

- Envelope serialisation and parsing in both directions, including unknown `type` values
  being ignored rather than throwing.
- Mapping a Node message envelope to `Message` with `channel = ON_DEVICE`,
  `transport = WHATSAPP`.
- Reply routing: an `ON_DEVICE` message goes to the Node channel, a `BRIDGE` message to the
  outbox, a `NOTIFICATION` message to the replier.

Do not write tests that pretend to exercise Node, the socket, or pairing. A test that cannot
fail for the right reason is worse than no test.

---

# Stage 3 — Threema client inside the app

Owner decision, 2026-07-27: the Threema client runs in the app, not on the server. See "Why
everything ends up on the device" above for the reasoning. This supersedes the server-side
Threema bridge as the *message transport*; it does not remove Firestore (see below).

> **Built as of 2026-08-04.** The bridge is vendored under `native/threema-bridge`, CI
> cross-compiles it and hands the `.so` to the build job, `ThreemaClient` drives it, and inbound
> and outbound messages both go through `MessageChannel.ON_DEVICE`. What follows is the original
> plan; these notes record where it now differs.
>
> Three things were wrong with the plan, discovered by building it:
>
> - **A client that starts is not the same as a client that is provisioned.**
>   `ThreemaRegistrar.ensureIdentity()` registers a *brand new* identity when none is stored, so
>   merely starting on an unprovisioned phone mints an address nobody has, reports Connected and
>   receives nothing forever. `ThreemaStartupPolicy` now gates startup on an identity already
>   existing.
> - **Threema must never fall back to the Firestore outbox.** Writing to a queue nobody drains
>   succeeds, which turns "the client is down" into "sent". `SendDispatch` makes the Threema path
>   fail instead. Nothing on any host reads `devices/{id}/outbox`.
> - **Contact matching was missing from this document entirely**, and without it the whole stage
>   is inert — see below.

## Giving her contacts a Threema ID

Everything above assumes contacts *have* Threema IDs. Nothing gave them one.
`ContactsContract` has no such column, and `libthreema` exposes no lookup in that direction, so
`MessageRouter`'s Threema branch was unreachable code for every contact she has.

Two sources now supply them, and they are not equally trustworthy:

- **`ThreemaContactMatcher`** — matches her imported backup to her address book *by name*.
  Deliberately timid: exact after normalisation, unambiguous on both sides, no fuzzy or
  first-name matching. It is guesswork and is treated as such.
- **`ThreemaNumberMatcher`** — matches *by phone number*, using identities the directory server
  itself resolved. This is the real mechanism, ported from the Threema app
  (`APIConnector.matchIdentities`): HMAC-SHA256 the normalised addresses, authenticate with a
  match token from the two-phase directory challenge, `POST identity/match`.

Where the two disagree the number wins, because one of them is a guess and the other is not.

Two things to hold on to:

- **Normalisation is where this silently fails.** The same number written `079 123 45 67` and
  `+41 79 123 45 67` must hash identically or the lookup finds nobody, and a wrong country code
  produces a number belonging to a real stranger. `PhoneNumberNormalizer` returns null rather
  than guess; a number it declines is simply not looked up.
- **A sync tells Threema who is in her address book.** The hashes are over a small enough space
  to brute-force. The Threema app makes the same trade and puts contact sync behind an explicit
  opt-in for exactly this reason; if this ever becomes automatic, that decision should be made
  deliberately and by her family, not inherited from this sentence.

## What already exists, and must be reused rather than rewritten

- `native/threema-bridge` — `threema-gemini-bridge`, a Rust
  binary over `libthreema` (Threema's own Rust library, ~1000 lines of local glue in
  `src/main.rs`). Given `--threema-id` and `--client-key` it connects over CSP **as the
  primary device** and speaks newline-delimited JSON on stdin/stdout.
- `<companion-ts-bridge-client>` — the existing driver for that
  protocol. It is the reference for framing and lifecycle. Implement the *same* framing in
  Kotlin; do not invent a second protocol.
- **Two local patches must travel with it, or it will not build on a CI runner.** `Cargo.toml`
  has `[patch.crates-io] blake2 = { path = "<treema-light>/..." }`, and group
  handling depends on a local `libthreema` `DesyncError` patch. Both point outside this
  repository at paths that exist only on this host. Vendor them in, and record in the vendored
  directory why the patch exists — an upstream `libthreema` sync silently kills group
  handling if the patch is lost.

## Integration shape

First cut: ship the Rust binary as a native library and drive it as a subprocess. Do not start
with JNI or UniFFI — get it working, then consider replacing the pipe.

- Cross-compile for `aarch64-linux-android` only, matching the Baileys `abiFilters`.
  `libthreema` is Threema's own library and Android is its intended target; this is its
  ordinary use, not a hack.
- Android 10+ refuses to exec files from the app data directory (W^X). Ship it as
  `jniLibs/arm64-v8a/libthreema_bridge.so` and exec it from
  `ApplicationInfo.nativeLibraryDir` — that path stays executable and is the supported
  workaround. The filename **must** match `lib*.so` or the packager will not extract it.
- Kotlin side: a `data/threema/ThreemaClient.kt` owning the process, the JSON framing,
  reconnect with backoff, and a `StateFlow` of connection state. Same lifecycle rules as the
  Node runtime in Stage 2 — no foreground service, battery-optimisation exemption instead.

## Her identity is a credential

- The client key comes from a Threema ID export (ID backup or Threema Safe). Import it behind
  the hidden caregiver gesture, never a visible screen.
- Store it app-private. Never log it, never include it in any diagnostic dump,
  `android:allowBackup="false"`. It is equivalent to her entire Threema account.
- **Test with a throwaway ID first** (owner decision, 2026-07-27). Do not point this at her
  real ID until an unrelated ID has completed a full connect → receive → send → reconnect
  cycle on a real device.
- Once this holds her ID, the Threema app on her phone stops working for that ID. That is the
  intended end state, not a bug — but it means there is no fallback if the embedded client
  misbehaves, and the person who loses their messaging is her. Weight the testing accordingly.

## What this does not give you

**Voice calls.** CSP carries the call signalling; the audio itself is WebRTC. Implementing
Threema calling is a project of its own size and is explicitly out of scope here. Until it
exists, tapping a face places a cellular call and nothing else. Do not stub a call UI that
cannot complete a call — for her, a button that looks like it works and doesn't is worse than
no button.

## Consequences elsewhere

- `MessageChannel` gains `ON_DEVICE` (shared with Stage 2). Threema messages become
  `ON_DEVICE` + `BridgeTransport.THREEMA`; reply routing sends them to the Rust client.
- Threema no longer has a notification path to come out of; the listener is gone, and the
  Threema app is not installed for her ID in any case.
- **Firestore stops being the message transport** and becomes caregiver visibility and
  settings only. `docs/bridge-contract.md` is written as a transport spec and must be
  re-scoped; the in-app model it produced (`BridgeTransport`, `Partner`, `Message`) stays and
  was worth having.

## CI

This is now the most fragile code in the app and the least verified — a Rust cross-compile,
a subprocess, and a network protocol, none of which the current pipeline touches. Add a job
that cross-compiles the crate for `aarch64-linux-android` and fails if the `.so` is not
produced. That is not a test of the protocol, but it converts "silently broken on her phone"
into "red on a pull request", which is the difference that matters here.

---

# Stage 4 — Threema voice calls

Owner decision, 2026-07-27: build it. Stage 3 says calling is out of scope; this supersedes
that. Read the whole section before writing code — the scope is not what it looks like.

## The situation, accurately

`libthreema` does **not** implement calls. Its Rust message model handles `Text`, `Location`,
`DeliveryReceipt`, `WebSessionResume`, the group equivalents and `ForwardSecurityEnvelope`,
and nothing else. The call type codes (`CALL_OFFER = 0x60` … `CALL_RINGING = 0x64`) appear
only in `threema-protocols/src/common.proto`, which is the type registry — no Rust code
dispatches them.

The saving grace is that **the reference implementation is on this machine.**
`<treema-light>` is a Threema for Android fork containing the complete VoIP stack:
signalling messages in `domain/src/main/java/ch/threema/domain/protocol/csp/messages/voip/`,
and the media layer in `app/src/main/java/ch/threema/app/voip/`. 135 files, ~35k lines.
This is a **port from a working reference**, not reverse engineering. Read that code. Do not
invent protocol details — every answer is on disk.

**Licence.** Threema for Android is **AGPLv3** (`<treema-light>/LICENSE.txt`).
Code lifted from it makes this app a derivative work, which must then also be AGPLv3 and its
source offered to anyone the APK is given to. For a private family app that means: keep the
AGPL headers on ported files, add the licence to this repo, and give her the source. The repo
does **not** have to become public. Keeping the headers is not optional bookkeeping — strip
them and the port is a licence violation.

## Build it in three layers, in this order

Each layer is useful on its own. If you run out of budget, stop at a layer boundary with that
layer finished — do not leave three half-layers.

### Layer 1 — Rust pass-through (small, do this first)

Teach the bridge to carry call messages **without understanding them**:

- Accept CSP E2E types `0x60`–`0x64` on receive: decrypt as usual, then emit the payload to
  Kotlin as opaque bytes (base64 in the existing newline-JSON envelope) with its type code.
- Accept the same on send: take opaque bytes plus a type code from Kotlin, encrypt, transmit.
- **Do not parse the JSON payload in Rust.** All call semantics live in Kotlin. Rust's job is
  the envelope, nothing more.
- Set `O2O_AUDIO_CALL_SUPPORT = 0x0010` in the identity's feature mask. Without this bit, her
  contacts' Threema apps will not even show a call button for her — the whole feature is
  invisible no matter how correct the rest is.

### Layer 2 — Signalling in Kotlin (the highest-value part; CI can actually verify it)

Port from `domain/.../csp/messages/voip/`: `VoipCallOfferData`, `VoipCallAnswerData`,
`VoipICECandidatesData`, `VoipCallHangupData`, `VoipCallRingingData`.

These are small, pure JSON serialisation classes with no Android dependencies. Keep them that
way — put them in a package with **zero Android imports** so they run under JVM unit tests.
This is the only part of Stage 4 that CI can prove, so put real effort into the tests: encode
and decode each message type, round-trip an offer with a realistic SDP body, reject malformed
input without throwing into the call path, and check `callId` correlation.

### Layer 3 — Media (large, and unverifiable here)

Port `app/.../voip/PeerConnectionClient.java` and the audio path. Add the libwebrtc AAR
(`libs.webrtcAndroid` in the reference's `app/build.gradle.kts` — match its version rather
than picking a newer one).

- **TURN is mandatory, not an optimisation.** See `app/.../utils/TurnServerCache.java`:
  credentials come from Threema's directory server and are authenticated with her identity.
  Mobile networks are behind CGNAT, so without working TURN essentially every call fails.
  Budget for this; it is the most likely thing to be silently wrong.
- `RECORD_AUDIO`, audio focus and `AudioManager` mode handling, proximity/wake locks, and
  earpiece-vs-speaker routing all come from the reference. Take them from there.
- Use Android **Telecom / `ConnectionService`** for incoming calls so the phone rings properly
  and shows the call over the lock screen. A custom activity that only appears when the app is
  foregrounded will miss calls, which for her is the same as the feature not existing.

## Her call screen is not Threema's call screen

Do **not** port any VoIP UI. Threema's call screen assumes a competent user. Hers is:
one large face, one very large green answer control, one very large red hang-up, nothing else
— no mute, no video toggle, no keypad, no messenger name. All copy through `strings.xml` in
both `values/` and `values-de/`.

An incoming call must be answerable from the lock screen without unlocking. If she has to
recognise a notification and swipe correctly, the feature does not work for her.

## Honest completion criteria

Do not report Stage 4 as done because it compiles. It is done when a real call between two
real devices connects and carries audio both ways. CI cannot tell you anything about that;
it can only tell you Layer 2 is correct and the rest builds. Say plainly which layers are
finished and which are untested, and never describe untested media code as working.

---

# Stage 5 — What WhatsApp still has that she does not

Written down while the WhatsApp integration is fresh, so that the next person does not have to
rediscover which of these are small and which are not. None of it is started.

## Status ("Status" / stories)

The thing her family actually uses to post a photograph of the grandchildren to everybody at
once, and the one part of WhatsApp she currently cannot see at all. whatsmeow does deliver
status updates: they arrive as ordinary `events.Message` with the chat JID `status@broadcast`,
carrying the same image, video and text payloads as any other message.

What makes this a stage of its own is not the fetching, it is the answer to a question this app
has never had to ask: **whose status, and where does it go on her screen?**

- Status is not addressed to her. Every other message in this app is *from a person, to her*,
  which is the whole model her screens are built on — a message list of people who wrote. A
  status posted to two hundred contacts is not somebody writing to her, and putting it in her
  Messages list would bury the four messages that were.
- It expires after 24 hours. Nothing else on her screen disappears by itself, and something
  vanishing overnight is exactly the kind of thing that reads as a fault to her.
- Marking one as viewed is visible to the sender. Doing that on her behalf, because a screen
  happened to render, tells her daughter she looked at something she may never have seen.

The likely shape, if it is built: its own quiet place — not the Messages list — showing only
statuses from her favourite contacts, never marked as viewed, and never producing a badge. The
implementation is a day; the decision about the screen is the work.

## Voice messages

Almost certainly the highest-value item in this list, because a voice message needs no reading
and her family sends them constantly. Today it is `[Sprachnachricht]`, which is the emptiest
possible rendering of somebody's voice.

Everything needed is nearly in place: `fetchPhoto` in the bridge already downloads, stores
app-private, prunes and reports back over the `media` event, and would generalise to audio by
kind. The new work is on her screen and it is all interaction design:

- One very large play control on the bubble and nothing else. No scrubber, no speed, no
  waveform, no pause-and-resume — a control she can land on and a sound that starts.
- It has to route through the same audio path as read-aloud, and stop read-aloud when it
  starts. Two voices at once is unusable, and she will not know which control silenced what.
- WhatsApp voice notes are Opus in an Ogg container. `MediaPlayer` handles that on Android 10
  and later, so no decoding work — but verify on her actual phone rather than trusting it.
- Recording a reply is a *separate* question, and a harder one: a record button that starts on
  press and sends on release will send silence, and a great deal of it.

## Video — built

Done in two halves, and the split is the design rather than an implementation detail.

**The still, which was already there.** WhatsApp packs a JPEG thumbnail inside the video message
itself (`videoMessage.jpegThumbnail`) and Threema uploads one beside the film as a second blob.
Neither costs a download worth the name, so both are written immediately, as `kind: "photo"`, and
`[Video]` became a picture of whoever is in it. On its own this was most of the value.

**The film, behind it.** Both bridges now fetch the film too — `kind: "video"`, a second `media`
event naming *the same message id*, stored in its own `videoPath` column beside the still's
`photoPath`. Never instead of the still: the picture is what she has while the film downloads,
and what she is left with when it never arrives, which for expired media is most of a history
import. Films are capped at 48 MB (`maxVideoBytes` / `MAX_VIDEO_BYTES`, the same number in both
bridges) — past that it is somebody's holiday in full, paid for with mobile data of a woman who
does not know she is buying any.

**The pruner needed a second budget.** Counting files was enough while every attachment was a
photograph or a few seconds of speech; four hundred *films* is most of the storage of her phone.
Both bridges now keep the newest files that fit inside **both** a count (400) and a total size
(512 MB), whichever bites first. This is the one part of either bridge that deletes something of
hers and the only part with host tests behind it — `go test ./...` runs in CI for exactly this.

**On her screen** (`MessageVideo`, `FilmPlayer`): one control under the still, the same shape and
size as the voice-message control because it is the same act. The still above it opens the film
too, on a single tap and with no double-tap zoom, and carries the same ▶ mark as a film on the
Look screen (since 2026-10-24: she tapped the picture four times and nothing happened). It does not play in the bubble —
that is what raises the question of what happens when she scrolls away from a playing film, and
there is no good answer to it. It opens the film on the whole screen instead, black, no controls
at all, starting by itself, with one large **Schliessen** and an automatic close when the film
ends so that doing nothing is also a way out. Tapping the picture deliberately does nothing: she
holds the phone in the hand she taps with, and losing the film to a shifted grip is not something
she could recover from. Starting one stops read-aloud and any voice message, for the reason
voice messages already stop read-aloud — she cannot be asked to know which of two voices she
started.

**Not done, honestly:** none of this has run on a phone. CI proves the protocol, the mapper, the
prune arithmetic and that it all compiles; it can say nothing about whether `VideoView` plays
what WhatsApp and Threema actually send. Codec support in particular is a device question — the
first real test is a film from her son on her own handset.

## What all three share

The bridge side of each is small; the download, storage, pruning and event plumbing already
exist and were built to take more kinds. The work is on her screen, and it is the same work
every time: deciding what she sees, and what she is never shown because it would make the
phone something she has to operate rather than something she uses.
