# Quoted messages — showing what an answer is answering

Status: **built**, all four steps, on main as of 2026-08-23. Compiled and unit-tested in
CI; the WhatsApp half has never met a real socket, so the bridge reading a real reply is
still unverified — that is only testable on her phone.

Both messengers she uses let people answer one particular message rather than the
conversation as a whole. Threema calls it a quote, WhatsApp calls it a reply. On her phone
today, one of the two is machine text in the middle of her daughter's words and the other is
invisible. Neither is what the sender meant.

---

## What she sees today

**Threema** puts the quote in the message text itself. The wire format is

```
> quote #a3f19c04b7e25d81

Ja, gerne!
```

— a marker line, a blank line, then the answer (`QuoteUtil.quoteV2MatchPattern` in the
Threema app source; the sixteen hex characters are the quoted message's id). There is an
older `> ECHO1001: <the quoted words>` form still in circulation. Nothing in this app knows
about either, so `Message.text` keeps the marker and every screen draws it. Her daughter
answers a question and what arrives on the home screen is a line of hexadecimal.
Read-aloud is worse: it is handed the same string and says it out.

**WhatsApp** puts it in `ContextInfo` beside the message — `StanzaID`, `Participant`, and a
full copy of the quoted message. `native/whatsapp-bridge/main.go` never reads `ContextInfo`,
so the context is dropped at the bridge and "Ja, gerne!" reaches her attached to nothing. In
a group of eight, where three conversations run at once, that is most of what is wrong with
her group chats.

So one transport shows too much and the other too little, and the fix has a shared half.

## What it should look like

A **citation** above the words in the bubble: who was being answered, and up to two lines of
what they said, in a quieter tone with a bar down the leading edge. Then the answer itself,
unchanged, in the size it always was.

```
┌──────────────────────────────────────┐
│ Sabine                               │  ← group sender name (titleSmall, bold)
│                                      │
│ ▌ Anna                               │  ← titleSmall, onSurfaceVariant
│ ▌ Kommst du am Sonntag zum Essen?    │  ← bodyMedium, two lines max, ellipsised
│                                      │
│ Ja, gerne!                           │  ← bodyLarge, exactly as now
│                                      │
│      🔊 Vorlesen                     │
└──────────────────────────────────────┘
```

Seven decisions in that picture, and each of them is the point:

**The marker never reaches her.** Stripping `> quote #…` out of the text is worth doing on
its own, before any card is drawn — it is a visible defect today. It has to be stripped in
one place, in the model, not per screen: the same string feeds the bubble, the one-line
preview on the home screen, the nudge, the watch and the text-to-speech engine, and a fix in
four of the five leaves the fifth reading hexadecimal aloud.

**The citation is drawn in the conversation only.** Not in the home screen's unread list, not
in the nudge card. Those have one line each and it belongs to what the person actually said.
This mirrors the rule already settled for photographs: the list row says "Foto", the
conversation shows the photograph.

**Her own quoted message says "Deine Nachricht", never her name.** The same rule the
conversation already follows for her own bubbles.

**The citation is not a control.** Every other messenger makes it tappable and jumps the list
to the original. For her that is a screen that moves on its own to a place she did not ask
for, and then a way back she has to find. The card shows; it does not go anywhere. (If
anything ever wants to be tappable here it should be under Advanced mode, not on her screen.)

**Two lines, roughly 120 characters, then an ellipsis.** A quoted paragraph above a
three-word answer inverts the message: the loudest thing in the bubble becomes the thing
somebody else said last week. Threema's own app caps quotes at 256; half that is right at
this type size.

**A quoted photograph is called "Foto", not drawn.** Run the quoted text through the
existing `Attachment` / `messageInWords` path, so `[Foto]` becomes the same word it becomes
everywhere else, and a captioned photograph quotes as its caption. The picture itself stays
in the bubble it belongs to — two copies of one photograph on one screen is her being shown
it twice.

**Read-aloud reads the citation.** This is the one place the citation is more than decoration
and is worth the extra seconds. She has failing sight; read-aloud is how she takes a message
in; and hearing "Ja, gerne" with no idea what was asked is precisely the confusion the card
exists to remove. The format, as a string resource beside `mail_read_aloud_format`:

```xml
<string name="quote_read_aloud_format">Antwort auf %1$s: %2$s. %3$s</string>
<!-- en: "Answering %1$s: %2$s. %3$s" -->
```

with the quoted words capped shorter still for the voice — one sentence or ~80 characters —
because a listener cannot skim.

### When the quoted message cannot be found

Threema sends only an id. If the message it names is not in the store — an id from before
this app was installed, a conversation imported without its history — there is nothing to
show. **Then show nothing at all**: no card, no "answer to an earlier message" placeholder.
A label with no content is clutter that tells her something is missing without letting her do
anything about it, and the answer on its own is exactly what she gets today minus the
hexadecimal, which is already a repair.

WhatsApp is the easier case: `ContextInfo` carries a copy of the quoted message, so the card
can be drawn from the wire even for a quote of something ten years old that this phone has
never seen. Store that copy; prefer the resolved local message when there is one — its
sender name has been through the address book, so it says "Anna" where the copy says a
telephone number.

---

## What it takes to build

### 1. One pure parser — `model/QuoteMarker.kt`

```kotlin
data class QuotedRef(val wireId: String?, val body: String)

object QuoteMarker {
    fun parse(text: String): QuotedRef   // "> quote #<16 hex>\n\n…" → id + remainder
}
```

Handles the v2 form, the legacy `> IDENTITY: …` form (marker dropped, no id recoverable),
and a quoted body that itself starts with a marker — Threema's own developer menu can send a
quote that references itself. Pure and unit-tested; this is where a real message either
stays intact or quietly loses its first line.

### 2. Four fields on `Message` and four columns on `messages`

```kotlin
val quotedId: String? = null,          // this app's own id: "threema_<hex>" / "whatsapp_<id>"
val quotedSenderName: String? = null,  // WhatsApp's carried copy; null for Threema
val quotedText: String? = null,        // ditto
val quotedIsHers: Boolean = false,     // whether the quoted message was sent by her
```

`quotedId` is written in this app's id space, not the wire's, because that space already
exists and already matches: a Threema message is stored as `threema_` + its sixteen hex
characters (`OnDeviceMessageMapper.ID_PREFIX`), and a WhatsApp one as `whatsapp_` + its
stanza id (`WhatsAppMessageMapper.ID_PREFIX`). So resolving a quote is a primary-key lookup
on the row the reaction path already looks up, and no new index is needed.

Room migration adds the nullable string columns and the default-0 `quotedIsHers` integer column,
alongside the existing manual migrations in `AppDatabase`.

### 3. Resolution where `groupName` is resolved

The quoted sender's name and words are **derived when the message is read**, not stored
resolved — the same choice, for the same reason, as `groupName` and `groupCanBeAnswered`: a
contact renamed in her address book should rename everywhere at once, including inside a
citation of a message from four years ago. `MessageDao.getAllFlow()` already hands the
conversation every message it has, so the lookup is against a list already in memory;
`ConversationBuilder` is the natural place, and it stays pure.

### 4. Threema — no protocol work at all

`OnDeviceMessageMapper.fromThreema` runs the text through `QuoteMarker`, sets
`quotedId = "threema_" + wireId`, and stores the remainder as `text`. Nothing in
`libthreema` or the native bridge changes. This half could ship on its own.

### 5. WhatsApp — the bridge has to start reading `ContextInfo`

In `native/whatsapp-bridge/main.go`, a `contextInfoOf(msg *waE2E.Message) *waE2E.ContextInfo`
helper (every message kind that can carry one has its own field —
`ExtendedTextMessage`, `ImageMessage`, `VideoMessage`, `AudioMessage`, `DocumentMessage`,
`StickerMessage`), then three more keys in the `emitMessage` event:

```go
"quotedId":     ctx.GetStanzaID(),
"quotedSender": personJID(parsed(ctx.GetParticipant()), types.EmptyJID, lookup),
"quotedText":   truncate(extractText(ctx.GetQuotedMessage()), quotedTextLimit),
```

`quotedSender` goes through `personJID` and the LID directory for the same reason every
other sender does — otherwise a quote of her daughter is captioned with a LID. The same
three keys go on the historical path (`emitHistoricMessage`), because a freshly linked phone
gets a fortnight of chat in one go and half of it is replies. Then `WhatsAppEvent.Incoming`
and `WhatsAppMessageMapper` carry them through.

### 6. Firestore bridge contract — three optional fields

`quotedMessageId`, `quotedSenderName`, `quotedText`, all nullable, documented in
`docs/bridge-contract.md` §1. `firestore.rules` needs no change: it validates only the
`isRead`/`reaction` update, and a bridge writes with the Admin SDK. An older build ignores
fields it does not know, so this is safe to write before every device has it.

### 7. UI — one composable

`ui/components/QuotedMessageCard.kt`, drawn in `MessageBubble` below the group sender name
(when present) and above the words — a `Column` with a 4dp accent bar, `surfaceVariant`
at a lower tone inside both bubble colours, `titleSmall` name, `bodyMedium` body,
`maxLines = 2`, `TextOverflow.Ellipsis`. Names go through `personName()` exactly as the group
sender name does. Everything it draws is optional, so a bubble with no quote is byte for byte
the bubble that is there now.

---

## Deliberately not in this

**Letting her quote back.** She answers with a big emoji or a short line, to one person, in a
conversation that is already open — the message she is answering is the one on the screen,
and choosing *which* message to answer is a selection gesture that this app has spent every
other decision removing. `inReplyToMessageId` already exists in the outbox for attaching a
native reaction; nothing here changes that.

**Threema quote v1 rendering.** The marker is stripped, but the old form carries no id and
its quoted text is the sender's own retyping, so there is nothing reliable to resolve. Its
words are dropped with the marker rather than shown as a card that might be wrong.

**Forwarded messages.** WhatsApp's `ContextInfo` also carries `IsForwarded`. It is a
different question — "who wrote this originally" rather than "what is this answering" — and
mixing it in would put a second kind of grey box in the bubble.

**The caregiver portal.** It reads the same documents and will keep showing the answer alone
until somebody teaches it the three new fields. That is a smaller problem: a caregiver can
scroll up.

---

## Order to build it

1. `QuoteMarker` + strip on the Threema path, no card, no columns. Ships the defect fix on
   its own and cannot regress anything else.
2. The three columns, the migration, `quotedId` on Threema, the card, read-aloud.
3. The Go bridge's `ContextInfo`, live and historic, and the mapper.
4. The bridge-contract fields, for the server-side path and the portal.

Steps 1 and 2 are testable in CI. Step 3 is only really testable on her phone, like every
other change to the WhatsApp bridge.

### What actually happened

All four went in, 1–2 first and 3–4 rebased on top once the first build was green.

One thing was got wrong on the way and is worth keeping: a test asserted that a v2 marker
riding inside a v1 quote should yield its id. It must not. A v1 quote carries the quoted
message's own text, so when that message was itself a reply its `> quote #…` line comes
along — and that id names what the *quoted* message was answering, one hop further back.
Citing it would put a third message on her screen as the thing being answered, with nothing
to say it was the wrong one. A v1 quote yields no id; that is the difference between the
two forms.
