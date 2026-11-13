//! Threema attachments: photographs, voice messages, and the still from a video.
//!
//! Until this existed, a Threema photograph or voice message reached her screen as *nothing at
//! all*. The text messages arrived; anything else fell into the `Unknown` branch, where the only
//! consumer is the group-control parser, which returns `None` for it — so the message was
//! dropped. Her daughter sending a picture on Threema produced no message, no sender, no sign
//! that anything had happened. That is worse than WhatsApp's "[Foto]", which at least says
//! somebody wrote.
//!
//! Modern Threema sends all of it as one message type: **file messages** (0x17). The body is JSON
//! naming two blobs — the thing itself and a thumbnail — plus the one symmetric key that decrypts
//! both. The blobs live on Threema's blob servers and are encrypted with NaCl secretbox under
//! that key, with a fixed nonce that differs only in its last byte: 1 for the file, 2 for the
//! thumbnail. The field names, the nonces and the URL template are all taken from Threema's own
//! code rather than guessed.
//!
//! Everything here fails silently. A blob that has expired, a key that does not fit, a server
//! that is unreachable — none of it is her problem, and none of it is worth a caregiver alert.
//! The message keeps its placeholder, which is what it would have had anyway.

use std::{
    path::{Path, PathBuf},
    sync::OnceLock,
    time::Duration,
};

use data_encoding::HEXLOWER;
use serde::Deserialize;

/// The blob's own bytes. Nonce ends in 1.
const FILE_NONCE: [u8; 24] = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1];

/// The preview Threema uploads beside it. Nonce ends in 2.
const THUMBNAIL_NONCE: [u8; 24] = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2];

/// Anything larger is not a snapshot or a voice message; it is a film or an archive.
const MAX_MEDIA_BYTES: u64 = 8 * 1024 * 1024;

/// What a film is allowed to be, which is larger — spelled exactly as the WhatsApp bridge
/// spells it, because a video from her son must not behave differently depending on which app he
/// happened to send it from.
const MAX_VIDEO_BYTES: u64 = 48 * 1024 * 1024;

/// How many attachments are kept on the phone. Newest wins; see `prune`.
const MAX_MEDIA_FILES: usize = 400;

/// The second budget, and the one films made necessary: four hundred photographs is a few
/// hundred megabytes, four hundred films is most of the storage of her phone.
const MAX_MEDIA_DISK_BYTES: u64 = 512 * 1024 * 1024;

/// What the Kotlin side switches on, spelled exactly as the WhatsApp bridge spells it.
pub const KIND_PHOTO: &str = "photo";
pub const KIND_VOICE: &str = "voice";
pub const KIND_VIDEO: &str = "video";

static MEDIA_DIR: OnceLock<PathBuf> = OnceLock::new();
static HTTP: OnceLock<reqwest::Client> = OnceLock::new();

/// Tells this module where to write and what to fetch with.
///
/// Called once at startup. Without it every function here is a no-op, which is the correct
/// behaviour for a build that was given no media directory: text still works.
pub fn configure(dir: PathBuf, client: reqwest::Client) {
    let _ = MEDIA_DIR.set(dir);
    let _ = HTTP.set(client);
}

pub fn is_configured() -> bool {
    MEDIA_DIR.get().is_some()
}

/// Where a blob goes when it is hers rather than somebody else's.
///
/// Spelled as libthreema spells it (`common/config.rs`), and the counterpart of the download
/// template a few lines below the nonces above.
const UPLOAD_URL: &str = "https://ds-blobp-upload.threema.ch/upload";

/// The blob server answers 403 to a request that does not name a client.
///
/// Verified against the live server rather than guessed: the same upload is refused with the
/// default agent and accepted with this one. It says what this is, which is what a user agent is
/// for; it does not pretend to be the Threema app.
const UPLOAD_USER_AGENT: &str = "libthreema (gentle-connection)";

/// What an upload leaves behind: the two names the recipient will fetch it by, and the one key
/// that opens either.
pub struct UploadedMedia {
    /// Blob id of the file itself, hex.
    pub blob_id: String,
    /// Blob id of the still that stands in for it, hex — absent when there is none, which is
    /// every case except a film.
    pub thumbnail_blob_id: Option<String>,
    /// The key both blobs are sealed with, hex. Without it the recipient has two dead names.
    pub key: String,
    /// The size of the *plaintext*, which is what a file message declares.
    pub size: u64,
}

/// Seals a file under a fresh key and puts it on Threema's blob servers.
///
/// The mirror of [`fetch`], and deliberately the same shape: one key, the file under nonce 1 and
/// the still under nonce 2. A new key every time, because a key is per-message — reusing one
/// would let anybody who was ever sent one photograph read the next.
///
/// Fails silently, like everything else here, and says so by returning `None`: a forward that
/// does not happen is told to her as a forward that did not happen, which is the only thing she
/// can act on.
pub async fn upload(path: &str, thumbnail_path: Option<&str>) -> Option<UploadedMedia> {
    let client = HTTP.get()?;

    let plaintext = std::fs::read(path).ok()?;
    let size = u64::try_from(plaintext.len()).ok()?;
    // The same ceiling a film is fetched under. Something larger than this arrived on her phone
    // in pieces or not at all, and sending it back out would be a minutes-long upload on a
    // mobile connection with nothing on her screen to say so.
    if plaintext.is_empty() || size > MAX_VIDEO_BYTES {
        return None;
    }

    let key = random_key()?;
    let blob_id = put_blob(client, seal(&plaintext, &key, &FILE_NONCE)?).await?;

    // A still is worth having and never worth failing over: a film whose thumbnail did not go up
    // still plays, and a photograph never had one.
    let thumbnail_blob_id = match thumbnail_path {
        Some(thumbnail) => match std::fs::read(thumbnail).ok() {
            Some(bytes) if !bytes.is_empty() => match seal(&bytes, &key, &THUMBNAIL_NONCE) {
                Some(sealed) => put_blob(client, sealed).await,
                None => None,
            },
            _ => None,
        },
        None => None,
    };

    Some(UploadedMedia {
        blob_id,
        thumbnail_blob_id,
        key: HEXLOWER.encode(&key),
        size,
    })
}

/// One blob, up.
///
/// Multipart under the field name `blob`, which is not a choice: the server answers 415 to the
/// same bytes sent as a plain body. The answer is the blob id in hex, and nothing else.
async fn put_blob(client: &reqwest::Client, sealed: Vec<u8>) -> Option<String> {
    let part = reqwest::multipart::Part::bytes(sealed).file_name("blob");
    let form = reqwest::multipart::Form::new().part("blob", part);

    let response = client
        .post(UPLOAD_URL)
        .header(reqwest::header::USER_AGENT, UPLOAD_USER_AGENT)
        .multipart(form)
        // Generous: this is an upload over a mobile connection, and the thing being uploaded may
        // be a film.
        .timeout(Duration::from_secs(120))
        .send()
        .await
        .ok()?;
    if !response.status().is_success() {
        return None;
    }

    let blob_id = response.text().await.ok()?.trim().to_ascii_lowercase();
    // A blob id is sixteen bytes. Anything else is an error page that happened to arrive with a
    // 200, and naming it in a file message would send the recipient nothing.
    if blob_id.len() != 32 || HEXLOWER.decode(blob_id.as_bytes()).is_err() {
        return None;
    }
    Some(blob_id)
}

/// A fresh secretbox key.
fn random_key() -> Option<[u8; 32]> {
    use crypto_secretbox::aead::{OsRng, rand_core::RngCore};

    let mut key = [0u8; 32];
    OsRng.try_fill_bytes(&mut key).ok()?;
    Some(key)
}

/// Seals bytes the way Threema's blob servers expect to be handed them.
///
/// The inverse of the unsealing in `download_and_open`, and tested against it: a round trip
/// through both is the only check here that does not need Threema's servers.
fn seal(plaintext: &[u8], key: &[u8; 32], nonce: &[u8; 24]) -> Option<Vec<u8>> {
    use crypto_secretbox::{
        XSalsa20Poly1305,
        aead::{AeadInPlace, KeyInit},
    };

    let cipher = XSalsa20Poly1305::new_from_slice(key).ok()?;
    let mut sealed = plaintext.to_vec();
    cipher.encrypt_in_place(nonce.into(), &[], &mut sealed).ok()?;
    Some(sealed)
}

/// The JSON body of a file message.
///
/// One-letter field names, which are Threema's, not ours — see `FileData.java` in the Threema
/// client. Only the fields this app can act on are read; the rest (correlation id, deprecated
/// rendering type, metadata) are ignored rather than rejected, so a newer Threema adding a field
/// does not turn her mother's photograph into nothing.
#[derive(Debug, Deserialize)]
pub struct FileMessage {
    /// Blob id of the file itself, hex.
    #[serde(rename = "b")]
    pub blob_id: Option<String>,
    /// Blob id of the thumbnail, hex.
    #[serde(rename = "t")]
    pub thumbnail_blob_id: Option<String>,
    /// The key both blobs are encrypted with, hex. Without it nothing can be read.
    #[serde(rename = "k")]
    pub key: String,
    #[serde(rename = "m", default)]
    pub mime: String,
    #[serde(rename = "n", default)]
    pub name: String,
    #[serde(rename = "s", default)]
    pub size: u64,
    /// What somebody typed under it. This is the message where there is one.
    #[serde(rename = "d", default)]
    pub caption: String,
}

/// What kind of thing this is, as far as her screen is concerned.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MediaKind {
    Photo,
    Voice,
    /// Shown as a still, never played.
    Video,
    /// A document. Named, never opened — there is nothing on this phone to open it with.
    Other,
}

impl FileMessage {
    /// Decided by mime type rather than by Threema's rendering type.
    ///
    /// The rendering type says how Threema's own client would lay it out, which is a different
    /// question from what this app can do with it — and it is set inconsistently by older
    /// clients. "audio/*" is somebody talking whatever the sender's app called it.
    pub fn kind(&self) -> MediaKind {
        let mime = self.mime.to_ascii_lowercase();
        if mime.starts_with("image/") {
            MediaKind::Photo
        } else if mime.starts_with("audio/") {
            MediaKind::Voice
        } else if mime.starts_with("video/") {
            MediaKind::Video
        } else {
            MediaKind::Other
        }
    }

    /// What to call the file on disk.
    ///
    /// Taken from the mime type rather than assumed, because the assumption was wrong on the
    /// first real message: a Threema voice message arrived as `ftypmp42` — AAC in an MP4
    /// container — and had been written as `.ogg` on the strength of WhatsApp doing it that way.
    /// MediaPlayer sniffs the container and would have played it anyway, which is exactly why
    /// this would have gone unnoticed.
    pub fn extension(&self) -> &'static str {
        match self.mime.to_ascii_lowercase().split(';').next().unwrap_or("").trim() {
            "image/jpeg" | "image/jpg" => "jpg",
            "image/png" => "png",
            "image/gif" => "gif",
            "image/webp" => "webp",
            "audio/aac" | "audio/mp4" | "audio/m4a" | "audio/x-m4a" => "m4a",
            "audio/ogg" | "audio/opus" => "ogg",
            "audio/mpeg" | "audio/mp3" => "mp3",
            "audio/amr" => "amr",
            "video/mp4" | "video/mpeg" => "mp4",
            "video/3gpp" => "3gp",
            "video/quicktime" => "mov",
            "video/webm" => "webm",
            // Unknown, and deliberately not guessed. Android decides what a file is by looking
            // inside it, so a name it does not recognise costs nothing.
            _ => "bin",
        }
    }

    /// The line she reads when there is nothing else to show, or before the blob arrives.
    ///
    /// German, and worded exactly as the WhatsApp bridge words it, because the two messengers
    /// must be indistinguishable on her screen — a person is a person, and a photograph is a
    /// photograph, whichever app carried it.
    pub fn placeholder(&self) -> String {
        let caption = self.caption.trim();
        if !caption.is_empty() {
            return caption.to_owned();
        }
        match self.kind() {
            MediaKind::Photo => "[Foto]".to_owned(),
            MediaKind::Voice => "[Sprachnachricht]".to_owned(),
            MediaKind::Video => "[Video]".to_owned(),
            MediaKind::Other => "[Dokument]".to_owned(),
        }
    }
}

/// Reads a file message out of a decrypted message body.
///
/// `skip` is 16 for a group message and 0 for a contact one: a group body carries the creator
/// identity and group id in front of the payload, and feeding those to a JSON parser produces
/// nothing but a parse error.
pub fn parse(body: &[u8], skip: usize) -> Option<FileMessage> {
    let json = body.get(skip..)?;
    let parsed: FileMessage = serde_json::from_slice(json).ok()?;
    // The key is the whole of the ability to read either blob. A file message without one is
    // either not a file message or is unreadable, and both end the same way.
    if parsed.key.len() != 64 {
        return None;
    }
    Some(parsed)
}

/// Fetches whatever of this message is worth fetching, and reports where it landed.
///
/// Returns the events to emit. Async, and called from a spawned task: a blob download over a
/// mobile connection takes seconds, and the message it belongs to is already on her screen.
pub async fn fetch(message_id: String, file: FileMessage) -> Vec<(String, String, String)> {
    let Some(dir) = MEDIA_DIR.get() else {
        return Vec::new();
    };
    let Some(client) = HTTP.get() else {
        return Vec::new();
    };
    let Some(key) = decode_key(&file.key) else {
        return Vec::new();
    };

    let mut events = Vec::new();
    for (blob_id, nonce, kind, extension) in blobs_worth_fetching(&file) {
        let Some(blob_id) = blob_id.filter(|id| !id.is_empty()) else {
            continue;
        };

        let Some(plaintext) = download_and_open(client, &blob_id, &key, &nonce).await else {
            continue;
        };

        let path = dir.join(format!("{}.{}", safe_name(&message_id), extension));
        // Written beside its name and renamed onto it, so that a file under its real name is
        // always a whole one. The app attaches a film it finds on disk even when the event saying
        // so was lost (StrandedFilms), and a bridge killed halfway through a film would otherwise
        // leave half of one there to be attached.
        let partial = dir.join(format!("{}.{}.part", safe_name(&message_id), extension));
        if std::fs::write(&partial, &plaintext).is_err()
            || std::fs::rename(&partial, &path).is_err()
        {
            let _ = std::fs::remove_file(&partial);
            continue;
        }
        prune(dir);

        events.push((
            message_id.clone(),
            path.to_string_lossy().into_owned(),
            kind.to_owned(),
        ));
    }
    events
}

/// What to go and get for this message, and what to call each of them on the way back.
///
/// A list rather than one blob because of video: a film message is worth fetching *twice*, the
/// still and then the film, and the still must not wait behind the film. They are returned in
/// that order and fetched in it, so on a slow connection the picture is on her screen while the
/// film is still coming — and if the film never arrives, which for an expired blob it will not,
/// she is left with exactly what she had before films were fetched at all.
///
/// Pure, and separate from the fetching, because this is the part worth testing: everything
/// below it needs Threema's blob servers and a real key.
fn blobs_worth_fetching(
    file: &FileMessage,
) -> Vec<(Option<String>, [u8; 24], &'static str, &'static str)> {
    match file.kind() {
        // The photograph itself when it is a reasonable size, and Threema's own preview when it
        // is not — a picture that is slightly soft is worth far more to her than no picture.
        MediaKind::Photo if file.size <= MAX_MEDIA_BYTES && file.blob_id.is_some() => {
            vec![(file.blob_id.clone(), FILE_NONCE, KIND_PHOTO, file.extension())]
        }
        // A thumbnail is always JPEG whatever the thing it previews was.
        MediaKind::Photo => {
            vec![(file.thumbnail_blob_id.clone(), THUMBNAIL_NONCE, KIND_PHOTO, "jpg")]
        }

        MediaKind::Video => {
            // The still first, always: it is small, it is the picture of whoever is in the film,
            // and it is what stands in for the film in every case where the film cannot be had.
            let mut blobs = vec![(
                file.thumbnail_blob_id.clone(),
                THUMBNAIL_NONCE,
                KIND_PHOTO,
                "jpg",
            )];
            // Then the film, when it is a film somebody sent rather than an evening's recording.
            if file.size <= MAX_VIDEO_BYTES && file.blob_id.is_some() {
                blobs.push((file.blob_id.clone(), FILE_NONCE, KIND_VIDEO, file.extension()));
            }
            blobs
        }

        MediaKind::Voice if file.size <= MAX_MEDIA_BYTES => {
            vec![(file.blob_id.clone(), FILE_NONCE, KIND_VOICE, file.extension())]
        }

        // A document has no useful rendering here, and a voice message too large to be a voice
        // message is not one. Both keep their placeholder.
        MediaKind::Voice | MediaKind::Other => Vec::new(),
    }
}

/// Downloads one blob and unseals it.
async fn download_and_open(
    client: &reqwest::Client,
    blob_id_hex: &str,
    key: &[u8; 32],
    nonce: &[u8; 24],
) -> Option<Vec<u8>> {
    let blob_id = HEXLOWER.decode(blob_id_hex.to_ascii_lowercase().as_bytes()).ok()?;
    let first = blob_id.first()?;

    // Threema's production blob server, spelled as libthreema spells it: the prefix is the first
    // byte in lower-case hex and is deliberately *not* zero-padded.
    let url = format!("https://ds-blobp-{first:x}.threema.ch/{}", HEXLOWER.encode(&blob_id));

    let response = client
        .get(&url)
        .timeout(Duration::from_secs(30))
        .send()
        .await
        .ok()?;
    if !response.status().is_success() {
        return None;
    }
    let mut data = response.bytes().await.ok()?.to_vec();

    // NaCl secretbox: the Poly1305 tag is at the front, which this crate handles for us — it
    // overrides the AEAD convention precisely to stay byte-compatible with NaCl.
    use crypto_secretbox::{
        aead::{AeadInPlace, KeyInit},
        XSalsa20Poly1305,
    };
    let cipher = XSalsa20Poly1305::new_from_slice(key).ok()?;
    cipher.decrypt_in_place(nonce.into(), &[], &mut data).ok()?;
    Some(data)
}

fn decode_key(hex: &str) -> Option<[u8; 32]> {
    let bytes = HEXLOWER.decode(hex.to_ascii_lowercase().as_bytes()).ok()?;
    bytes.try_into().ok()
}

/// Reduces a message id to something that can only name a file inside the media directory.
fn safe_name(id: &str) -> String {
    id.chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '.' })
        .collect()
}

/// Keeps the newest attachments that fit in both budgets and deletes the rest.
///
/// Her phone is not an archive, and a launcher that fills the storage of the phone it is the
/// launcher for fails at everything, not just at photographs.
///
/// Two budgets rather than one, since films started being kept: counting files was enough while
/// everything here was a photograph or a few seconds of speech, and it stopped being enough the
/// moment a single attachment could be forty megabytes. A file survives only if it is inside
/// both, so whichever budget is tighter is the one that decides. The same rule, and the same two
/// numbers, as the WhatsApp bridge's `pruneMedia`.
fn prune(dir: &Path) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    let now = std::time::SystemTime::now();
    let mut files: Vec<(PathBuf, std::time::SystemTime, u64)> = entries
        .flatten()
        .filter_map(|entry| {
            let path = entry.path();
            let metadata = entry.metadata().ok()?;
            let modified = metadata.modified().ok()?;
            if path.extension().and_then(|e| e.to_str()) == Some("part") {
                if now.duration_since(modified).map_or(false, |d| d > Duration::from_secs(300)) {
                    let _ = std::fs::remove_file(&path);
                }
                return None;
            }
            Some((path, modified, metadata.len()))
        })
        .collect();
    files.sort_by(|a, b| b.1.cmp(&a.1));

    let mut kept: u64 = 0;
    for (index, (path, _, bytes)) in files.into_iter().enumerate() {
        kept = kept.saturating_add(bytes);
        // Newest first, so this reads as "everything from here on is older than what is kept".
        // The file that breaks the budget goes with them: keeping it because it happened to be
        // the one that crossed the line would make the budget depend on arrival order.
        if index >= MAX_MEDIA_FILES || kept > MAX_MEDIA_DISK_BYTES {
            let _ = std::fs::remove_file(path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Taken from Threema's own `FileData`: one-letter keys, and a caption in "d".
    const REAL_SHAPE: &str = r#"{"b":"0102030405060708090a0b0c0d0e0f10","t":"1112131415161718191a1b1c1d1e1f20","k":"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f","m":"image/jpeg","n":"photo.jpg","s":204800,"i":0,"j":1,"d":"Schau mal"}"#;

    #[test]
    fn a_contact_file_message_is_read_whole() {
        let file = parse(REAL_SHAPE.as_bytes(), 0).expect("should parse");

        assert_eq!(file.mime, "image/jpeg");
        assert_eq!(file.size, 204_800);
        assert_eq!(file.kind(), MediaKind::Photo);
        // The caption is the message where there is one: somebody writing "schau mal" under a
        // photograph meant the words.
        assert_eq!(file.placeholder(), "Schau mal");
    }

    #[test]
    fn a_group_file_message_skips_its_header() {
        // A group body carries eight bytes of creator identity and eight of group id in front of
        // the payload. Handing those to a JSON parser produces nothing at all, which is exactly
        // how a group photograph would silently vanish.
        let mut body = b"ABCDEFGH\x01\x02\x03\x04\x05\x06\x07\x08".to_vec();
        body.extend_from_slice(REAL_SHAPE.as_bytes());

        assert!(parse(&body, 16).is_some());
        assert!(parse(&body, 0).is_none());
    }

    #[test]
    fn a_file_message_without_a_key_is_not_usable() {
        // The key is the whole of the ability to read either blob.
        let no_key = r#"{"b":"01","m":"image/jpeg","s":1}"#;
        let short_key = r#"{"b":"01","k":"00ff","m":"image/jpeg","s":1}"#;

        assert!(parse(no_key.as_bytes(), 0).is_none());
        assert!(parse(short_key.as_bytes(), 0).is_none());
    }

    #[test]
    fn the_kind_comes_from_the_mime_type() {
        let of = |mime: &str| {
            let json = format!(
                r#"{{"k":"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f","m":"{mime}"}}"#
            );
            parse(json.as_bytes(), 0).expect("should parse").kind()
        };

        assert_eq!(of("image/jpeg"), MediaKind::Photo);
        assert_eq!(of("image/png"), MediaKind::Photo);
        // Threema has sent voice messages as both over the years, and older clients set the
        // rendering type inconsistently — which is why this is decided by mime and not by that.
        assert_eq!(of("audio/aac"), MediaKind::Voice);
        assert_eq!(of("audio/ogg; codecs=opus"), MediaKind::Voice);
        assert_eq!(of("AUDIO/MP4"), MediaKind::Voice);
        assert_eq!(of("video/mp4"), MediaKind::Video);
        assert_eq!(of("application/pdf"), MediaKind::Other);
    }

    #[test]
    fn each_kind_has_something_to_say_for_itself() {
        let of = |mime: &str| {
            let json = format!(
                r#"{{"k":"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f","m":"{mime}"}}"#
            );
            parse(json.as_bytes(), 0).expect("should parse").placeholder()
        };

        // Worded exactly as the WhatsApp bridge words them: her screen must not be able to tell
        // which messenger carried a photograph.
        assert_eq!(of("image/jpeg"), "[Foto]");
        assert_eq!(of("audio/aac"), "[Sprachnachricht]");
        assert_eq!(of("video/mp4"), "[Video]");
        assert_eq!(of("application/pdf"), "[Dokument]");
    }

    #[test]
    fn the_extension_comes_from_the_mime_type_and_is_not_assumed() {
        let of = |mime: &str| {
            let json = format!(
                r#"{{"k":"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f","m":"{mime}"}}"#
            );
            parse(json.as_bytes(), 0).expect("should parse").extension()
        };

        // The real one, off her phone: a Threema voice message is AAC in an MP4 container, and
        // had been written as `.ogg` because WhatsApp's are Opus in Ogg.
        assert_eq!(of("audio/aac"), "m4a");
        assert_eq!(of("audio/mp4"), "m4a");
        assert_eq!(of("audio/ogg; codecs=opus"), "ogg");
        assert_eq!(of("image/jpeg"), "jpg");
        assert_eq!(of("image/png"), "png");
        assert_eq!(of("video/mp4"), "mp4");
        assert_eq!(of("video/3gpp"), "3gp");
        // Not guessed. Android decides what a file is by looking inside it.
        assert_eq!(of("application/octet-stream"), "bin");
        assert_eq!(of(""), "bin");
    }

    /// A file message of a given mime type and size, with both blobs present.
    fn message_of(mime: &str, size: u64) -> FileMessage {
        let json = format!(
            r#"{{"b":"0102030405060708090a0b0c0d0e0f10","t":"1112131415161718191a1b1c1d1e1f20","k":"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f","m":"{mime}","s":{size}}}"#
        );
        parse(json.as_bytes(), 0).expect("should parse")
    }

    #[test]
    fn a_video_is_fetched_as_its_still_and_then_its_film() {
        let blobs = blobs_worth_fetching(&message_of("video/mp4", 6 * 1024 * 1024));

        assert_eq!(blobs.len(), 2);
        // The still first, and it is the thumbnail blob under the thumbnail nonce. The order is
        // the feature: on a slow connection the picture of whoever is in the film is on her
        // screen while the film is still coming.
        assert_eq!(blobs[0].2, KIND_PHOTO);
        assert_eq!(blobs[0].1, THUMBNAIL_NONCE);
        assert_eq!(blobs[0].3, "jpg");

        assert_eq!(blobs[1].2, KIND_VIDEO);
        assert_eq!(blobs[1].1, FILE_NONCE);
        // A different extension from the still, so the two land as two files under the one
        // message id rather than one overwriting the other.
        assert_eq!(blobs[1].3, "mp4");
    }

    #[test]
    fn a_video_too_large_to_be_worth_it_still_gives_her_the_picture() {
        // An evening's recording rather than something somebody sent. The film is left on the
        // server; what she gets is exactly what she got before films were fetched at all.
        let blobs = blobs_worth_fetching(&message_of("video/mp4", MAX_VIDEO_BYTES + 1));

        assert_eq!(blobs.len(), 1);
        assert_eq!(blobs[0].2, KIND_PHOTO);
    }

    #[test]
    fn a_photograph_is_still_fetched_the_way_it_always_was() {
        // The film work must not have changed what happens to a picture: itself when it is a
        // reasonable size, Threema's preview when it is not, and never both.
        let full = blobs_worth_fetching(&message_of("image/jpeg", 200_000));
        assert_eq!(full.len(), 1);
        assert_eq!(full[0].1, FILE_NONCE);

        let huge = blobs_worth_fetching(&message_of("image/jpeg", MAX_MEDIA_BYTES + 1));
        assert_eq!(huge.len(), 1);
        assert_eq!(huge[0].1, THUMBNAIL_NONCE);
        assert_eq!(huge[0].2, KIND_PHOTO);
    }

    #[test]
    fn a_voice_message_is_fetched_alone_and_a_document_not_at_all() {
        let voice = blobs_worth_fetching(&message_of("audio/aac", 300_000));
        assert_eq!(voice.len(), 1);
        assert_eq!(voice[0].2, KIND_VOICE);

        // A voice message too large to be one is not one, and there is nothing on this phone to
        // open a document with. Both keep their placeholder and cost no bandwidth.
        assert!(blobs_worth_fetching(&message_of("audio/aac", MAX_MEDIA_BYTES + 1)).is_empty());
        assert!(blobs_worth_fetching(&message_of("application/pdf", 1000)).is_empty());
    }

    /// The one half of an upload that can be checked without Threema's servers.
    ///
    /// A blob sealed here has to be openable by the same code that opens everybody else's — same
    /// cipher, same nonce, same key — or the recipient is sent sixteen kilobytes of noise and a
    /// key that does not fit it, and neither end has any way of telling.
    #[test]
    fn a_sealed_blob_opens_again_under_the_key_it_was_sealed_with() {
        use crypto_secretbox::{
            XSalsa20Poly1305,
            aead::{AeadInPlace, KeyInit},
        };

        let key = super::random_key().expect("a key");
        let plaintext = b"a photograph of somebody she loves".to_vec();

        let mut sealed = super::seal(&plaintext, &key, &super::FILE_NONCE).expect("sealed");
        assert_ne!(sealed, plaintext, "the blob went up in the clear");

        let cipher = XSalsa20Poly1305::new_from_slice(&key).expect("cipher");
        cipher
            .decrypt_in_place(&super::FILE_NONCE.into(), &[], &mut sealed)
            .expect("it opens");
        assert_eq!(sealed, plaintext);
    }

    /// Two forwards of the same photograph must not share a key.
    #[test]
    fn every_upload_gets_a_key_of_its_own() {
        let first = super::random_key().expect("a key");
        let second = super::random_key().expect("a key");
        assert_ne!(first, second);
        assert_ne!(first, [0u8; 32], "an all-zero key is no key");
    }

    /// The still is sealed under a different nonce than the film, or the recipient reads one as
    /// the other.
    #[test]
    fn the_still_and_the_film_are_sealed_apart() {
        let key = super::random_key().expect("a key");
        let bytes = b"the same bytes twice".to_vec();

        let file = super::seal(&bytes, &key, &super::FILE_NONCE).expect("sealed");
        let thumbnail = super::seal(&bytes, &key, &super::THUMBNAIL_NONCE).expect("sealed");
        assert_ne!(file, thumbnail);
    }

    #[test]
    fn a_message_id_can_only_name_a_file_in_its_own_directory() {
        // The property, not the spelling: nothing that could climb out of the directory survives.
        let escaped = safe_name("../../etc/passwd");
        assert!(!escaped.contains('/'), "{escaped}");
        assert_eq!(escaped, "......etc.passwd");

        // An ordinary Threema message id is left exactly as it is, so a redelivery overwrites
        // its own file rather than accumulating a second one.
        assert_eq!(safe_name("00ff11aa22bb33cc"), "00ff11aa22bb33cc");
    }
}
