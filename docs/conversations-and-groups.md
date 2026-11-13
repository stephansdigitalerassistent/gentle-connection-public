# Messages as conversations, and Threema groups

Status: **Stages 0, 1, 2 and 3 are built, and so is the caregiver's error log.**

Two changes that belong together, because the second is impossible without the first:

1. **Messages become conversations.** What she sent is kept and shown alongside what she
   received, per person, and survives a restart.
2. **Threema groups become real.** Several people in one conversation, with replies that reach
   all of them.

## The word stays "Messages"

The structure changes; the vocabulary does not. **"Chat" must never appear in user-facing copy**,
in either language. She reads *Messages* / *Nachrichten*, the same as today, and the nav tab is
untouched. "Conversation" is a word for this document and for type names, not for her screen.

The reason is not fussiness. Every word on her screen is one she has to recognise under time
pressure, and the set of words she already recognises in this app is small and hard-won. Adding a
synonym for something she already has a word for is a pure cost.

Same for groups: **never "group", never "Threema group"**. Several people are several people —
see "How a group is described to her" below.

---

# What is broken today

This is not a greenfield feature. Three things are already wrong, and one of them is a live
correctness bug that should be fixed before anything here is built.

### 1. A group message looks like a private message, and replying answers one person

**Fixed in Stage 0.** The bridge already emits group messages fully identified — `native/threema-bridge/src/main.rs`
handles `IncomingMessageBody::Group` and sends `group_creator` plus `group_id`. `ThreemaClient`
parses both into `ThreemaIncomingMessage`.

**Nothing else in the app reads either field.** `OnDeviceMessageMapper.fromThreema` uses only
sender, text, timestamp and message id, so a message someone sent to a group of eight people
arrives on her screen indistinguishable from a private message. If she answers it, the reply goes
to `senderId` — one person — and everyone else in that group never sees it.

She has no way to detect this. She answered what looked like a private message and got a private
reply. This is the worst shape of bug this app can have.

### 2. Nothing she sends is kept

**Fixed in Stage 1.** `MainViewModel.sendNewMessage` routes to `ThreemaClient.sendTextMessage`, returns a `Boolean`, and
stores nothing. `Message` has no outgoing concept at all — no direction field. There is nowhere
for a sent message to live even if we wanted one.

For someone whose memory is fading, this is worse than untidy: having replied and having no
evidence of it is exactly the situation that produces the same message sent three times, or a
daughter left unanswered because she could not remember whether she had answered.

### 3. Received Threema messages do not survive a restart

**Fixed in Stage 1.** `onDeviceMessages` is an in-memory `MutableStateFlow`. Bridge messages come from Firestore and
persist; SMS comes from the system. On-device Threema messages exist only as long as the process.

### 4. Group control messages are discarded

**Fixed in Stage 2.** `GroupMessageBody::Unknown` carries group setup, rename and membership
changes, and the bridge forwards them on `unknownMessages`. **Nothing collected that flow** (`VoipCallManager` collects
`callMessages`; nothing collects `unknownMessages`). That is where group membership and group
names would come from, and today it is dropped on the floor.

---

# Stage 0 — Stop the wrong-recipient reply — **done**

**Do this first, on its own, before any of the rest.** It is small and it removes a live harm.

- `OnDeviceMessageMapper` carries `groupCreator`/`groupId` through onto `Message`.
- A message that has a group identity was **not answerable** at all: `canBeAnswered` returned
  false, and the reply affordance was simply absent. Stage 3 narrowed that to the groups whose
  membership we do not know, which is the part of the rule that was always load-bearing.
- **No explanation appears on her screen** — see "Errors are the caregiver's business". The
  caregiver sees why; she just sees a message she reads rather than answers.

Being unable to answer is survivable. Answering the wrong person is not.

---

# Stage 1 — Keep what she sends and what she receives — **done**

## Storage

One Room table, `messages`, holding both directions and every transport.

| column | notes |
| --- | --- |
| `id` | stable, source-prefixed (`threema_`, `bridge_`, `sms_`) — the existing dedup key |
| `conversationId` | derived, stored for query; see below |
| `direction` | `INCOMING` / `OUTGOING` |
| `counterpartId` | the other person's Threema ID or phone number, in both directions |
| `counterpartName` | resolved at write time, but see the note on renames |
| `text` | |
| `timestamp` | |
| `isRead` | incoming only |
| `sendState` | `PENDING` / `SENT` / `FAILED`; outgoing only |
| `transport` | for routing a reply, never for display |
| `groupCreator`, `groupId` | null for a one-to-one conversation |

One deviation from the table as first written: the sender columns became *counterpart* columns.
For a message of hers the sender is her, and what the conversation needs to know is who is at the
other end — otherwise a conversation she started, before any reply arrives, has no name to show.

Migration `4 → 5`, written out by hand. `fallbackToDestructiveMigration` stays as the last resort
but must not be what runs: dropping this table would erase the only copy of her conversations.

## Conversation identity — the load-bearing decision

**One person is one conversation, even when they reach her two different ways.** Her daughter
texting from a train and writing on Threema from home is one person and belongs in one place.

The key is the contact she is matched to, falling back to the raw address when she is matched to
nobody:

Derived on every read rather than trusted from the row, with the stored column as the fallback.
A Threema message that arrived before contact sync had run was filed under a bare identity; once
the directory answers, re-deriving moves those messages to join the later ones instead of leaving
the thread split in two.

```
conversationId = "contact:<contactId>"        when the sender resolves to a contact
               | "threema:<identity>"         when it does not
               | "phone:<e164>"
               | "group:<creator>:<groupId>"  always, for a group
```

**Merge only on a directory-confirmed link.** `ThreemaNumberMatcher` gives identities the Threema
directory itself resolved from a phone number; `ThreemaContactMatcher` gives identities guessed
from a name. A wrong merge silently splices two people's conversations into one thread — her
doctor's messages appearing under her sister's name — which is precisely the invisible failure the
name matcher is already written to avoid. So the name match may fill in a contact's `threemaId`
for *routing*, but it must not be sufficient to *merge two conversations*.

## What she sees

The Messages screen stays a flat list of what arrived, newest first, exactly as today. That list
is the thing she can read without deciding anything, and it should not become a list of names she
has to navigate.

**Tapping a message opens that person's conversation**, which is new: her messages and theirs,
oldest at the top, newest at the bottom, with the reply field she already knows at the bottom.

- Her own messages are visually distinct but **not** by a label — alignment and background,
  no "You:" prefix.
- Her own messages are not repeated in the flat Messages list, which stays a list of what
  arrived.
- No delivery ticks. See below.
- No timestamps on every message; a day separator only, in the wording already used elsewhere
  ("Yesterday" / "Gestern").

## Honesty about sending

`sendTextMessage` returns whether a line was written to the bridge subprocess. **That is not
delivery, and must never be drawn as delivery.**

- On send, the message appears immediately with `PENDING`.
- `SENT` means the bridge accepted it. Nothing in this stage may claim the recipient received it.
- `FAILED` is **the one place where something must still be visible to her**, and it is the
  documented exception to the rule below. Not an error message and not a warning — the message
  simply stays visibly unfinished, in the way a half-written note is obviously half-written.

The reason for the exception: everywhere else, hiding an error costs her an explanation she
probably would not have wanted anyway. Here, hiding it means her message sits in the conversation
looking exactly like one that arrived, and she believes she has answered her daughter when she has
not. Silence would not be discretion; it would be a false statement about something she did.

The caregiver still gets the actual reason, and the retry.

Threema delivery receipts (CSP `0x80`) would let us say more, and are deliberately deferred to
their own stage. A tick she cannot trust is worse than no tick.

---

# Stage 2 — Threema groups, inbound — **done**

## Identity

A Threema group is identified by **(creator identity, group id)** — not by name, which changes,
and not by membership, which changes. The bridge already emits both;
`group_id` is hex, little-endian, from `group_identity.group_id.to_le_bytes()`.

## Membership and names come from the control messages

### The libthreema bug this had to be written around

`libthreema` decodes **every** message type at or above `0x40` by assuming the body begins with an
8-byte creator identity followed by an 8-byte group id. That holds for a group *member's* message,
and for group-leave. It is **wrong for group-setup and group-rename**, whose bodies begin with the
group id directly — the creator is the sender, so it is not repeated.

The saving grace is that the misread fails loudly rather than quietly: eight random group-id bytes
almost never spell a valid Threema ID, so the decode returns nothing and those messages arrive as
plain contact messages with **no group identity attached at all**. `GroupControlParser` therefore
takes the creator from the sender and reads the body itself, exactly as `MessageCoder` in the
Threema app does. The bridge's `group_creator` / `group_id` fields must not be trusted for these
two types.

Collect `ThreemaClient.unknownMessages` and interpret the group control types:

- **group setup** — the authoritative member list, from the creator. This is the only message
  that tells us who is in a group.
- **group name** — the name, when the group has one.
- **leave** — a member departing.

Two Room tables: `threema_groups` (creator, groupId, name, lastSetupAt) and `threema_group_members`
(creator, groupId, identity). Migration `5 → 6`.

A setup message **replaces** the membership rather than merging into it. Merging would look tidier
and would be wrong: somebody removed from the group would stay in it here for ever, and a Stage 3
reply fanned out to that stale list would reach a person the family had deliberately taken out of
the conversation. The name and the membership are written by separate narrow updates, because the
two facts arrive in separate messages in either order and a whole-row replace would blank whichever
one the current message says nothing about.

## How a group is described to her

Never as a group, never with the messenger's name. In order of preference:

1. The group's own name, if Threema gave us one — those are usually already human ("Familie").
2. Otherwise the members' names as she knows them: *"Anna, Peter and Ruth"*, falling back to
   *"Anna, Peter and 3 others"* past three. Names come from her address book first, the imported
   Threema backup second, the raw identity last.

**Inside a group conversation, every message shows who sent it.** This is the one place the
sender's name is load-bearing: in a one-to-one conversation she knows who is talking, and in a
group she does not.

## One of them, on their own — **built**

The faces at the top of a group conversation open a list of everybody in it, by name. That list
used to be the end of the road: faces, names, and a button that closed it.

But a group is where she most often wants *one* person. Somebody asked her something in the family
conversation and what she wants is to answer them, by voice. So a row in that list is pressable,
and pressing one opens that person on their own: their face, their name, **Ring Monika** and
**Write to Monika**.

- **Two presses to ring, and no third question.** The per-person dialog *is* the confirmation —
  it shows the face and puts the name in the button, which is everything `ConfirmCallDialog`
  asks. It keeps that dialog's clock, and running out rings nobody.
- **Not two controls on every row.** A telephone and a pencil down the edge of a scrolling list
  is the arrangement `ContactRow` refuses: one row means one thing, and a small control at the
  edge of a list she is scrolling is what once put a call through to a stranger.
- **The whole row is the target**, face and name together, because the name is the part she reads
  and aims at.
- **Writing happens in place**, in `ComposeMessageDialog`, rather than walking her into a
  one-to-one conversation that may have nothing in it. It opens with the cursor in the line and
  the keyboard up, and with **no emoji row**: she has already pressed twice to say she wants to
  write, and the faces that need no keyboard are under every conversation, which is where a
  message that needs no keyboard belongs. (Owner's decision, 2026-09-21.)
- **Only what will actually work is drawn.** `GroupMemberRoute` decides, before the buttons
  exist, whether there is anything to ring and anything to carry a message. Somebody the
  messenger named but gave no address for — a `name:` key, or a WhatsApp member behind a hidden
  number — stays in the list with their face and their name and is simply not pressable. No mark
  says so and no explanation is offered: which of her family this app happens to hold an address
  for is not her business.

Still out of scope, deliberately: adding or removing people, leaving a group, muting or blocking
one person, and showing anybody's number or identity.

Photos: the members' photos, overlapped, using the same avatar treatment as the favourites row.
**Not built, and deliberately.** The message list draws a generic person icon for everybody today,
including one-to-one messages, so overlapped photographs for groups alone would give several people
faces while her daughter writing on her own has none. This should follow whenever the message list
gains photographs at all, and not before.

---

# Stage 3 — Threema groups, outbound — **done**

This is the largest piece and the only one that needed new Rust.

## There was no group send

The bridge had `SendMessage`, `SendRaw`, `SendCall` — **no group send at all.** A Threema group
message is not one message to a group address; it is the same message sent to **every member
individually**, each wrapped in a group envelope carrying the group identity. The fan-out is the
implementation.

`BridgeInput::SendGroupMessage { creator, group_id, members, text }` encrypts and sends once per
member and answers with `GroupSendResult { delivered, failed }`. Per-member rather than a boolean
because the failure that matters is the partial one, and a single "sent" cannot express it.

The body is `creator(8) || group_id(8) || text`, under CSP type `0x41` — note that the creator *is*
repeated here, unlike in the setup and rename bodies of Stage 2 where the creator is the sender.
That asymmetry is the whole reason `libthreema` misdecodes those two, and getting it backwards here
would send eight bytes of group id as somebody's identity. One message id is shared by every copy,
and the flags are `0x11` (push, plus the group marker the Threema app still sets although
`libthreema` calls that bit reserved) — both checked against the Threema app's own
`OutgoingCspGroupMessageCreator` and `GroupTextMessage.getBody()` rather than guessed.

The bridge has no JVM tests behind it, so the wire layout is covered by Rust unit tests, which CI
now runs whenever the Rust changes.

## The failure mode to design against

If membership is incomplete, a group reply reaches some people and not others, **and looks
exactly like success**. Her message to the family reaches two of five and she has no way to know.

So: **she may only reply to a group whose membership we know authoritatively** — that is, one we
have seen a group setup message for. A group that existed before this app was installed, and has
been quiet since, is a group we do not know the members of, and it stays unanswerable, silently,
until a setup message arrives.

Partial fan-out is failure. Her message is marked `FAILED` and stays visibly unfinished; "sent to
3 of 5" is a fact she can do nothing with, and it goes to the caregiver instead. A fan-out nobody
answers for within 30 seconds is failure too, for the same reason.

`GroupReplyPolicy` is what decides, and it also decides *who*: the members from the last setup
message **plus the creator**, who is never in that list because the creator is the one who sent it.
Left out, the one person certain to be in the group would be the one person her reply never
reached. She is taken out of the list — her own message arriving back looking like somebody
writing to her is the last thing this app should produce.

The affordance follows the decision, silently: `Message.groupCanBeAnswered` is derived on read the
same way the group's name is, and `canBeAnswered` gates the reply field on it. A group she may not
answer simply has no reply field, with no explanation — see "Errors are the caregiver's business".

An emoji reaction takes the same path. To several people it is a message like any other, and it
either reaches all of them or counts as not sent.

---

# The shell: home summarises, the tabs hold the lists

Owner decision. This resolves the pull between decisions 1 and 2 — keep everything forever, but
bound what the home screen shows — without putting a cutoff inside the Messages list itself.

## Home

**Five favourite people, as a vertical list.** One person per row, large row, photo and name.
Not the horizontal strip it is today.

The strip is measurably losing people. On the device, home showed Claire-Lise, Karolina and Evelyn
with Evelyn cut off mid-photo; Johann, Laura, Sabine and Simon were off-screen to the right —
and those four are precisely the ones she can write to. Reaching them needs a sideways swipe on
content with no visible edge, which is exactly the affordance that does not survive fading memory.
A vertical list puts everything either on the screen or behind the one gesture everybody has.

Take Simple Phone's *layout* — vertical, one target per row, large — but **keep the photographs.**
Simple Phone's `FavoritesScreen` is a two-column grid of `BigButton`s with a generic
`Icons.Filled.Person` and no photos at all, and it is still backed by `MockData`. A generic icon is
fine for a dialer with a broad audience; for her a face is recognition and a name is reading, and
she already has real photographs on that screen.

**Five newest messages — from people only.** `SenderKind.PERSON` already exists and already decides
what is answerable; the same test decides what reaches the home screen. Machine messages stay in
the Messages tab.

This is not tidiness. Home currently previews *"ParkApp: your verification code is 999999"* and
*"Reinigung! Ihr Auftrag ORDER-1234 ist abholbereit"*. With an unbounded list that is clutter; with
exactly five slots it is fatal — one busy day of automated messages and her daughter's message
never appears on the home screen at all.

## The tabs

**Call** holds the full list of people, same row shape as home. **Messages** holds the full flat
list, including the machine messages home filters out.

Home and Call showing the same shape is correct rather than repetitive: they mean the same thing —
a shortlist and the full list of one act. The rule that matters is unchanged, that no *persistent*
list under Messages has a person as its row identity.

## A call is always confirmed

Owner decision. **Tapping a person never dials immediately.** It asks first, and the call starts on
the second, deliberate tap. This holds everywhere a person is tappable — home and the Call tab
alike, so the gesture means one thing in both places.

Simple Phone dials on the first tap, and "people are for voice" points that way, but a mis-tap
that silently rings her daughter at half past eleven is not recoverable by someone who may not
work out what she did or how to stop it.

Constraints on that confirmation:

- It is a **question, not a warning** — this is the modal she will meet most often, and it must
  never read as though something has gone wrong. It names the person and offers the call.
- It reuses the vocabulary already on her screen. No new word for calling.
- Both languages, identical key sets, in the same commit.

# Errors are the caregiver's business — **the log is built**

Owner decision. **Her screen carries no error copy at all.** Not "you cannot answer this just
now", not "not sent", not "no connection" — nothing that explains a fault to someone who did not
cause it and cannot fix it.

What replaces it:

- **Absence rather than explanation.** Something she cannot do is not offered. No reply field
  rather than a greyed one with a reason next to it.
- **A caregiver error log**, behind the existing five-tap gesture, alongside the Threema
  connection status. Newest first, plain language, with timestamps: failed sends and why, groups
  that cannot be answered and what is missing, connection failures, contact-sync failures,
  normalisation refusals. Everything currently visible only in `logcat`, which nobody looking
  after her will ever read.
- **One exception, documented above**: a message of hers that did not send stays visibly
  unfinished. That is not an error message; it is the honest state of something she did.

## What this changes that already exists

**Done in Stage 1.** `MessageRouter.SMS_ENABLED` is false today, and an SMS from a real person used
to show her *"You cannot answer this message just now. You can still call."* Under this decision
**that copy went**, along with `reply_not_possible`, in both languages in one commit — an orphaned
key in one language is how the two files drift. The reply affordance is simply absent instead.

The caregiver error log that receives those reasons is **Section 2 of the five-tap dialog**, named
"What did not work". Newest first, plain language, dated. It records failed sends, a fan-out that
reached only part of a group, each of the three reasons several people cannot be answered,
messenger connection failures, contact-sync failures, and contacts whose number could not be read
and were therefore never looked up.

Three decisions inside it, each guarding a way of failing quietly:

- **It is on disk, not in memory.** A launcher process is killed and restarted constantly, so an
  in-memory log is empty almost exactly when somebody finally looks at it — and the faults worth
  reporting are the ones that happened while nobody was there. `MIGRATION_6_7`.
- **Repeats collapse into one line with a count.** A messenger that cannot connect retries on a
  timer; one bad evening would otherwise push every other fault off the end of the log. Bounded at
  `CaregiverLog.MAX_ENTRIES`, because nobody administers this phone.
- **It never writes down an identity.** A message from somebody she has no contact for is
  displayed under their raw Threema identity, so the name handed to the log is sometimes the
  identity itself — and a support screen would slowly accumulate a copy of her social graph.
  `CaregiverLog.anonymise` drops anything of that shape on the way *in*, so it never reaches the
  database rather than merely never being displayed. It deliberately requires a digit, so that
  KAROLINA and MATTHIAS — eight uppercase letters, and exactly the names the log exists to be
  readable about — survive.

It is placed above the credential forms rather than below them: those are filled in once at setup,
whereas this is the reason somebody opens the dialog again months later.

This also supersedes the reporting habit elsewhere in the app: any new user-facing error text is a
bug against this section. Every fault reported through `Log.w` for want of somewhere better now
goes here instead.

# What must be pure

CI runs JVM unit tests only. There is no Robolectric, so anything touching `Context` cannot be
tested at all. Every decision below belongs in a pure object, and every one of them is a place
where a mistake is invisible to her:

- `ConversationKey` — address to conversation id, including the merge rule and its deliberate
  refusal to merge on a name match. **Built, with `ConversationLinks` holding the merge rule.**
- `ConversationBuilder` — ordering, day separators, dedup across sources. **Built.**
- `StoredMessageMapper` — what survives a round trip through the database, and what a row written
  by a later version of the app degrades to rather than being lost. **Built.**
- `GroupIdentity` — parsing, formatting, equality. **Built in Stage 0.**
- `GroupDescription` — the name-or-members rendering, including the "and N others" cutoff.
  **Built.**
- `GroupControlParser` — setup, rename and leave off the wire, including where the creator comes
  from and the refusal to keep half a member list. **Built.**
- `GroupReplyPolicy` — whether a group may be replied to, given what we know of its membership,
  and who the reply must reach. **Built**, with `GroupSendOutcome` holding "all of them or none".
- `CaregiverLog` — what the error log keeps, what collapses into what, and the refusal to write
  down an identity. **Built**, with `CaregiverEventMapper` holding the round trip through the
  database.
- `dayNameFor` — today, yesterday, or a date, and where the boundary between them falls.
  **Built**, extracted out of `dayInWords` so that the rule that got "Yesterday" wrong on every
  old message is now covered by tests, and so that the caregiver's log dates things by the same
  rule her own screen does.
- `SendStatePolicy` — what may be claimed about a message we handed to the bridge, including a
  `PENDING` row that nobody ever came back to. **Built.**
- `GroupMemberRoute` — what one person in a group can be reached by, per transport, including the
  keys that are not addresses at all and must therefore draw no button. **Built.**

---

# Out of scope

- Delivery and read receipts (CSP `0x80`), which deserve their own stage.
- Editing, deleting, quoting, threading within a conversation.
- Media of any kind — images, voice messages, files.
- Creating, naming or leaving a group from within the app.
- WhatsApp groups. Stage 2 of `in-app-bridges.md` has not started.
- Search.

---

# Decisions taken

All four settled by the owner, 2026-08-04.

1. **The flat list stays the entry point, and Messages never becomes a list of people.** The faces
   are for voice; messages are second. Those are two different things and the screens must not
   converge — a list of names under Messages would duplicate the favourites row while meaning
   something else, which is the kind of near-repetition that is hardest for her to tell apart.
   Tapping a message opens that conversation. See "The shell" for how this lands on each screen.
2. **Keep everything, always.** No retention window, no pruning. The table grows; her history is
   not a cache. What is *shown* is bounded on the home screen only — again, see "The shell".
3. **A group appears only when somebody writes in it.** Groups she belongs to but which are quiet
   do not occupy her screen, and nothing lists them.
4. **No error copy on her screen; the caregiver gets all of it.** See "Errors are the caregiver's
   business" for what this removes and what replaces it.
