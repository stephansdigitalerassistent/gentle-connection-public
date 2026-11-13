# gentle connection — build brief

This is the north-star spec for the implementation agent. Follow it; when a detail is
unspecified, mirror the conventions of the sibling `gentle phone` app (Kotlin, Jetpack
Compose, Material 3, `ch.heuscher.*` package, large accessible components).

## 0. Product in one paragraph

A single-surface Android **home-screen launcher** for an elderly parent with cognitive
decline. It gathers incoming communication from any messenger into ONE unified view with
**no app differentiation**, lets her **call** favorites by tapping a face, lets her
**react** to messages with big emoji (the primary, keyboard-free reply), and lets her
**read email** (read-only). A remote caregiver configures everything.

## 1. Platform & stack

- Kotlin, Jetpack Compose, Material 3.
- `minSdk = 26`, `targetSdk = 36` (match gentle phone).
- Package / applicationId: `ch.heuscher.gentleconnect`. App label: **gentle connection**.
- Single-module Gradle app (`app/`) with Kotlin DSL, mirroring gentle phone’s structure.
- Locales: **German (default `values-de`) and English (`values`)**. All user-facing copy
  goes through `strings.xml` — never hardcoded.

## 2. The launcher (this IS the app)

Register `MainActivity` as a HOME launcher:

```xml
<intent-filter>
  <action android:name="android.intent.action.MAIN" />
  <category android:name="android.intent.category.HOME" />
  <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

Also keep `LAUNCHER` category so it’s findable. Handle `onBackPressed` as a no-op / stay
on home (a launcher must not “exit”). Survive reboot. No status bar clutter.

### Home screen layout (top → bottom), all large & high-contrast

1. **Clock + date** — e.g. `9:24` / `Donnerstag, 23. Juli`. Updates each minute.
2. **Nudge card** — show at most ONE, the single most important unread thing (a message
   or a missed call). Big “Ansehen” button. If nothing, hide the card entirely.
3. **Favorite faces** — horizontal or 2-row grid of big circular contact photos with the
   name beneath. **Tapping a face places a phone call** (voice) to that contact. This is
   the primary “talk” action. Reuse gentle phone’s call-placing approach (`ACTION_CALL`).
4. **Unread messages** — a short list (max ~5) of unified incoming messages: sender photo,
   name, one-line preview, time-in-words. Tapping opens the message view with a big
   **reaction row**. NO messenger branding anywhere.
5. **Unread mail** — a compact “Post” entry showing unread count; opens a **read-only**
   list of emails. Clearly labeled read-only.
6. **The four buttons** — Home, Call, Message, Post, at the bottom of *every* screen she can
   reach, always in the same place, with the current one marked. **Amended 2026-07-30**: the
   bar was originally on the home screen alone, so opening her post replaced four familiar
   buttons with a single back arrow in a corner. The way out of a screen must not change
   depending on which screen she is on. "Message" opens the writing flow (§3); it does not
   scroll the page.

## 3. Unified messages (no app differentiation)

- Messages arrive from two sources and are normalized into a single `Message` model:
  the Threema bridge, and her SMS inbox read through `Telephony.Sms.Inbox`.
- **Superseded 2026-07-30.** This originally specified a `NotificationListenerService`
  (`UnifiedMessageListenerService`) reading other apps' notifications off the status bar.
  It was removed: it could not verify who a message was from, it required her to grant a
  frightening permission through a system settings screen she could not navigate, and every
  source it covered is now covered properly. See `docs/mail-account.md`.
- Persist recent messages locally (Room or DataStore — pick one, keep it simple).
- De-duplicate; map sender to a known Contact when possible (by name/number).
- The UI shows a person and a message — never which app it came from.
- **Messaging is two-way.** **Amended 2026-07-30**, overriding the reply-only design below.
  She can both answer and *write first*, over SMS, Threema and WhatsApp.
  - **Answering** goes back the way the message came, and is not a routing decision: a reply
    to an SMS leaves as an SMS, a reply to a bridged message leaves through the outbox on the
    transport it arrived on. Answering an SMS over Threema would reach the sender from an
    identity they do not recognise.
  - **Writing first** is routed by `MessageRouter` — Threema, then WhatsApp, then SMS.
    Threema first because it is this app's own transport, costs nothing and behaves the same
    abroad; SMS last because it costs money per message and should not be the default for
    someone who cannot be asked to weigh that. Pure and unit-tested, like `CallRouter`.
  - The transport is **never shown to her and never chosen by her**. She picks a person from
    one merged list — a favourite contact and a bridge partner with the same name are the same
    woman, and she is never asked which of the two she means.
  - Both the reply dialog and the compose dialog offer the same five emoji (❤️ 👍 😊 🙏 😂)
    as the primary, keyboard-free way to say something, with a text field beneath.
  - Delivery honesty: when nothing actually left the phone, say so. Never let her believe a
    message is on its way when it is not.
- **Superseded 2026-07-30** (kept for the record): reactions were to be delivered back through
  the source app's notification reply/remote-input action, behind a `MessageReplier` interface
  with a per-source strategy and a deep-link fallback. That path went with the notification
  listener. `MessageReplier` survives as the SMS sender; everything else goes through the
  bridge outbox.
- Optional short text reply may sit under the reactions, but is secondary. **No voice
  messages anywhere.**

## 4. Mail (read-only)

- A `Mail` model + `MailRepository`. Read-only: fetch and display, never compose/send/delete.
- Prefer the simplest viable source: read email notifications via the same notification
  listener (subject + sender + snippet) for v1, behind a `MailSource` interface so a real
  IMAP fetch can replace it later. Clearly mark the whole surface “nur lesen / read-only”.

## 5. Caregiver remote config

- Mirror gentle phone’s `RemoteSettingsRepository` (Firebase Firestore): device pairing
  code, remote settings that override local, register device, sync.
- Caregiver-controllable: favorite contacts & order, text/photo size, dark mode, which
  channels (messages/mail) are enabled, nudge on/off.
- Include a minimal `caregiver-portal/` static web page (like gentle phone’s) later; not
  required for the first code drop.

## 6. Accessibility & tone (non-negotiable)

- Touch targets ≥ 56dp; prefer 72dp+ for primary actions. Huge text option.
- High contrast; full light & dark themes.
- Haptic + optional TTS announcement on primary actions (reuse gentle phone patterns).
- Warm, plain German copy. Never expose technical/app names to the user.
- Everything reachable in ≤ 2 taps from home.

## 7. Deliverable for the first code drop

1. Compiling Gradle project skeleton (`settings.gradle.kts`, `build.gradle.kts`,
   `app/build.gradle.kts`, `gradle/`, `gradlew`), Compose + M3 configured.
2. `AndroidManifest.xml` with HOME launcher + required permissions (CALL_PHONE,
   READ_CONTACTS, READ_CALL_LOG, POST_NOTIFICATIONS, READ_SMS, etc.). No
   `BIND_NOTIFICATION_LISTENER_SERVICE` — see §3.
3. Home screen (`HomeScreen.kt`) implementing the layout in §2 with mock data wired.
4. `Message` model + local store (`AppStores`) + `MainViewModel`.
5. Reaction UI + `MessageReplier` interface with at least a fallback implementation.
6. Read-only mail model + screen.
7. `RemoteSettingsRepository` stub mirroring gentle phone.
8. `strings.xml` for `values` (en) and `values-de` (de).
9. Unit tests where logic is testable (message normalization, dedup, settings merge).

## 8. Constraints & guardrails

- Do NOT run Gradle builds on this host (aarch64, no `aapt2`); write code to compile in CI.
- Keep it a **separate repo**; do not touch `../Simple-Phone2`.
- Small, readable commits. Conventional-commit messages. English code, German UI strings.
- If a capability is technically impossible from a stock app (e.g. reading full WhatsApp
  history), implement the best feasible approximation and note the limitation in comments.

## 9. Telephoning, beyond the address book

**Added 2026-08-15.** The original spec had exactly one way to make a call: press a face, or a
name in the address book. That is still the way she makes almost every call, and nothing below
changes it. But three things a telephone does were simply absent, and their absence meant
somebody else had to be found:

- **The keys.** A number on a letter, a surgery ringing back from a different line, a taxi.
  `KeypadScreen`, one press behind the Call button. It names the person as soon as enough digits
  are typed, and offers to keep a number that belongs to nobody yet.
- **Who has rung.** `RecentCallsScreen`, reading the telephone's own log. Runs of calls to one
  number collapse into one row — see `CallHistory`, which is pure and unit-tested — because four
  attempts to reach her son is one event in her life and four rows in a record-keeper's.
- **Correcting the address book.** Handed to Android's own contacts editor through
  `ContactEditing`, never written here: an editor of this app's own would need `WRITE_CONTACTS`
  in exchange for a screen the system already provides. It is the caregiver's job, so it is on a
  press-and-hold rather than a button, on a contact row and on a call row alike.

Neither new screen is a fifth tab. The bar along the bottom has four buttons and goes on having
four buttons — it is the one part of this phone she has learnt. Both hang under Call, reached
from two labelled buttons at the top of the address book (`CallToolsRow`).

## 10. The watch

**Added 2026-08-15.** A `:watch` Gradle module, same `applicationId` as `:app` and signed with
the same key — the Wear Data Layer only delivers between applications that share both, and a
mismatch is a watch that silently receives nothing.

It is a remote control for the phone in her pocket and not a small copy of it: her favourites as
faces, one question, and a call. Plus "find my phone", which is the one thing a watch can do that
a phone cannot do for itself.

- Phone → watch: the faces, as a **data item** (`WearSyncManager`). Held by the Wear layer and
  delivered when the watch comes back into range, so a favourite starred while it was on the
  bedside shelf is on it in the morning.
- Watch → phone: two **messages**, "ring this person" and "find the phone"
  (`PhoneWearableListenerService`). The paths and the payload format live in one file at each end
  — `WearMessages` — because a path typed differently at the two ends fails silently.
- The phone decides how to place the call, through `CallPlacer`, exactly as it would if the same
  face had been pressed on its own home screen. Whether a call goes over Threema depends on
  whether the phone has mobile voice service and whether it is roaming, which are facts about the
  phone; the watch is not told and does not decide.
- **The telephone first; Threema abroad.** `CallRouter` rings the ordinary number whenever the
  phone has voice service, and Threema only without service or without a number. While roaming,
  with `threemaOnlyWhenRoaming` on (portal: "Im Ausland nur über Threema anrufen", **on by
  default**, added 2026-09-16), calls go over Threema or not at all — no fallback to the paid
  network. Emergency numbers are always dialled on the telephone, before any routing.
- **No in-call controls.** Answering, rejecting and hanging up from the wrist are not offered.
  They became technically possible when the app took the dialler role (§11), and they are still
  not offered: a wrist is where a sleeve presses a button, and the button it would press is the
  one that ends her daughter's call. The watch starts calls; the telephone carries them.

## 11. Being the telephone

*Added 2026-08-16.*

The app holds Android's **dialler role** (`android.app.role.DIALER`), which means an ordinary
telephone call — the ringing, the answering, the screen in between, the hanging up — belongs to
this app rather than to another one. Before this, pressing a face here handed her over to a
different app halfway through the one moment that matters most, and the app that knew who she was
and how large her text should be had no part in it.

- **One call screen, both kinds of call.** `ui/CallScreen.kt` draws every call, whether it arrived
  over the telephone network (`telecom/PhoneCallActivity`) or over Threema
  (`voip/CallActivity`). She has no way to know which kind is which and should not have to: a
  green circle in one place must be the same green circle in the other.
- **What is not on that screen**: mute, keypad, hold, video, call duration, and the name of the
  network it came in on. Each is a control that a phone held to an ear presses by accident, and
  each has a recovery she would have to know she needed.
- **Where the sound goes** (`telecom/CallAudioPolicy`, run for both kinds of call by
  `telecom/CallAudioRouting`; *changed 2026-10-26*). A Bluetooth or wired headset always wins.
  Otherwise:
  - On an ordinary phone she chooses. Under the name sit two keys side by side, **Hörer** and
    **Lautsprecher**, the one in force filled in. They are two choices rather than one switch
    because her finger often lands twice and a switch pressed twice undoes itself. Every call
    starts on the loudspeaker. The keys are hidden while a headset is in, because a press would be
    overruled at once.
  - On a book-style folding phone (a book-style folding phone) the hinge decides, read from the
    permission-free hinge-angle sensor: folded shut is the earpiece and opened up is the loudspeaker.
    It counts as shut below 30° and open above 60°, and in between it keeps its last state. The screen
    only names the route there, because two keys arguing with the hinge would be a second control.
    A flip phone would be routed the wrong way round by this rule.
  - The proximity sensor no longer routes anything. On one foldable device the proximity sensor shut its
    infrared part off mid-call, and light leaking past her ear read as *far*. She spent 16 of 17
    minutes on the loudspeaker at full volume with the phone at her ear. One phone read *near* while
    lying flat and never went back to the loudspeaker. The sensor now only blanks the screen at her
    ear (`telecom/EarProximity`), and only while the sound is on the earpiece.
- **The app does not ring.** The manifest does not claim `IN_CALL_SERVICE_RINGING`, so Android
  plays her ringtone at her volume under her Do Not Disturb rules. Owning the ringing would allow
  cleverer rules — silencing numbers she does not know, letting a second call through a quiet hour
  — and every one of them is a new way for the telephone to sit silent, discovered weeks later as
  "she never picks up any more". Simple Phone's `DndRingPolicy` is a tested implementation to port
  if that is ever wanted; taking the ringtone on is a decision to make deliberately, not a side
  effect.
- **No `ACTION_CALL` filter.** Other apps may ask this one to *show* a number (`ACTION_DIAL`, or a
  `tel:` link), and it lands on the keys, typed in, waiting. Nothing on this phone starts ringing
  because another app asked.
- **Asking for the role** is a caregiver's job: `DefaultPhoneRole` opens Android's own screen once
  per install, and again whenever the keys are held down on the address book — the same
  press-and-hold that corrects a name. Refusing costs nothing that was working before.
- **Why it matters beyond the call screen**: `READ_CALL_LOG`, `READ_SMS` and `SEND_SMS` are
  restricted permissions, and holding the corresponding role is what makes them grantable. Without
  the dialler role the app cannot be distributed through Play at all, whatever else is true of it.
- **Blocking unknown callers** is a caregiver switch on the website
  (`RemoteSettings.blockUnknownCallers`), **off unless somebody deliberately turns it on**. When
  on, a call from a number not in her address book — including a withheld one — is *ended* before
  the phone rings; see `telecom/UnknownCallers`. Three things make that safe enough to offer:
  - It ends the call rather than silencing it. A silenced call still lands in her list of calls
    with a number and a green telephone beside it, one press from the person it was meant to keep
    away. Platform-blocked calls (`BLOCKED_TYPE`) are kept off that list too, unless a caregiver
    asks for them (`showBlockedCallsInCallLog`). All rejected calls (`REJECTED_TYPE`) are always
    shown in her call list (marked as `BLOCKED` rather than `MISSED`), including for contacts in
    her address book.
  - A caregiver can also have her told (`notifyBlockedCalls`): a quiet notice with no call-back
    button, separate from the missed-call notice so it can never replace or clear one.
  - Every ended call is written to the caregiver's log (`CALL_FROM_STRANGER_ENDED`), without the
    number, because the log is published to the website. A switch that ends calls silently turns
    the surgery ringing back from an unsaved line into "nobody ever answers there".
  - It does **not** touch the ringtone. Ending one call is a single visible act; owning the ringer
    would put every call at the mercy of this app's audio code, which is the trade refused above.

## 12. Being the messages app

*Added 2026-08-16.*

The app also holds Android's **SMS role** (`android.app.role.SMS`). This is a heavier
responsibility than the dialler role and is written down here so that nobody removes a receiver
without knowing what it costs.

- **Android stops filing text messages the moment this app holds the role.** `SMS_DELIVER` goes to
  exactly one app, and whatever `sms/SmsDeliverReceiver` fails to store is stored nowhere, by
  anybody, ever — her bank codes and appointment reminders included. It stores first and tells the
  app second, so a failure downstream cannot lose a message.
- **Sent messages must be filed by us too** (`SmsSender.recordAsSent`), and only after the network
  confirms they went. Nothing in this app reads the sent box; the cost of not writing it lands on
  a caregiver looking at her phone in an ordinary messages app, or on a backup.
- **Picture messages are dropped.** There is no MMS implementation — no PDU parsing, no MMSC
  download, no store — and `sms/MmsDeliverReceiver` exists only because the role is not granted
  without it. An MMS arriving is lost, not filed elsewhere for later. This was decided knowingly
  on 2026-08-16: the alternative was leaving text messages outside the app's own hands, which is
  what blocks distribution entirely. If it turns out she is sent photographs this way, the fix is
  a real implementation there, not a smaller compromise.
- **Nothing new is asked of her.** The arrival of a message still goes through the one arrival
  loop that knows about quiet hours and about her being on the telephone; the receiver reloads the
  store and lets that loop decide. No second opinion, no second notice.
- `RESPOND_VIA_MESSAGE` is answered by a service that does nothing, on purpose: "decline this call
  and send a message saying why" is three decisions in the two seconds a telephone is ringing.

## 13. WhatsApp calls

*Added 2026-09-12.*

Somebody ringing her on WhatsApp reaches nothing. The call rings inside the WhatsApp app — which
is installed because it has to be, being the primary device this app's companion session hangs
off — behind a screen she has never been taught, and it arrives here as signalling the bridge
cannot act on.

**Answering one is not possible and is not being attempted.** `whatsmeow` implements the
signalling half of WhatsApp calling and nothing else: call events arrive, and `RejectCall` is the
only thing that can be sent back. There is no accept, and the audio runs over Meta's own relay
protocol, which no client outside their own implements. WhatsApp Web can place calls because Meta
wrote that client; the companion protocol does not hand the capability to anyone else. Anybody
revisiting this is revisiting a reverse-engineering project, not a feature — and the cost of
getting it wrong is the link, which also resets her history depth.

So the call is allowed to fail, and the failing is made loud at both ends. See
`model/CallCourtesy.kt`, which is where all of it is decided.

- **She is told.** An unanswered call becomes a missed call in the conversation with whoever rang,
  which her home screen already turns into a card with a green button that rings them back on the
  telephone. A caller WhatsApp addresses only by a hidden id is *not* shown: the card would name
  nobody and its button would ring nobody.
- **The caller is told, once, if her address book knows them.** One message, and it says outright
  that a machine wrote it (`whatsapp_call_notice`) — it goes out under her name, and putting
  sentences she never said in her voice is the one thing this must not do. A stranger is told
  nothing at all: a reply confirms to whoever dialled that this number is live and answered by
  somebody. Nor is a group, where the notice would be an announcement to eight people.
- **Not twice in an evening.** `CallCourtesy.QUIET_MS` is six hours — long enough to cover one
  run of attempts, short enough that somebody falling back into the habit next week is reminded
  rather than met with silence.
- **Only when it really was missed.** WhatsApp saying the call was picked up settles it; failing
  that, the reason it ended and how long it rang do. The reasons are not documented outside Meta's
  own clients, so the bridge logs every one it sees (`[wa] call …`) and
  `CallCourtesy.TAKEN_REASONS` is the list that grows from that. Everything unrecognised counts as
  missed, deliberately: the other way round, a renamed reason would switch this off silently.
- **Turning calls away is a caregiver switch, off by default.** With `rejectWhatsAppCalls` on
  (portal: "WhatsApp-Anrufe abweisen", added 2026-09-14) the app asks the bridge to reject each
  WhatsApp call as it rings, so it no longer rings in the WhatsApp app — where on the second test
  phone somebody other than her picked one up. The bridge keeps each offer's own creator address
  (rejecting must name it as the offer did, which may be a hidden id) and reports a successful
  rejection as the call ending with reason `rejected_by_phone`; from there it is an ordinary missed
  call: the card on her screen, and the notice to a caller she knows. Off, nothing is rejected and
  the call rings in WhatsApp as before.
- **The notice names Threema where it can be answered.** On a phone with a Threema identity the
  notice asks for the telephone number *or* Threema (`whatsapp_call_notice_threema`); otherwise
  only the number.
- **Outgoing WhatsApp calls are not offered.** Placing one through WhatsApp's own contact data row
  is possible and cheap, and is worth doing only if it turns out there are relatives she can reach
  no other way.

## 14. Threema calls behaving like an ordinary telephone

*Added 2026-09-17.*

The user is an elderly woman with cognitive impairment. She gets nervous quickly, cannot follow
technical explanations, and must never perceive any difference between a Threema call and an ordinary
telephone call. The phone must look, sound, and feel like a traditional landline.

- **Zero external app redirects:** `CallChannel` has ONLY `CELLULAR` and `THREEMA_IN_APP`. Routing
  to the external Threema application is completely removed. If in-app Threema is unavailable, the call
  routes over the telephone network (unless roaming with `threemaOnlyWhenRoaming` enabled) or fails
  cleanly with an immediate explanation.
- **Immediate dialling feedback:** When she taps a face, ringback tone (`ToneGenerator.TONE_SUP_RINGTONE`)
  starts immediately, and Telecom is notified right away (`telecomManager.placeCall(...)`), before TURN
  credentials or network handshakes are resolved. To an elderly user accustomed to old telephone
  exchanges, silence after tapping reads as "the phone didn't work".
- **Busy tone cadence (Besetztzeichen):** Calls ending from the other end (remote hangup 0x63, reject
  0x61, 20s unanswered ringing timeout, peer connection error, or disconnection of a cellular call in
  `GentlePhoneCallService`) play the European busy tone (`ToneGenerator.TONE_SUP_BUSY`) on
  `STREAM_VOICE_CALL` for 3.5 seconds. During this time, the call screen remains visible displaying the
  contact and "Call ended" ("Beendet") so she understands instinctively that the other person hung up.
  A local hangup (pressing the red button) terminates immediately and cancels the busy tone.
- **Silent dropout grace (4s):** Handover between Wi-Fi and mobile data momentarily drops WebRTC ICE
  connections for 1–3 seconds. When a call is `CONNECTED`, an ICE disconnection triggers a 4-second
  silent grace timer rather than an immediate abrupt hangup. If ICE recovers within 4s, conversation
  continues seamlessly without disturbing her; if it expires, busy ending is triggered.
- **Fail-at-tap feedback (`CallFailedActivity`):** If a tap on a contact cannot start (roaming abroad
  without Threema, no data/cellular connection, or missing number/identity), the phone launches a
  full-screen, high-contrast explanation over the keyguard (`call_failed_not_from_abroad` or
  `call_failed_no_connection`), shows the contact's picture, reads the explanation aloud once only if the
  caregiver switch `speakCallFailures` is on (portal: "Ansage, wenn ein Anruf nicht möglich ist", **off by
  default**), and auto-dismisses after 10 seconds or immediately upon touch.
- **Watch call-failure haptics:** When a tap originating from her watch fails on the phone, the phone
  remains completely quiet (avoiding alarming an empty room), and sends `CALL_FAILED_PATH` to the watch,
  which vibrates twice distinctly on her wrist (`longArrayOf(0, 200, 150, 200)`).
- **Stranger call blocking:** Caregiver switch `blockUnknownThreemaCallers` (default `true`, configured
  via Caregiver Portal) silently rejects incoming Threema calls from senders not found in the address book
  or imported contacts (`0x61 REJECT`), logging `THREEMA_CALL_FROM_STRANGER_ENDED` for caregiver review.
- **Telecom collision avoidance:** If an ordinary telephone call is active, incoming Threema call offers
  are immediately rejected with `0x61 REJECT` (reason `BUSY`).
- **Stale offer protection:** Incoming call offers with timestamps older than 60 seconds are dropped
  silently.
- **Incoming ringer:** `IncomingRinger` produces ringtone and vibration for self-managed Telecom calls,
  strictly obeying Do Not Disturb, system ringer modes (Silent/Vibrate/Normal), hardware volume keys, and
  the caregiver-configured silence timeout. Missed incoming calls timeout after 45 seconds without busy tone.
- **Bridge packet flags:** Outgoing hangup messages (0x63) carry `0x01` (send push);
  all other VoIP messages (0x60, 0x61, 0x62, 0x64) carry `0x21` (send push | short-lived: not queued on the server), as Threema's own app does.

## 15. Photo sending (taking and sending her own photos)

*Added 2026-10-22.*

Allows her to take a photograph herself and send it to a contact over Threema or WhatsApp. Full specification, screen interaction details, caregiver switches (`enablePhotoSending`, `enableCameraSwitch`, `photoButtonPlacement`), and privacy guarantees (automatic EXIF and GPS stripping, long-edge downscaling to 1600 px) are documented in [docs/photo-sending.md](docs/photo-sending.md).

## 16. Steps

*Added 2026-10-22.*

Shows how many steps she has taken today and yesterday at the very bottom of the home screen (after the Look section, before the final bottom spacer) so she has to deliberately scroll to reach it.

- **Hardware sensor & lifecycle:** Uses `Sensor.TYPE_STEP_COUNTER` with 60 s batch latency (`SENSOR_DELAY_NORMAL`), kept registered in `MessengerService` so the counter keeps accumulating while the phone sleeps and midnight rollovers are noticed.
- **Calm presentation:** A phone on the table counts nothing, so step counting is never gamified or demanding. Today's count is only shown as a number once >= 3'000 steps; below 3'000 it quietly reads "wird gezählt …". Yesterday's count is always shown as a number when known (including under 3'000 and 0). If yesterday is unknown (first day after install or gap > 1 day), the yesterday row is omitted entirely.
- **Number format:** Numbers always use an apostrophe as the thousands separator (`4'512`, `12'030`, `812`), independent of system locale.
- **Privacy & offline only:** Purely local. Nothing leaves the phone; step counts are never transmitted to Firestore, the caregiver portal, or any external service.
- **Caregiver control:** Switch `showSteps` (default `true`) allows caregivers to hide the section completely if showing step numbers creates unwanted pressure.
- **Permissions & setup:** Requires `android.permission.ACTIVITY_RECOGNITION` on Android 10+ (API >= 29). Granted by caregivers via ADB (`pm grant ch.heuscher.gentleconnect android.permission.ACTIVITY_RECOGNITION`) on her phones so she never sees a permission prompt dialog.

