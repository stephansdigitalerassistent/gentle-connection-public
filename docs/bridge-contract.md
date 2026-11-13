# Bridge Contract

This document defines the contract between the `gentle-connection` Android app and the
server-side messenger bridges that feed it.

> **Security Rules & Admin SDK**
> A bridge uses the Firebase Admin SDK and therefore operates with administrative
> privileges — it **is not constrained by Firestore security rules**. Client access from the
> device and the caregiver portal is governed strictly by `firestore.rules`.

All document paths are subcollections under the device document: `devices/{deviceId}`.

---

## Transports

The contract is transport-neutral. Every document that identifies a person carries a
`transport` field naming the messenger it belongs to:

| Wire value | Meaning | Bridge implementation |
|---|---|---|
| `"threema"` | Threema | `threema-ibex-lib`, server-side client |
| `"whatsapp"` | WhatsApp | multi-device companion link (QR / pairing code) |

An address (`to`, `senderId`, `partners.id`) is only meaningful together with its
`transport`: for Threema it is an 8-character Threema ID, for WhatsApp a phone number.

Unrecognised transport values map to `UNKNOWN` on the device rather than throwing, so a
future messenger cannot crash an older build. The security rules, however, accept only the
values listed above — adding a transport means updating `firestore.rules` too.

SMS is **not** part of this contract. It never reaches Firestore; it is read on-device from
`Telephony.Sms.Inbox` by `LocalMessageStore`. (Until 2026-07-30 the same sentence covered
"any messenger read from Android notifications" — that path has been removed entirely.)

---

## 1. `devices/{deviceId}/messages/{messageId}`

**Writer:** bridge (Admin SDK).
**Readers:** Android device, caregivers (web portal).
**Updater:** Android device — `isRead` and `reaction` only.

### Schema

| Field | Type | Required | Description |
|---|---|---|---|
| `senderName` | `string` | Yes | Resolved display name. Avoid raw IDs where possible; the app shows this to her. |
| `senderId` | `string` | Yes | Sender's address on `transport` (Threema ID, WhatsApp number). |
| `transport` | `string` | Yes | `"threema"` or `"whatsapp"`. |
| `text` | `string` | Yes | Plain text body. Media is replaced by a short German placeholder, e.g. `"[Foto]"`. |
| `timestamp` | `number` | Yes | Epoch milliseconds. |
| `direction` | `string` | Yes | `"in"` or `"out"`. |
| `isRead` | `boolean` | Yes | Written `false`; the device sets it `true`. |
| `reaction` | `string?` | No | Emoji she sent back, or `null`. |
| `quotedMessageId` | `string?` | No | Messenger wire id / stanza id of the quoted message, or `null`. |
| `quotedSenderName` | `string?` | No | Display name or phone number of the quoted person, or `null`. |
| `quotedText` | `string?` | No | Carried copy of the quoted text (e.g. truncated body or `"[Foto]"`), or `null`. |

The device **ignores every document with `direction: "out"`.** They exist so the caregiver
portal can show a full conversation; showing them on her home screen would surface her own
replies as unread messages from herself.

This holds for **Firestore documents only.** A server-side bridge and the device record her
outgoing messages independently, with no id in common, so a kept echo could only ever be a
second copy of something already on her screen. The in-app WhatsApp bridge is the opposite
case and keeps them: it is the only path a message she wrote in the *native* WhatsApp app
ever travels, and the app's own echo is told apart there by the message id the app chose
before sending — see `WhatsAppMessageMapper` and `MainViewModel.isOwnEcho`.

### Quoted messages / replies

A message replying to an earlier one may carry three optional fields: `quotedMessageId`,
`quotedSenderName`, and `quotedText`.

An older build ignores fields it does not know, so a bridge may safely write them before every
device has been updated.

`firestore.rules` needs no changes: bridges write with the Firebase Admin SDK (which operates
with administrative privileges and bypasses security rules), while client updates from the device
remain strictly restricted to `isRead` and `reaction`.

**Why the device reads the copy rather than resolving the id:**
On the device, `BridgeMapper` reads `quotedSenderName` and `quotedText` directly into the domain
model, but deliberately leaves `quotedId` as `null`. A bridged message's document id is chosen
by Firestore or the bridge (e.g. `msg_123456`), whereas `quotedMessageId` is the messenger's own
wire id (a Threema 16-hex hash or a WhatsApp stanza id). Because those two id spaces do not line
up, `quotedMessageId` is not resolvable against other Firestore documents in the store, and
attempting to resolve it would fail. The citation card is drawn from the carried wire copy
(`quotedSenderName` and `quotedText`) instead.

```json
{
  "senderName": "Anna",
  "senderId": "ECHO1001",
  "transport": "threema",
  "text": "Ja, gerne!",
  "timestamp": 1774614400000,
  "direction": "in",
  "isRead": false,
  "reaction": null,
  "quotedMessageId": "a3f19c04b7e25d81",
  "quotedSenderName": "Mama",
  "quotedText": "Kommst du am Sonntag zum Essen?"
}
```

---

## 2. `devices/{deviceId}/outbox/{outboxId}`

**Writer:** Android device.
**Readers:** bridge, caregivers (read-only queue monitoring).
**Deleter:** bridge, after delivery.

An outbox document is a command to send a message **as her**. The rules shape it tightly and
permit only the device to create one — a caregiver can watch the queue but can never put
words in her mouth.

### Schema

| Field | Type | Required | Description |
|---|---|---|---|
| `to` | `string` | Yes | Recipient's address on `transport`. |
| `transport` | `string` | Yes | `"threema"` or `"whatsapp"`. Tells the bridge which client to send on. |
| `kind` | `string` | Yes | `"reaction"` or `"text"`. |
| `emoji` | `string?` | Conditional | Set when `kind == "reaction"`, else `null`. |
| `text` | `string?` | Conditional | Set when `kind == "text"`, else `null`. |
| `inReplyToMessageId` | `string?` | No | Target message id; lets the bridge attach a **native** reaction. |
| `createdAt` | `number` | Yes | Epoch milliseconds (device clock). |
| `status` | `string` | Yes | Always `"pending"` from the device. |

### Reaction delivery

Where the transport supports real reactions, use one — WhatsApp multi-device does, and it is
what she means when she taps a heart. Fall back to sending the emoji as a plain text message
when a native reaction is unavailable, or when `inReplyToMessageId` is absent (as it is for a
heart sent from the "Menschen" section, which starts a conversation rather than answering
one).

```json
{
  "to": "ECHO1001",
  "transport": "threema",
  "kind": "reaction",
  "emoji": "❤️",
  "text": null,
  "inReplyToMessageId": "msg_987654321",
  "createdAt": 1774614450000,
  "status": "pending"
}
```

```json
{
  "to": "41791234567",
  "transport": "whatsapp",
  "kind": "text",
  "emoji": null,
  "text": "Ich komme später.",
  "inReplyToMessageId": null,
  "createdAt": 1774614500000,
  "status": "pending"
}
```

---

## 3. `devices/{deviceId}/partners/{partnerId}`

**Writer:** bridge, on first contact.
**Readers:** Android device, caregivers.
**Updaters:** caregivers — display names, phone numbers, and the `canInitiate` toggle.

The document id is `"<transport>:<id>"`, e.g. `threema:MARKUS01` or `whatsapp:41791234567`.
A bare id would let a Threema ID and a WhatsApp number collide in the same collection.

This collection is the allowlist for her **starting** a conversation: she may contact anyone
here, and no one else.

### Schema

| Field | Type | Required | Description |
|---|---|---|---|
| `id` | `string` | Yes | Address on `transport`, without the document-id prefix. |
| `transport` | `string` | Yes | `"threema"` or `"whatsapp"`. |
| `displayName` | `string` | Yes | Name shown under the face, e.g. `"Markus"`. |
| `phoneNumber` | `string?` | No | Enables a cellular call to this person. |
| `photoUrl` | `string?` | No | Contact photo. See the limitation note below. |
| `lastContactAt` | `number` | Yes | Epoch milliseconds of the last interaction. |
| `canInitiate` | `boolean` | Yes | Caregiver switch; `false` hides the person from her screen. |

> **Photo limitation:** the app currently resolves `photoUrl` through Android's content
> resolver, which handles `content://`, `file://` and `android.resource://` but not
> `https://`. Remote partner photos fall back to a placeholder icon until an image-loading
> library is added. Favourites are unaffected — their photos come from local contacts.

```json
{
  "id": "MARKUS01",
  "transport": "threema",
  "displayName": "Markus",
  "phoneNumber": "+41791234567",
  "photoUrl": "https://example.com/photos/markus.jpg",
  "lastContactAt": 1774614400000,
  "canInitiate": true
}
```
