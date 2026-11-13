# Support button — spec

Status: **agreed design, not implemented** (2026-09-17).

Replaces the "?" help button (`ui/components/HelpButton.kt`) on every screen. The "?" explained
the screen she was on; this button gets her a person. The screen explanations are dropped.

*On every screen* (note 2026-10-05): it is now also in the album viewer (`BurstViewer`), the
full-screen photo (`PhotoViewer`) and film (`FilmPlayer`), and on the call screen
(`GentleCallScreen`); on a call an already-sent request does not open the status screen (the tile
already shows the green tick, and a call must not be left for it). Not on: the call-failed card
(gone in 10 s, any touch closes it), the find-my-phone screen, the caregiver screen, the status
screen itself.

## 1. Decisions

| Question | Decision |
|---|---|
| What the button shows | The face of the first person in the support group, in a blue ring |
| Who is told | **Everyone** in the support group at once; the first to respond **claims** it |
| A group member calls her | Counts as a claim; the rest of the group is told |
| Channel | Messenger (Threema / WhatsApp) first, **SMS as backup — also while roaming** |
| What the alert carries | Time, screen, **location** and **phone status** |
| Confirmation | One calm question; nothing ever happens by itself |
| Emergency services | Never dialled automatically |

## 2. The button

### Look

- Same place and size as the "?" today: the round 56 dp control at the right-hand end of the header
  (`GentleScreenHeader`, and the Home clock row balanced by `HelpButtonSize`). Ring and badge fit
  **inside** the 56 dp so the Home spacer stays as it is.
- **Face**: the photo of the first support-group member, cropped to a circle.
- **Ring**: 4 dp, solid, a blue used for nothing else in the app.
  - Light: `#1F5FA8` (6.0:1 against `GentleCream`).
  - Dark: `#8AB4F8` (8.8:1 against `GentleDarkBg`).
  - Add as a theme colour of its own (`SupportBlue` / `SupportBlueOnDark`), not as a Material
    role — no other component may pick it up.
- **Why blue**: green is her own actions and the back button at the other end of the same row,
  amber is other people / unread / missed, terracotta is warnings, red is hang-up. Blue has no
  meaning yet, stays distinct from green for red-green colour blindness, and reads as
  "assistance" rather than "alarm". Mid blue, not navy: an ageing lens turns dark blue black.
- **Why not the favourite faces' look**: favourites are amber-framed and live only in Home's list.
  The blue ring plus the fixed header position is what separates "call this person" from "get
  me help".
- **No photo configured**: a bold two-hands symbol, white on a solid `SupportBlue` disc (6.4:1).
  Drawn thick — fine lines vanish at 56 dp for low vision.
- **No support group configured**: the button is not shown; the header keeps a 56 dp spacer so
  nothing moves. A button that cannot do anything is worse than none.
- Content description: "Hilfe holen" / "Get help".

### States

| State | Button |
|---|---|
| Idle | First member's face, blue ring |
| Sent, not yet claimed | Same, plus a small green tick badge at the lower edge |
| Claimed | The **claimer's** face, blue ring, green tick — for 60 min after the claim, or 30 min after a call with the claimer ends, whichever is later |
| Sending failed | **No change on the button.** Failure is shown on the status screen only, never as a warning colour on every screen |

No blinking, pulsing or animation on the button in any state (the restlessness beacon aside).
The status screen (3.3) is the one place something moves, and only to say who is coming.

## 3. Pressing it

### 3.1 The question

A dialog in the shape of `GentleConfirmDialog`:

- The faces of the whole group (sliding sideways when they do not fit), and one sentence:
  **"Soll deine Familie Bescheid bekommen?"** — "Familie" is the portal-configurable group name.
- Buttons: **"Ja, Bescheid geben"** (large, primary, at the **bottom** of the dialog) and
  **"Nein"** (quiet, above it).
- **Anti-panic rules**
  - "Ja" is never under the finger that opened it: the button is top right, "Ja" bottom.
  - All input is ignored for the first 500 ms after the dialog appears, so a burst of taps
    cannot confirm.
  - No red, no countdown, no warning words.
- **Timeout 60 s → nothing is sent.** Same rule as the call dialog: the clock must never do what
  she did not. The unconfirmed opening is written **quietly** to the portal log ("opened help at
  14:32, did not confirm") — visible there, no notification.

### 3.2 Pressing again

She will forget she pressed.

- **Open, unclaimed request**: the button opens the status screen (3.3) instead of the question.
  It carries "Noch einmal Bescheid geben", which re-alerts everyone — at most once per 5 min;
  within the cooldown the button is simply not there.
- **Claimed request**: status screen, showing who is on it. "Noch einmal Bescheid geben" is
  available here too (same cooldown).
- **No open request**: the question (3.1).

### 3.3 Status screen

Full screen, not a dialog. Replaces where she was until she presses "Zurück".

- Large faces of the group. A face the message reached has a slow breathing blue ring (about 3 s a
  breath, faint — no spinner, no ticking dots); a face it could not reach is dimmed, so faces and
  headline agree.
- Once claimed: the ring stops, the claimer's face grows to 160 dp in the middle with a green ✓,
  the others shrink and fade, and the phone buzzes once. With the system's "remove animations"
  setting, the end state without the motion.
- Headline, in this order as things happen — never "ich", which in the family's messages is her:
  1. "Nachricht geht an Anna und Johann …"
  2. "Anna und Johann wissen Bescheid" (the names of those it reached), with underneath
     **"Wer zuerst kann, meldet sich bei dir."** — who is coming before anybody has answered
  3. "Anna hat es gelesen" — only where a real read receipt exists (Threema)
  4. "Anna schaut es mit dir an"
- If nothing could be sent yet: "Das Telefon versucht es weiter" — never an error message.
- **No call button** (removed 2026-09-18): dialling is what she finds hard; they call her.
- "Vorlesen" button, styled like the others (reads the headline and the line under it); nothing
  speaks unasked.
- "Zurück" (large, bottom).

## 4. Who is told, and how

### 4.1 The support group

Configured in the caregiver portal, stored in `RemoteSettings`.

**Picked from her favourites, no upper limit** (changed 2026-09-17). The portal shows the starred
contacts with a tick box each and a "Auf dem Hilfe-Knopf" choice among the ticked ones; that person
is saved first. The portal stores only `{id: "contact-<contactId>", contactId, name}` — it never
sees her address book — and the phone fills in number, contact photo and Threema ID from its own
contacts (`SupportGroupResolver`). WhatsApp is used only for a number the bridge has a cached
profile picture for, since a send to a number not on WhatsApp could count as sent and hold back
the SMS. Hand-typed members (all fields filled in) still work unchanged.

**Every number a person has is considered** (added 2026-09-17, after it went wrong on her own
telephone). `Contact` carries one number, and which one is decided by the order the contacts
provider returns rows in — no preference for a mobile. For her son that was an office landline,
and everything hung off it: the Threema directory was asked about the landline (no match, so no
Threema), the WhatsApp check looked for a cached profile picture for the landline (none, so no
WhatsApp), and the alert fell back to an SMS to a line that cannot receive one. He was in the
support group and could not be reached at all, and nothing said so. For her daughter the same
order picked a second mobile she is on neither messenger under.

So `SupportGroupResolver` now takes every number of each member (`ContactPhone`, read with its
`Phone.TYPE`) and:

- **writes to the mobile** — `ContactPhones.bestForMessages`: a typed mobile first, then an untyped
  number, and a landline only when it is all there is. That number is also what her button dials,
  and a call to a landline is a perfectly good call.
- **takes the Threema ID from whichever of their numbers the directory knows**, and the WhatsApp
  number from whichever one has a cached profile picture.
- **remembers the rest**, so a family member who rings her back from the office is still
  recognised as the person who was alerted — `memberFor`, which is what claims the request.

The Threema directory sync was making the same mistake from the other end: it submitted one number
per contact, so a second number was never looked up. It now submits every number.

```kotlin
data class SupportMember(
    val id: String,
    val name: String,             // "Anna" — used in her screens and in the messages
    val phoneNumber: String,      // E.164; required, it is the SMS backup — the mobile, if there is one
    val threemaId: String? = null,
    val whatsAppNumber: String? = null,
    val buttonPhotoUri: String? = null, // close-up face; only the first member's is shown on the button
    val otherNumbers: List<String> = emptyList() // so a call back from any of them is recognised
)

val supportGroup: List<SupportMember> = emptyList()   // order = display order; first = button face
val supportGroupName: String = ""                      // "Familie"; empty → "Familie"/"family"
val supportPersonName: String = ""                     // how messages name her: "Mama"
val supportShareLocation: Boolean = true
```

The button shows the contact photo from her address book; a group photo or a dark picture is
useless at 56 dp, so pick the button person with a clear close-up.

### 4.2 Sending the alert

On "Ja":

1. Create the request (4.5) with a fresh id.
2. Capture phone status (4.4) and ask for a location fix (4.4) **in parallel**; do not wait more
   than 20 s for the fix — send with the best known one and its age.
3. For **every** member, send on their messenger: Threema if `threemaId`, else WhatsApp if
   `whatsAppNumber`, via the existing on-device send paths (in-app WhatsApp / Threema).
4. **SMS backup**, per member, via `SmsSender`, when any of:
   - the messenger send was not confirmed as sent within **60 s** (outbox ack / Threema
     server ack),
   - the phone has no internet (`CallRouter.hasInternet` false) — send SMS immediately,
   - the member has no messenger configured.
   **Roaming does not suppress it.** `threemaOnlyWhenRoaming` is about call cost; a help request
   is worth an SMS abroad. This is an explicit exception and must be commented as such where the
   decision lives.
5. Nothing sent at all (no internet, no signal): retry every 2 min for 30 min, then every 10 min
   until 2 h, re-evaluating messenger vs SMS each time. The status screen says "Ich versuche es
   weiter".
6. Publish the request to Firestore when online; the portal raises a browser notification with
   the existing `Notification` path (no cooldown for support requests).

### 4.3 Message text

**Written in her voice, because she reads it too.** Every alert is sent from her account, so each
one also lands in her own thread with that person, and she reads it there — days later, as
something she said. In the third person it is a stranger describing her out of her own mouth, in
her own conversation. So: "Ich bitte um Hilfe", "meine Hilfe-Anfrage", "ruf mich an". That the
telephone sent it by itself is said outright, or the family reads a message she sat down to write.

**What is not in it:** which screen she was on, whether the messengers were connected, and the
day's faults. Those are the caregiver's questions and they are answered on the portal, which has
the room and the reader for them. In a family chat they are noise; in her own thread they are
somebody inspecting her. The battery and the network stay, because they change what a reader
does about it.

**Messenger** (no length limit):

```
🆘 Ich bitte um Hilfe.
Ich habe um 14:32 den Hilfe-Knopf gedrückt.

📍 https://maps.google.com/?q=47.37690,8.54170  (±30 m, vor 1 Min.)
📱 Mein Telefon: Akku 23 % · WLAN

Das schickt mein Telefon automatisch an Anna, Beat und Carla.
Wer sich kümmert, antwortet mit „ok" oder ruft mich an – dann wissen es die anderen.
```

Named recipients rather than "alle aus der Familie": it tells each of them who else may already
be on the way, and "everybody got this" was not true the day one SMS failed. Without names (an
older settings document) it falls back to "an alle in „Familie“".

**SMS** (≤ 2 GSM-7 segments, no emoji — they force UCS-2 and halve the length):

```
Ich bitte um Hilfe. Ich habe um 14:32 den Hilfe-Knopf gedrückt. Ort: maps.google.com/?q=47.3769,8.5417 (30m, vor 1 Min). Akku 23%, kein Internet, Roaming. Mein Telefon schickt das automatisch. Antworte ok oder ruf mich an.
```

Sections whose data is missing are left out, not filled with "unbekannt". Location only when
`supportShareLocation` is on.

**Where the sentences live:** `values/strings.xml` and `values-de/strings.xml`, like every other
string in the app, resolved by `supportTextsOf(context)` and handed to the pure formatter as
`SupportTexts`. They used to be German and English literals in `model/` behind an `isGerman`
parameter that no caller ever passed, so the English half had never been sent once. `model/` may
not import `android.*` — that is what lets its tests run without the Android toolchain — hence the
detour rather than `getString` at the point of use. The unit tests read `values-de/strings.xml`
itself, so the wording under test is the wording that ships.

**The claim notices are in the same voice**, and she reads those too:

```
✅ Johann kümmert sich um meine Hilfe-Anfrage von 14:32.
📞 Johann hat um 14:36 mit mir telefoniert und kümmert sich um die Hilfe-Anfrage von 14:32.
```

### 4.4 Location and phone status

**Location** — one-off, at the press:

- `PhoneLocationSource` as the heartbeat uses it: last known first, one fresh network reading if
  that is stale, GPS as fallback, folded through `PhoneLocation.betterOf`.
- No subscription is left open afterwards.
- Accuracy radius and age always accompany the coordinates.
- A later, better fix within 5 min of the request is sent as a short follow-up
  ("📍 Ich bin genauer hier: … ±12 m") on the messenger only — never a second SMS.

**Phone status** — pure snapshot, no new permissions:

- Battery % and charging.
- Connection: WLAN / mobile / none; roaming yes/no; cellular voice service yes/no.
- Messenger links: Threema connected, WhatsApp bridge connected.
- The screen she was on when she pressed.
- Recent faults: up to 3 caregiver-log entries from the last 24 h that `CaregiverAlerts` would
  publish (already anonymised).
- App version code.

### 4.5 Firestore

`devices/{deviceId}/supportRequests/{requestId}`:

```
createdAt, screen, status: "open" | "claimed" | "closed",
claimedBy: memberId?, claimedVia: "reply" | "call"?, claimedAt?,
location: { lat, lon, accuracyMetres, at }?,          // only if sharing is on
phoneStatus: { battery, charging, network, roaming, threema, whatsapp, faults[], appVersion },
deliveries: [ { memberId, channel: "threema"|"whatsapp"|"sms", state: "sent"|"failed"|"read", at } ],
repeats: [ at, … ]
```

Rules: writable by the device, readable by the device's caregivers — same pattern as the
caregiver log. Offline requests are written when the connection returns; SMS does not wait
for that.

## 5. The claim

### 5.1 What counts as a claim

While a request is open (not yet claimed, < 2 h old), the first of these from any member:

- **A call that connects** between her and the member, either direction, any transport
  (telephone, Threema, WhatsApp). "Connects" = reaches `STATE_ACTIVE` / talking, not ringing.
- **A reply** to the alert on the channel it came in on (messenger or SMS):
  - a quoted reply to the alert that does not say no, or
  - a **yes reaction** to the alert, on Threema or WhatsApp: `👍` `👌` `✅` `✔️` `☑️` `🙏`, any skin
    tone. Not `👎` (also what an older Threema client's "decline" arrives as), `❓`, `😢` or `❤️`.
    A reaction to any other message is about that message, never a claim, or
  - a text that opens with a yes (`ok`, `ja`, `komme`, `rufe`, `ich kümmere`, `ich komme`,
    `bin unterwegs`, `bin gleich da`, `👍`, `✅` …) and has no no in it (`nicht`, `nein`,
    `später`, `leider`). "ich" or "bin" alone is not a yes: "Ich kann gerade nicht" and "Bin im
    Zug" used to claim, and a claim tells everybody else to stand down (changed 2026-09-18).
- **The portal**: a "Ich kümmere mich" button on the request.

A missed call from a member does not claim it (it goes on the request as "Anna hat versucht
anzurufen", shown on her status screen).

Replies from group members that are claims are **not** shown to her as ordinary messages in the
conversation list — she sees them as "Anna kümmert sich" on the status screen.

### 5.2 On a claim

- Request → `claimed`, `claimedBy`, `claimedVia`.
- **Everyone else in the group is told**, on the channel(s) their alert went out on:
  - by reply: "✅ Anna kümmert sich um Mamas Anfrage von 14:32."
  - by call: "📞 Anna hat mit Mama telefoniert (14:36). Anfrage von 14:32 ist erledigt."
  - by SMS the same text without emoji.
- Her button and status screen switch to the claimer (2, 3.3).
- A second member claiming later changes nothing on her side; they get
  "Anna kümmert sich schon (seit 14:36)." in reply.

### 5.3 After a claim

- Calls from **any** group member ring through quiet hours, Do Not Disturb and
  `blockUnknownCallers` for **2 h** from the request. Call routing (`CallRouter`, roaming rule)
  is otherwise unchanged.
- The request closes itself after 2 h, or from the portal.
- "Noch einmal Bescheid geben" on a claimed request opens a new request linked to the old one
  (`repeats`) and alerts everyone again, including the claimer.

## 6. Restlessness detection

An elderly parent with cognitive decline often cannot put distress, confusion, or disorientation into words, but the telephone can perceive the shape of it from touch rhythm, screen navigation, and physical device handling.

### 6.1 Signals and scoring

Distress signals are weighted by certainty:
- **Hard signs (3 base points)** — clear indicators that fire on their own during the day (`FIRE_SCORE = 3`):
  - **Burst tapping**: Four rapid taps in 2.5 seconds (`BURST_TAPS`), indicating panic or hammering an unresponsive surface. Keys on the dialpad (`KeypadTapZone`) and the faces in the reaction row (`ReactionTapZone`) are not counted as taps, here or for nocturnal activity: fluent dialing and tapping 😂 several times in amusement are normal use. They stay answered touches, so they still clear the unanswered-touch streak.
  - **Repeated call retries**: Calling the same person 3 or more times within 10 minutes (`CALL_RETRIES`) when calls fail or drop.
  - **Unconfirmed support loops**: Opening the support dialog repeatedly without confirming (`UNCONFIRMED_SUPPORT`), reflecting hesitation or disorientation.
  - **Unanswered touches**: Five rapid touches within 10 seconds that produce no UI reaction (`UNANSWERED_TOUCHES`).
  - **Same-spot dead taps**: Tapping repeatedly on the exact same dead area (3 taps within 48 dp within 10 s, `SAME_SPOT_DEAD_TAPS`), mistaking non-interactive elements for buttons.
  - **Exit hunting**: Tapping where the back arrow would be on other screens (top corners: height ≤ 140 dp, width ≤ 120 dp) or pressing the system back button without effect 3 times within 20 s (`EXIT_HUNTING`), searching for a way out.
  - **Nocturnal activity**: Touch activity during quiet hours (`NOCTURNAL_ACTIVITY`), 3 taps within 1 minute.
- **Soft signs (2 base points)** — subtle behavioral cues that do not trigger an alarm alone during the day, but correlate over a 2-minute sliding score window (`SCORE_WINDOW_MS = 2 min`, needing 3 points to fire):
  - **Navigation loops**: Hopping between screens without dwelling (5 consecutive screen transitions within 3 s of each other, `NAVIGATION_LOOP`).
  - **Fold cycles**: Repeatedly opening and closing the folding phone (3 transitions within 60 s, `FOLD_CYCLES`).
  - **Screen cycles**: Turning the screen on repeatedly without taking action (3 times in 3 min, `SCREEN_CYCLES`), ignoring screen-on events within 2 s of unfolding.

### 6.2 Learned quiet hours and nocturnal doubling

Rather than relying purely on fixed night clocks, `QuietHours` learns her personal circadian rhythm across a 14-day history window. An hour is deemed quiet if touch activity occurred on at most 1 day out of the last 14 (with a 7-day initial learning floor). Deep night hours (01:00 to 05:00) always remain quiet by policy, so nocturnal disorientation can never be learned away as normal.

During a quiet hour, detection points for every signal except `NOCTURNAL_ACTIVITY` are doubled (2 points become 4 points). This allows soft signs like navigation wandering or fold cycles to fire an alert on their own at night.

Each signal clears its own buffer when it detects, and a tap rule that detects takes the taps with
it, so the same taps are never counted twice by two rules.

### 6.3 Click-through exclusion

When transitioning between screens, momentum taps that land where a button just was on the previous screen within 700 ms (and within 400 ms of the previous tap up, inside 120 dp radius) are classified as click-throughs. They are excluded from dead-tap and burst counts, preventing normal rapid navigation from tripping false alarms.

### 6.3a Grip touches at the rims

Unanswered taps within 24 dp (`EDGE_GRIP_DP`) of the left or right window edge are grip touches: her thumb or palm resting on the rim while she holds the unfolded Fold. They are left out of the unanswered-touch, same-spot, burst and night counts and only still count towards exit hunting in the top corners. Answered taps at the rims are unaffected, and the right edge needs a known window width. Added after the 2026-11-05 23:38 `UNANSWERED_TOUCHES` false alarm (taps at x = 636/637 dp on a 640 dp window and 34/9/16 dp on the left). Replaying the Fold app.log of 10-18 to 11-05 removes exactly that episode (16 → 15) and leaves every other one in place.

### 6.4 Goal resolution

Confusion often subsides once she reaches her goal. If a constructive action occurs within 15 seconds (`RESOLVE_MS`) of an agitation fire — specifically when a phone call connects or an outgoing text/photo message is sent — restlessness is immediately settled:
- The pending caregiver log event (delayed by 15 s) is cancelled, preventing false-alarm log entries.
  The 10-minute log throttle is only consulted once the wait is over, so a resolved episode does
  not silence a real one shortly after.
- The trail sidecar file of the snapshot burst is appended with `HH:mm:ss.SSS resolved <goal> after <N> s`.

### 6.5 Replay verification against Fold traces

To protect against false positives and regressions, `RestlessReplay` replays raw device touch trails and logcat dumps through `AgitationTracker`. Verified fixture baselines include:
- `SAME_SPOT`: 2026-10-24 trail where dead taps near a conversation bubble trigger `SAME_SPOT_DEAD_TAPS`.
- `EXIT_HUNTING`: 2026-10-26 trail where top-corner tapping on the Look screen triggers `EXIT_HUNTING`.
- `CLICK_THROUGH`: 2026-10-27 trail where consecutive screen transitions correctly identify 2 momentum click-throughs and stay quiet by day.
- `CALM`: 2 minutes of real reading and messaging that produce zero agitation triggers.
- `EDGE_GRIP`: 2026-11-05 23:38 trail where rim touches while reading the home screen stay quiet (4 grip touches).
- `REACTION_BURST`: 2026-10-31 trail where 😂 tapped four times in under a second stays quiet. A trail has no zones, so replay recognises a reaction by its whole label (`click "😂"`, one of `QuickReactions.FACES`).

Replaying a multi-day app trace log (about 3000 UiTrace lines) gave two
episodes: the real 10-24 struggle and a same-spot alarm on 10-25 14:22 while she browsed photos. The
tap that opened the photo counted as dead because the photo's tap detector had no click semantics;
`MessagePhoto` now carries one, so UiTrace sees that tap as answered.

## 7. What is removed

- `HelpDialog`, `HelpAffordance`, the `showHelp` state on every screen.
- Strings `help_title`, `help_home_text`, `help_messages_text`, `help_conversation_text`,
  `help_phonebook_text`, `help_appointments_text`, `help_keypad_text`, `help_recent_calls_text`,
  `help_look_text` and their `values-de` counterparts.
- `GentleScreenHeader` parameters `helpText`, `isHelpOpen`, `onOpenHelp`, `onCloseHelp`,
  `onSpeakRequested` become one `support: SupportButtonState` + `onSupportPressed`.
- Unchanged: the five-tap caregiver gesture on the title/clock (must still not be reachable by
  taps aimed at the button).

## 8. Out of scope

- Automatic calls to emergency numbers. 144/112 stay her own choice; `CallPlacer` already routes
  them over the mobile network.
- Robot/automated voice calls to members.
- Ordered or time-of-day escalation (everyone-with-claim was chosen).
- Screen sharing / remote control.
- Limits for frequent presses ("only Anna, quietly, until tomorrow") — revisit once real usage is
  known.

## 9. Testing

**Pure, JVM-testable** (in `model/`, runnable locally):

- `RestlessReplay` — replay real foldable touch traces and logcat lines against `AgitationTracker`.
- `AgitationPolicy` — sliding window scoring, hard vs soft signs, click-through detection, goal resolution.
- `QuietHours` — 14-day circadian history, learned quiet hours, nocturnal preservation.
- `SupportChannelPolicy` — messenger vs SMS per member; internet / no internet / roaming /
  no messenger / 60 s unconfirmed. Roaming must never suppress SMS.
- `SupportClaimPolicy` — which inbound events claim; quoted reply, reaction, keywords, connected
  vs missed call, expiry after 2 h, second claimant.
- `SupportMessageText` — messenger and SMS text, missing sections left out, SMS stays GSM-7 and
  ≤ 306 chars with the longest realistic values.
- `SupportRequestCooldown` — 5 min re-alert rule.

**On the device** — the test group must contain **only test numbers / test accounts, never a
family member**. Every confirmed press sends real SMS and messages, and a claim test places real
calls. Never drive the confirmation with chained `adb` taps.

- Button contrast and ring in light, dark and battery-saver dark, indoors and outdoors.
- Burst-tapping the button does not confirm.
- Aeroplane mode with signal: SMS goes, messenger follows when online.
- Roaming SIM: SMS backup still goes.
- Claim by call from each transport informs the others.

## 10. Open points

- Keyword list for text claims in other languages if a member writes in English.
- Whether a Threema "read" receipt alone should enlarge that member's face before a claim.
- Portal UX for picking the button photo (crop in browser vs upload a pre-cropped image).
