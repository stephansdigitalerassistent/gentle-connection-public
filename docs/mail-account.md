# Mail from her account, not from her notifications

> **2026-09-30 — IMAP is gone; her mail is read through the Gmail API.** The app-password route
> never worked in practice, so the credential decision below is reversed. What replaced it:
>
> - **Scope `gmail.readonly`.** Read-only is now enforced by Google, not by our code: the token
>   cannot send, delete or mark anything read. An app password could do all of that.
> - **No password on the phone.** The caregiver types her Gmail address in the caregiver screen
>   and taps *Gmail verbinden*; Google's consent screen appears on her phone, once. Play services
>   then hands out access tokens in the background (`GmailAuthorizer`). Any app password still
>   stored from the IMAP days is deleted the first time `MailAccountRepository` is read.
> - **The 7-day expiry was a Testing-mode rule, not a restricted-scope rule.** The OAuth consent
>   screen in your Firebase/GCP project is published *In production* and left **unverified**:
>   restricted scopes then work for up to 100 users, behind a one-time "Google hasn't verified
>   this app" warning (Advanced → continue). No CASA assessment at that size.
> - **OAuth client:** the Android client Firebase created from the signing SHA-1
>   `<SHA-1 FINGERPRINT>` (matching your Play/CI upload key). A build signed with any other key gets no token.
> - **Sync:** `GmailApiFetcher` lists the newest 200 inbox ids plus the unread ones, downloads
>   each new letter whole (`format=raw`) and feeds it to the same MIME code as before
>   (`MimeMailMapper`). Gmail ids are stored in the `uid` column under `uidValidity = 0`.
> - **Consent screen branding:** name + support/developer email, home page
>   `https://<YOUR_PROJECT_ID>.web.app/about.html`, privacy policy `…/privacy.html` (which carries
>   the Google API Limited Use statement), authorized domain `<YOUR_PROJECT_ID>.web.app`. **No
>   logo** — uploading one forces verification.
> - **Grant lost:** a sync with no token (grant withdrawn, account gone) records
>   `MAIL_ACCESS_LOST`, which the website publishes as an alert (collapsed, 6 h cooldown).
> - **Who is shown:** every inbox letter by default (Gmail keeps spam out of the inbox);
>   the portal switch *Post nur von Bekannten* (`mailOnlyKnownSenders`) limits the Post screen to
>   contacts and the allowlist.
>
> The rest of this document is the history that led here; the IMAP-specific parts no longer
> describe the code.

Status: **implemented and green in CI. Never connected to a mailbox.**

Pass 1 (`646f817`, fixes in `59da9ca`): MIME body extraction, the sender filter, deduplication,
the schema. Pass 2 (`2a73bbc`, fix in `784a40d`): credential storage, the IMAP fetcher, the
WorkManager sync, the caregiver setup UI.

What "green in CI" covers here is narrow, and worth stating plainly: the Kotlin compiles, the
JVM tests pass, lint is clean. Nothing has opened a socket. Every item under *Honest completion
criteria* at the foot of this document is still open, and the next step is a throwaway mailbox
on a real device — not more code.

Mail used to reach this app one way: `UnifiedMessageListenerService` watched for notifications
posted by mail apps and turned each one into a `Mail`. Since 2026-07-28 those were persisted to
Room, so a mail stayed on her Post screen after she read it and survived a restart. That
listener was removed on 2026-07-30 — see *The notification listener is gone* below.

Persistence fixed the disappearing, but not the ceiling. Notification scraping could only ever
see a mail **at the instant a mail app posted a notification for it**, and only what fitted in
a notification. It could not show:

- anything that arrived before this app was installed,
- anything whose notification she or the mail app already dismissed,
- the actual message body — a notification carries a truncated snippet,
- read state that agrees with what she sees anywhere else.

This document specifies reading her mailbox directly.

---

## Why not piggyback on the Gmail app

Because Android has no cross-app contract for mail. Contacts, SMS and call log each have a
`ContentProvider` that any app can read with a permission. Email has no equivalent — there is
no `android.provider.Email`.

What Gmail actually exposes to other apps is one provider,
`content://com.google.android.gm/labels/<account>`, behind the permission
`com.google.android.gm.permission.READ_CONTENT_PROVIDER`. It returns label names with unread
and total counts. **Counts, not messages** — no sender, no subject, no body. It is undocumented
and has broken between Gmail releases before.

Samsung Email and the AOSP-derived clients guard their providers with `signature`-level
permissions, so only an app signed with the same key can read them.

There is no route that reads another mail client's stored mail. The notification listener was
the piggyback on the app, and it was the best one available. To get anything more, the app has
to talk to the account — which is now the only thing it does.

---

## The credential decision

Two ways to reach the account. This was decided in favour of the second.

### Gmail API over OAuth — rejected

Sign in with Google, store no password, hold a revocable token scoped to `gmail.readonly`. The
Firebase work already provides a Google Cloud project.

`gmail.readonly` is a **restricted** scope. While the OAuth client's publishing status is
"Testing", refresh tokens expire after seven days — somebody would redo the consent flow on her
phone every week, which is not a thing that will happen. Leaving Testing for restricted scopes
normally means Google's security assessment.

Rejected not because it is less secure — it is more secure — but because the path to it
working continuously for one elderly user on a private app is uncertain, and the failure mode
is her mail silently stopping.

### IMAP with an app password — chosen

The account needs 2-Step Verification enabled; the caregiver then generates a 16-character app
password scoped to mail and enters it once. No consent screen, no verification, no weekly
expiry. It is revocable from the Google account page without touching her real password, and
the identical mechanism works for Outlook, Proton Bridge, Fastmail or anything else — nothing
in the implementation is Gmail-specific.

**Known risk to record:** Google has been narrowing app-password availability for years. If
they withdraw it for IMAP, this route dies and the OAuth question reopens. That is one reason
the notification path below is kept rather than deleted.

---

## The notification listener is gone (2026-07-30)

This section used to argue the opposite, and it is worth keeping the argument visible rather
than quietly rewriting it. It said: keep mail scraping in `UnifiedMessageListenerService`,
because notification = instant and IMAP = truth, and because a listener that needs no
credential is a floor the app cannot fall below.

`UnifiedMessageListenerService` was deleted on 2026-07-30, by design. IMAP is
now the only way mail reaches her, and the Threema bridge the only way messages do.

**What the argument got right, and what it cost to give up:**

- *Instant.* A notification fired the moment mail landed. Now the floor is `MailSyncWorker`'s
  15-minute period, plus a sync when she opens the Post screen. Her post can be a quarter of an
  hour behind the letter.
- *A floor with no credential.* If the app password expires or is revoked, mail stops dead.
  There is no second path. `MailSyncWorker` returns `Result.failure()` and nothing tells anyone
  — surfacing that to the caregiver portal is open work.

**What was wrong with it, and why removing it was right anyway:**

- A notification carries a display name and no email address, so `MailSenderFilter` — which
  matches on the address, never on the name, because the name is written by whoever sent the
  mail — could never check it. The compromise was to store the notification copy with
  `isVisible = false` whenever a mailbox was configured: fetched, parsed, persisted, and then
  deliberately never shown. A whole ingestion path whose output was hidden by design.
- It cost her a permission. Notification access is granted through a system settings screen
  with a warning dialog about an app that "will be able to read all notifications", and it
  needed a red banner on her home screen to ask for it — an alarming thing to put in front of
  someone with fading memory, about something she could not act on.
- It read every messaging app on the phone. That is a large amount of other people's private
  correspondence flowing through this app for the sake of a snippet.

`isVisible` stays and still does real work: `MailSenderFilter` sets it on the IMAP path, so
mail from someone not in her address book is stored and hidden rather than shown.

`MailDeduplicator` also stays. It no longer has two sources to converge — it now catches the
same letter re-fetched after a `UIDVALIDITY` reset.

**The sender-plus-timestamp fallback may only ever be applied between the two sources**, where
it is reconciling one real mail seen twice. Applied within a single source it destroys mail:
two letters from the same person in the same thread a few minutes apart match on sender, on
normalised subject and on time, so they merge and one of them is gone. `MailDeduplicator`
guards this by requiring the two sides to disagree on whether they carry an IMAP UID.

---

## What she must never see: the filtering problem

**This is the part most likely to make the feature worse than no feature.**

A real mailbox is mostly not correspondence. It is newsletters, receipts, delivery
notifications, security alerts, and marketing. Pointing this app at a fifteen-year-old Gmail
account and rendering INBOX produces a Post screen with four hundred items, of which three
matter, and she will not find those three.

**Only mail from people reaches her screen.** Concretely, a mail is shown if its `From` address
matches either:

1. an email address in her Android address book, read from
   `ContactsContract.CommonDataKinds.Email` via `ContactsRepository.queryContactEmailAddresses()`,
   or
2. an address the caregiver has explicitly added to a mail allowlist.

**Matching is on the address, never on the sender's display name.** An earlier draft of this
document said to match against contacts, partners and imported Threema contacts. That is not
implementable as written: `Contact` and `Partner` carry a phone number and a Threema ID and no
email address at all, so the only field left to match on is the `From` display name — and that
is written by whoever sent the mail. `From: "Anna" <anything@example.com>` would pass, which
defeats the entire filter, while a genuine mail from "Anna Müller" would fail against a contact
stored as "Anna". Partners and Threema contacts therefore contribute nothing here.

Everything else is fetched, stored, and hidden. Not deleted — hidden, so the caregiver can look
at what was filtered and add a sender that should have got through.

This is the same principle the rest of the app already runs on: faces of people who matter,
not a directory. It also solves spam permanently and without heuristics.

Sync `INBOX` only. Not All Mail, not Spam, not Sent.

---

## Architecture

### Library

`com.sun.mail:android-mail:1.6.7` plus `com.sun.mail:android-activation:1.6.7`.

This is JavaMail's Android port. It is the well-trodden option and it works, but be aware it
was last released in 2021 and is effectively unmaintained; Jakarta Mail 2.x has no official
Android port. Check what it drags in and keep R8 rules with it. All of its I/O is blocking —
none of it may touch the main thread.

### Shape

```
MailAccountRepository   credential storage, account config
    ↓
ImapMailFetcher         connect, fetch, disconnect. No Android UI types. Testable seams.
    ↓
MimeBodyExtractor       MIME → plain text. Pure. This is where the real effort goes.
    ↓
MailSenderFilter        is this from a person she knows? Pure.
    ↓
MailRepository/MailDao  existing storage, extended
```

`MailSyncWorker` (WorkManager) drives it. Periodic, minimum 15 minutes, plus one when she opens
the Post screen. (The expedited run "when the notification listener sees mail arrive" went with
the listener; 15 minutes is now the floor.)

**Not IMAP IDLE.** IDLE holds a socket open, and on a Samsung One UI device app sleep will
kill it and no foreground service will save it — that ground was already covered when the
Threema bridge's keep-alive was specified. WorkManager survives Doze; a long-held socket does
not.

### Credential storage

The app password is a mail-account credential, not her Google password, but it still reads
every mail she has ever received. Handle it like the Threema client key:

- `EncryptedSharedPreferences` (`androidx.security:security-crypto`), **not** plain
  `MODE_PRIVATE`. The Threema client key is currently in plain SharedPreferences and that is a
  known outstanding item — do not add a second one, and prefer wiring this up in a way the
  Threema key can move to later.
- Never in logs, never in a crash report, never in an `Intent` extra.
- `android:allowBackup="false"` is already set on the manifest. Keep it.
- Entered only through the hidden caregiver gesture (five fast taps on the clock). She must never meet a
  password field.

### Data model

`Mail` gains:

| field | why |
|---|---|
| `body` | the real message, as plain text. `snippet` stays for the list row. |
| `messageId` | RFC 5322 `Message-ID`, for deduplicating against the notification path |
| `uid` | IMAP UID, for incremental fetch |
| `uidValidity` | if the server changes this, every stored UID is void and the folder must be refetched |
| `isVisible` | passed the sender filter, or hidden |

This is a Room schema change. `AppDatabase` is at version 2 with a hand-written `MIGRATION_1_2`
— add a `MIGRATION_2_3` the same way. **Do not let it fall back to destructive migration**;
that drops the contacts and message history imported from her Threema backup, which came off a
file and a password nobody will find again.

### Sync algorithm

1. Connect over IMAPS (993, TLS, certificate validation on — never trust-all).
2. Read `UIDVALIDITY`. If it differs from what is stored, discard stored UIDs and start over.
3. Fetch UIDs greater than the highest stored, newest first, capped — a first sync against a
   large mailbox must not try to pull everything in one run.
4. For each: fetch envelope and body structure, extract plain text, apply the sender filter,
   store.
5. Separately, refresh `\Seen` flags for mail already stored, so read state stays current.

Bounded by design: fetch at most N per run and remember where it got to. A first sync that
takes several runs across an evening is fine. One that holds a connection open for twenty
minutes on her phone is not.

### Read state stays local

Reading a mail in her launcher **does not** set `\Seen` on the server.

She has a read-only appliance and the help text promises her that nothing here can change
anything or be sent by mistake. Silently mutating her real mailbox contradicts that, and a
caregiver who uses unread-in-Gmail to track what she has not seen would be reading a lie.

Server → app: yes, a mail read elsewhere shows as read here.
App → server: no.

Reversible later if it turns out to be the wrong call.

---

## Body extraction is the real work

Notification snippets are plain text. Real mail is MIME, and most of it is HTML written by a
marketing tool.

- Walk the MIME tree; prefer `text/plain` when a `multipart/alternative` offers it.
- Fall back to `text/html` → text. Strip tags, decode entities, collapse whitespace, keep
  paragraph breaks, drop `<style>` and `<script>` contents entirely.
- Honour the declared charset. Getting this wrong turns every German mail into mojibake, and
  she has no idea what mojibake is or that it is the app's fault.
- Decode quoted-printable and base64 transfer encodings.
- Attachments: **ignore in v1.** Never download them. Never offer to open one. A mail with an
  attachment shows its text and nothing else.
- Remote images: never fetch. Fetching one tells a sender her device is live.

All of the above is pure, deterministic, and has no Android dependency. **It is the only part
of this feature CI can actually verify, so it is where the tests go.**

---

## Tests (JVM)

Real ones, on the pure pieces:

- `MimeBodyExtractor`: plain text; `multipart/alternative` preferring plain; HTML-only falling
  back; quoted-printable with German umlauts; base64; a declared non-UTF-8 charset; an
  attachment-bearing mail yielding just the text; a malformed tree not throwing.
- `MailSenderFilter`: known contact passes; unknown sender hidden; allowlisted sender passes;
  case and whitespace differences in addresses still match; display-name-only `From` handled.
- Deduplication: the same mail from the notification path and IMAP produces one row; matching
  by `Message-ID`, and the fallback match.
- `UIDVALIDITY` change discards stored UIDs.

Do not write tests that open sockets, or that assert against a mock IMAP server so elaborate it
is really testing itself. Fetching is verified on a device or not at all.

---

## CI

Nothing here needs a new CI job — it is Kotlin and JVM tests, which the `build` job already
compiles, lints and runs. The one thing to watch is that `android-mail` pulls in `javax.*`
packages that R8 will complain about; expect to add keep rules, and expect the first CI run
after adding the dependency to fail on exactly that.

---

## What the owner has to provide before any of this can be tested

1. The mail address to read.
2. 2-Step Verification enabled on that account.
3. An app password generated for it.
4. A decision on whose mailbox is used for the first test — as with the Threema ID, use a
   throwaway account first. An IMAP client with a bug in its UID handling should not meet her
   real fifteen-year mailbox on its first run.

---

## Honest completion criteria

This is done when, on her actual phone:

- mail from before the app was installed appears on her Post screen,
- a mail she opens shows readable text rather than a snippet or a wall of HTML,
- a newsletter does not appear at all, and the caregiver can see that it was filtered and why,
- reading a mail in Gmail on another device shows it as read in her launcher,
- the phone's battery is no worse over a day than it was before,
- and revoking the app password degrades the app to notification-only rather than breaking it.

Compiling proves none of these. As with the Threema work: CI can tell you the Kotlin is valid
and the parser is correct. It cannot tell you the mail arrived.

---

## What comes with a letter (added later)

A letter is no longer only its words. Two kinds of file are kept and drawn under the letter in
the reader — a photograph, and a PDF as its pages — because a great deal of her real post is a
picture of the grandchildren or an appointment on paper, and all of it used to arrive as a letter
that said nothing with no sign that anything was missing.

The parts of it worth knowing:

- **Nothing else is kept.** A spreadsheet or a calendar invitation is declined before a byte of it
  is read. There is nowhere on this phone to send a file to and no other app she is expected to
  know, so a row naming a file she cannot open would be a dead end with a name on it.
- **A logo is not a photograph.** Every company letter carries its logo, its icons and a tracking
  dot as inline images with file names. An inline image under 24 KB is decoration and is dropped;
  anything actually attached is kept whatever its size. That rule is
  `MailAttachments.isWorthKeeping`, and it is the one that fails silently — a screenshot of it
  failing looks exactly like the feature working.
- **Bounded.** Ten megabytes per file, six files per letter, sixty parts walked per letter, and
  96 MB for the whole store before the oldest letters give theirs up. A file deleted that way is
  gone for good: a letter is never fetched twice, and the letter simply goes back to its words.
- **Files live outside the database**, under `filesDir/mail-attachments/<letter>/`, with the paths
  in one column on the row. A path may point at nothing, exactly as a message's photograph may,
  and the reader draws nothing in that case.
- **Three pages of a PDF.** Each page is a bitmap of several megabytes in a process that is also
  the launcher; nearly everything a surgery or a bank sends is one or two pages. A PDF that will
  not render at all falls back to its file name, which is the one place in this app where a
  failure is allowed to say something rather than show nothing — showing nothing would put her
  back where this started, reading "see the attachment" with no attachment.

The read-aloud button says how many things came with the letter, because for somebody losing her
sight that is the one thing on the screen she cannot find out by listening.
