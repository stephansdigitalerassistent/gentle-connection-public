# Photo Sending (Taking and Sending Her Own Photos)

*Added 2026-10-22.*

This feature allows her to take a photograph herself and send it directly to a family member or caregiver. The user of this app is an elderly woman with cognitive decline; every screen is big, calm, and presents one decision at a time.

---

## 1. What She Sees

### Entry Points
A camera button is offered in three places, but **only** when `enablePhotoSending` is turned on by the caregiver and the recipient can carry media (`Forwarding.canCarryMedia(recipient)` for a person; any group she can answer):
1. **Conversation reply row** (`ReplyControls`):
   - In a one-on-one or group conversation (since 2026-10-23), a 64 dp wide camera button (`Icons.Filled.PhotoCamera`, content description "Foto schicken") appears immediately to the left of the "Antworten" button.
   - In the side-by-side layout: `[faces][📷][Antworten]`.
   - In the stacked layout (narrower width): `[📷][Antworten]` centred underneath the faces.
   - While writing text, the camera button is hidden alongside the face reactions.
2. **Home face dialog** (`PartnerActionDialog`):
   - Tapping an initiating partner on the home screen offers a third button: "Foto zeigen" (`Icons.Filled.PhotoCamera`), placed below "Herz senden".
3. **Group member dialog** (`GroupMemberDialog`):
   - When viewing the members of a group, tapping an individual member offers a third choice: "Foto" (alongside ringing and writing), sending a photo to that person privately.

The camera button is **never greyed out**; if the feature is disabled or the contact cannot receive media, the button is simply absent.

### The Camera Screen (`ui/CameraScreen.kt`)
The camera screen is drawn by `MainActivity` as a full-screen overlay above whatever screen she was on:
- **Taking (Live preview)**:
  - Header: Contact avatar and bold text: "Foto für [Name]".
  - Center: Rounded live camera preview (`PreviewView`).
  - Bottom bar ("The Sandwich"):
    - Left: `GentleSecondaryKey` "Zurück" (cancels and closes).
    - Center: Big 88 dp round shutter `GentleKey` (`Icons.Filled.PhotoCamera`, content description "Foto machen").
    - Right: Camera flip key (`Icons.Filled.Cameraswitch`, "Wechseln"), shown **only** if `enableCameraSwitch` is true and the device has both front and back cameras. If not shown, an invisible spacer of identical width keeps the shutter perfectly centred.
    - Default: The back camera is always selected initially whenever the screen opens.
- **Reviewing (Freeze in place)**:
  - Header: Contact avatar and question: "An [Name] schicken?".
  - Center: The prepared photo (the exact file that will be sent).
  - Bottom bar:
    - Left: `GentleSecondaryKey` "Nochmals" (deletes the prepared photo and returns to Taking).
    - Right: `GentlePrimaryKey` "Schicken" (sends the photo; shows a progress spinner while sending).
- **Wide window (Samsung Fold unfolded)**:
  - When unfolded (`isWideWindow()`), the layout adapts: preview on the left, buttons in a vertical column on the right (Zurück top, shutter middle, Wechseln bottom; or Schicken above Nochmals in Reviewing).
- **Inactivity dismissal**:
  - If she puts the phone down and walks away for 2 minutes without tapping, `gentleDialogOpacity` gently fades out and closes the screen, deleting any unsent prepared photo.

---

## 2. Caregiver Switches

Configured in the Caregiver Portal and synced via Firestore (`RemoteSettings`):

1. **`enablePhotoSending`** (Boolean, default: `false`):
   - Master switch for the entire feature.
   - When `false`, no camera button appears anywhere in the app, and nothing changes for her.
2. **`enableCameraSwitch`** (Boolean, default: `false`):
   - Controls whether the camera screen offers the button to toggle between back and front (selfie) cameras.
   - When `false`, only the back camera is used.
3. **`photoButtonPlacement`** (String, default: `"card"`):
   - Where the photo key sits on the home screen:
     - `"card"` (default): full-width key directly under the clock row, outside the scrolling column.
     - `"clock"`: 56 dp square tile in the clock row opposite the support button. If the payment card is active, automatically falls back to `"card"` via `PhotoButtonPlacement.effective`.
     - `"faces"`: after the favourite faces, scrolling with the content.
     - `"bottom"`: pinned directly above `GentleBottomBar` on Home.
     - `"none"`: no button on Home (person-first photo sending entries stay).

---

## 3. Privacy & Photo Preparation

When the shutter is pressed, `OwnPhotoStore` processes the raw JPEG before displaying it for review:
- **EXIF & GPS Stripping**: Raw camera images from modern smartphone sensors contain metadata, including precise GPS location tags of her home. `OwnPhotoStore` decodes the raw JPEG into an in-memory Android `Bitmap` and re-compresses it as a clean JPEG (quality 85) into internal app storage (`context.filesDir/own_photos/<UUID>.jpg`). This completely strips all EXIF metadata and GPS tags.
- **Orientation Normalization**: Reads EXIF orientation from the raw capture using `android.media.ExifInterface` and applies rotation to the bitmap before stripping metadata, ensuring the photo is displayed and sent upright.
- **Dimension Scaling (`OwnPhotoSize`)**:
  - Scales the image so its longest edge is at most 1600 px (`OwnPhotoSize.LONG_EDGE = 1600`), keeping aspect ratio without upscaling.
- **OOM Protection**:
  - Computes `inSampleSize` during decoding so that large (e.g. 50 MP) sensors do not exhaust the device's heap memory.
- **Temporary File Lifecycle**:
  - Raw captures in `cacheDir` are deleted immediately after preparation.
  - If she presses "Nochmals", presses "Zurück", closes the screen, or walks away (2-minute timeout), the prepared file is deleted.
  - Only when sending succeeds (`onSent`) is the prepared file retained for the outgoing message history.

---

## 4. Transports & Channels

- **Threema**: Outgoing photos are sent via `threemaClient.sendFile` (`IMAGE` message type).
- **WhatsApp**: Outgoing photos are sent via `whatsappClient.sendMedia` (`image/jpeg`).
- **Groups** (since 2026-10-23, `Outbox.sendPhotoToGroup`): a photo goes to the group the way her words to it do.
  - *WhatsApp group*: one `sendMedia` to the group's `@g.us` address; WhatsApp delivers it to every member.
  - *Threema group*: `GroupReplyPolicy` decides who gets it, exactly as for text (refused, with a caregiver event, when the membership is not certain). The blobs are uploaded once (`ThreemaClient.prepareFileMessage`), then the bridge's `SendGroupMessage` with `file_hex` fans out a group file message (type `0x46`, body `creator(8) || group_id(8) || file JSON`) to each member. A partial fan-out is logged as `GROUP_MESSAGE_PART_SENT`.
  - A photo to one member, privately, still goes through `GroupMemberDialog`.
- **SMS / MMS**: SMS-only contacts are never offered photo sending (no MMS transport; `Forwarding.canCarryMedia(recipient)` returns `false`).

---

## 5. Foto-Knopf auf Home (Photo-First Flow)

*Added 2026-10-24.*

In addition to person-first photo sending (reply row, partner dialog, group member dialog), she can also take a photo first from Home, check it calmly, select who should receive it, and confirm before sending.

### Placements on Home (`photoButtonPlacement`)
The button key is rendered by `PhotoHomeButton` (`secondaryContainer` styling, min height 64 dp, or 56 dp square for the clock tile). Configured via `RemoteSettings.photoButtonPlacement`:
- **`card`** (Default): A full-width key "📷 Foto machen" placed directly under the clock row, outside the scrollable column so it never scrolls away. On wide displays (unfolded Fold), it spans across both columns.
- **`clock`**: Sits in the left slot of the clock row (opposite the support button). If the payment card is displayed, it automatically falls back to `card` via `PhotoButtonPlacement.effective` so two controls never occupy the same slot.
- **`faces`**: Appears directly after the favourite face avatars (`thePeople()`) inside the scrolling content (at the end of the people column on wide screens).
- **`bottom`**: Pinned directly above `GentleBottomBar` exclusively on Home.
- **`none`**: Hidden on Home (person-first photo options remain active).

**Visibility condition**: The button is shown only when `enablePhotoSending` is true, the effective placement is not `none`, and the candidate list (`photoRecipients`) contains at least one target. It is never disabled or greyed out—simply absent.

### Photo-First Interaction Sequence
1. **Taking (CameraScreen, `recipient = null`)**:
   - Header title: "Foto machen" (no recipient avatar).
   - Live preview, back button ("Zurück"), shutter button, and camera switch button (if enabled).
2. **Reviewing ("Ist das Foto gut?")**:
   - Header title: "Ist das Foto gut?".
   - The captured photo is displayed frozen in place.
   - Buttons: `[ Nochmals ]` (discards photo, returns to Taking) and `[ Weiter ]` (primary key, opens recipient selection).
   - Inactivity timeout (2 minutes) automatically deletes the prepared photo and dismisses the screen.
3. **Recipient Picker ("Wem schicken?") (`PhotoRecipientFlow`)**:
   - Title: "Wem schicken?".
   - Displays a calm, scrollable column of eligible people and groups (`PhotoTarget`), each showing a 56 dp avatar, bold name, and for groups a plural member count subtitle (e.g. "6 Personen").
   - "Schliessen" button at the bottom returns to the frozen review screen without deleting the photo.
4. **Confirmation & Sending**:
   - **Person**: `GentleConfirmDialog` asking "Foto an [Name] schicken?".
   - **Group**: `GroupPhotoConfirmDialog` showing a 120 dp photo thumbnail, a warning heading with the member count (e.g. "6 Leute sehen das Foto"), and up to 5 circular member avatars (plus a "+N" overflow badge if >5 members) to make group visibility unmistakable.
   - Confirming shows a progress spinner (`isLoading`), preventing accidental duplicate taps.
   - On success: photo is sent via `Outbox.sendPhoto` (handling individual or group fan-out), the temporary file is retained as an outgoing message record, and the UI closes or navigates to the conversation.
   - On error: shows a clear error toast and restores the dialog so she can retry without losing her photo.
   - Back navigation (`BackHandler`) safely steps back: Confirmation -> Recipient Picker -> Review Screen.

### Candidate Ranking (`model/PhotoRecipientRanking.kt`)
The recipient list is ordered by **her own interaction frequency**, not recent incoming activity (which would erroneously prioritize busy groups she only reads):
- **Signals counted**: Her outgoing messages (`MessageDirection.OUTGOING`), her reactions (`Reactions.ownOf != null`), and her placed phone calls (`CallRecord` outgoing).
- **Time window**: 60 days before today (`[todayStart - 60 days, todayStart)`).
- **Midnight freeze**: Events from *today* are strictly excluded. The order changes at most once a day at midnight, never while she is using the phone. This preserves spatial memory and prevents confusion from shifting faces.
- **Per-person multi-channel merge**: Messages across Threema, WhatsApp, and phone calls are combined per individual into a single unified score.
- **Group isolation**: Outgoing messages or reactions in a group count strictly toward the group's score, never boosting individual member scores.
- **Filtering**:
  - Groups she has never written or reacted to (score = 0) are excluded entirely.
  - Status broadcast groups (`status@broadcast`) are always excluded.
  - People with score = 0 remain available at the end of the list.
- **Ties**: Resolved by the caregiver's favourite order, then alphabetically by name.
