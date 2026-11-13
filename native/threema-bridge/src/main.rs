//! Example for usage of the Chat Server E2EE Protocol, connecting to the chat server and receiving incoming
//! messages.
#![expect(unused_crate_dependencies, reason = "Example triggered false positive")]
#![expect(
    clippy::integer_division_remainder_used,
    reason = "Some internal of tokio::select triggers this"
)]
#![expect(
    unreachable_code,
    unused_variables,
    clippy::todo,
    reason = "TODO(LIB-16): Finalise this, then remove me"
)]

macro_rules! println {
    ($($arg:tt)*) => {{
        use std::io::Write as _;
        let mut out = std::io::stdout();
        let _ = std::io::Write::write_fmt(&mut out, format_args!($($arg)*));
        let _ = std::io::Write::write(&mut out, b"\n");
        let _ = std::io::Write::flush(&mut out);
    }};
}

use core::cell::RefCell;
use std::{collections::HashMap, ffi::OsString, io, rc::Rc};

use anyhow::bail;
use clap::Parser;

mod media;
use data_encoding::HEXLOWER;
use libthreema::{
    cli::{
        CommonConfig, CommonConfigOptions, ConfigEnvironmentOption, FullIdentityConfig,
        FullIdentityConfigOptions,
    },
    common::{ClientInfo, keys::ClientKey},
    csp::{
        CspProtocol, CspProtocolContext, CspProtocolInstruction, CspStateUpdate,
        payload::{EchoPayload, IncomingPayload, MessageAck, MessageWithMetadataBox, OutgoingFrame, OutgoingPayload},
    },
    csp_e2e::{
        CspE2eProtocol, CspE2eProtocolContextInit,
        contacts::{
            create::{CreateContactsInstruction, CreateContactsResponse},
            lookup::ContactsLookupResponse,
            update::{UpdateContactsInstruction, UpdateContactsResponse},
        },
        message::task::incoming::{
            IncomingMessageInstruction, IncomingMessageLoop, IncomingMessageResponse, IncomingMessageTask,
        },
        reflect::{ReflectInstruction, ReflectResponse},
        transaction::{
            begin::{BeginTransactionInstruction, BeginTransactionResponse},
            commit::{CommitTransactionInstruction, CommitTransactionResponse},
        },
    },
    https::cli::https_client_builder,
    model::provider::in_memory::{DefaultShortcutProvider, InMemoryDb, InMemoryDbInit, InMemoryDbSettings},
    utils::logging::init_stderr_logging,
};
use tokio::{
    io::{AsyncReadExt as _, AsyncWriteExt as _},
    net::TcpStream,
    signal,
    sync::mpsc,
    time::{self, Duration, Instant, MissedTickBehavior},
};
use tracing::{Level, debug, error, info, trace, warn};

use libthreema::https::directory::{
    request_identities, handle_identities_result,
    set_feature_mask_phase1_request, set_feature_mask_phase2_request,
    handle_set_feature_mask_phase2_result, handle_authentication_challenge,
    obtain_turn_servers_phase1_request, obtain_turn_servers_phase2_request,
    handle_obtain_turn_servers_phase2_result, TurnServerResponse,
    create_identity_phase1_request, create_identity_with_license_phase2_request,
    handle_create_identity_with_license_result,
    match_token_phase1_request, match_token_phase2_request, handle_match_token_phase2_result,
    match_identities_request, handle_match_identities_result,
};
use libthreema::common::config::Flavor;
use libthreema::common::ThreemaId;
use libthreema::id_backup::{IdentityBackupData, decrypt_identity_backup, encrypt_identity_backup};


macro_rules! println {
    ($($arg:tt)*) => {{
        use std::io::Write as _;
        let mut stdout = std::io::stdout();
        let _ = std::io::Write::write_fmt(&mut stdout, format_args!($($arg)*));
        let _ = std::io::Write::write_all(&mut stdout, b"\n");
        let _ = std::io::Write::flush(&mut stdout);
    }};
}

use std::io::BufRead;

#[derive(serde::Deserialize, Debug)]
#[serde(tag = "type")]
pub enum BridgeInput {
    SendMessage {
        recipient: String,
        text: String,
        /// The id to send it under, as sixteen hexadecimal characters, or absent for a random one.
        ///
        /// The app chooses this so that it knows afterwards what its own message is called. It
        /// could not before: the id was generated here and never reported, so a reaction to
        /// something she had written named a message the app had no record of, and was dropped.
        #[serde(default)]
        message_id: Option<String>,
    },
    SendRaw {
        recipient: String,
        message_type: u8,
        data_hex: String,
    },
    /// Puts an attachment that is already on this phone back onto Threema's blob servers, so that
    /// it can be sent on to somebody else.
    ///
    /// Upload only. What comes back is the two names and the key that a file message is made of;
    /// building that message and sending it is [`BridgeInput::SendRaw`]'s job, because a file
    /// message is an ordinary message once its blobs exist.
    UploadMedia {
        /// Names the answer, so that a caller with two forwards in flight can tell them apart.
        request_id: String,
        /// The file on this phone. Its bytes go up sealed; the path itself never leaves.
        path: String,
        /// The still that stands in for a film. Absent for everything else.
        #[serde(default)]
        thumbnail_path: Option<String>,
    },
    SendCall {
        recipient: String,
        message_type: u8,
        data_hex: String,
    },
    /// One text message to several people.
    ///
    /// There is no group address to send to: a Threema group message is the same message encrypted
    /// and sent to **every member individually**, each copy carrying the group it belongs to. The
    /// fan-out is the send, which is why this cannot be done by the caller looping over
    /// [`BridgeInput::SendMessage`] — every copy needs the group envelope, and the caller needs to
    /// hear which members it actually reached.
    ///
    /// `members` is exactly who to send to and nobody else: the caller has already decided that
    /// (it owns the membership, and the rule that a group with an unknown membership may not be
    /// answered at all). The sender's own identity must not be in it.
    SendGroupMessage {
        /// The group's creator — part of its identity, and one of the recipients.
        creator: String,
        /// Group id as hex in wire byte order, exactly as it is reported on incoming messages.
        group_id: String,
        members: Vec<String>,
        /// Empty when [`BridgeInput::SendGroupMessage::file_hex`] is given.
        #[serde(default)]
        text: String,
        /// A photograph instead of words: the JSON body of a file message whose blobs are already
        /// uploaded (see [`BridgeInput::UploadMedia`]), as hex. Present, it goes out as a group
        /// file message under the same fan-out, so a photograph reaches the same people a text
        /// would — the membership decision is the caller's either way.
        #[serde(default)]
        file_hex: Option<String>,
        /// As on [`BridgeInput::SendMessage`]. One id for every copy, which is what makes the
        /// several copies of a group message one message as far as anybody reacting is concerned.
        #[serde(default)]
        message_id: Option<String>,
    },
    SendReaction {
        recipient: String,
        target_message_id: String,
        emoji: String,
        #[serde(default)]
        withdraw: bool,
    },
    SendGroupReaction {
        creator: String,
        group_id: String,
        members: Vec<String>,
        target_message_id: String,
        emoji: String,
        #[serde(default)]
        withdraw: bool,
    },
    /// Seed the in-memory contact store (directory lookup) so that incoming
    /// group messages from these identities are not discarded.
    AddContacts {
        identities: Vec<String>,
    },
    /// Ask the directory server which of these addresses belong to a Threema identity.
    ///
    /// Phone numbers must already be normalised to E.164 *without* the leading `+` — digits
    /// only. Normalisation is the caller's job because it needs the user's region to make sense
    /// of a number written as `079 123 45 67`, and the caller is the one that knows it.
    MatchContacts {
        #[serde(default)]
        phone_numbers: Vec<String>,
        #[serde(default)]
        emails: Vec<String>,
    },
    FetchTurnCredentials {
        #[serde(default)]
        turn_type: Option<String>,
    },
}

/// One address that turned out to belong to a Threema identity.
///
/// The address is echoed back rather than the hash the server answered with, so the caller can
/// join the result to the contact it came from without repeating the hashing.
#[derive(Debug, Clone, serde::Serialize)]
pub struct ContactMatch {
    pub identity: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub phone_number: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub email: Option<String>,
}

#[derive(serde::Serialize)]
#[serde(tag = "type")]
pub enum BridgeOutput {
    /// Somebody reacted to a message. Attaches to the message it names; never a message itself.
    ///
    /// A reaction shown as its own line is an emoji from nobody, floating in the conversation
    /// with nothing to say what it is about — which is what a reaction became before this
    /// existed, when it was one at all.
    Reaction {
        sender: String,
        /// The message being reacted to, named the way this bridge names messages.
        target_message_id: String,
        emoji: String,
        /// Taking a reaction back is as real an event as making one.
        withdrawn: bool,
        /// Whether this is an older client's acknowledge/decline rather than a modern reaction.
        ///
        /// The difference is not cosmetic and the app cannot work it out from the emoji: a modern
        /// reaction is one of a set a person may add to and take back one at a time, while
        /// acknowledge and decline are a single slot that the next one overwrites. Without this,
        /// somebody on an old client who acknowledged and then declined would sit on her screen
        /// as 👍👎 — every reaction they ever sent, none of them withdrawable.
        legacy: bool,
        timestamp: u64,
        #[serde(skip_serializing_if = "Option::is_none")]
        group_creator: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        group_id: Option<String>,
    },
    /// An attachment that finished downloading, belonging to a message already delivered.
    ///
    /// Separate from `Message` because the two travel at different speeds: the placeholder is on
    /// her screen the moment the message arrives, and the photograph or the voice message follows
    /// when the blob has been fetched and unsealed.
    Media {
        message_id: String,
        path: String,
        /// `photo` or `voice`, spelled exactly as the WhatsApp bridge spells it.
        kind: String,
    },
    /// An attachment of hers that is now on the blob servers, waiting to be named in a message.
    MediaUploaded {
        request_id: String,
        blob_id: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        thumbnail_blob_id: Option<String>,
        /// Hex, and the whole of the ability to read either blob.
        key: String,
        /// Of the plaintext, which is the number a file message declares.
        size: u64,
    },
    /// It did not go up. Said out loud rather than left silent: unlike a download, somebody is
    /// waiting on this one and has to be told it did not happen.
    MediaUploadFailed {
        request_id: String,
    },
    Message {
        sender: String,
        text: String,
        timestamp: u64,
        message_id: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        group_creator: Option<String>,
        /// Group ID in wire byte order (hex)
        #[serde(skip_serializing_if = "Option::is_none")]
        group_id: Option<String>,
    },
    Call {
        sender: String,
        message_type: u8,
        data_hex: String,
        timestamp: u64,
        message_id: String,
    },
    Unknown {
        sender: String,
        message_type: i32,
        data_hex: String,
        timestamp: u64,
        message_id: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        group_creator: Option<String>,
        /// Group ID in wire byte order (hex)
        #[serde(skip_serializing_if = "Option::is_none")]
        group_id: Option<String>,
    },
    /// A freshly registered identity. Emitted once, by `--create-identity`, and never again:
    /// the caller is expected to persist all three fields and reuse them from then on.
    ///
    /// `client_key` IS the account. It goes to stdout, which only the parent process reads, and
    /// must never reach the log, a crash report or argv.
    IdentityCreated {
        identity: String,
        server_group: String,
        client_key: String,
    },
    /// Her account, encrypted into the string Threema itself uses for an ID backup.
    ///
    /// Emitted by `--export-identity`. Dashed Base32, and the standard format: it can be restored
    /// into the real Threema app as easily as back into this one, which is the point — an account
    /// that can only be moved by the app that made it is an account she can lose with the phone.
    ///
    /// Useless without the password, which is why this may be written to a file while the
    /// [`BridgeOutput::IdentityCreated`] client key may not.
    IdentityBackup {
        backup: String,
    },
    /// What was inside a backup string. The mirror of [`BridgeOutput::IdentityCreated`], and the
    /// same warning applies to `client_key`: it IS the account.
    ///
    /// `scheme` is which encryption the backup turned out to use, reported rather than assumed —
    /// old backups are the legacy PBKDF2 scheme and new ones are Argon2id, and being told which
    /// one arrived is the difference between "wrong password" and "backup this app cannot read".
    IdentityImported {
        identity: String,
        client_key: String,
        scheme: String,
    },
    TurnCredentials {
        turn_urls: Vec<String>,
        turn_urls_dual_stack: Vec<String>,
        turn_username: String,
        turn_password: String,
        expiration: u64,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    /// The outcome of a [`BridgeInput::MatchContacts`]. Always emitted, even when nothing matched
    /// and even on failure, so the caller can tell "nobody in her address book uses Threema" from
    /// "the lookup never completed" — which look identical from an empty list alone.
    ContactMatches {
        matches: Vec<ContactMatch>,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    },
    Log {
        level: String,
        message: String,
    },
    /// How a [`BridgeInput::SendGroupMessage`] fan-out went, member by member.
    ///
    /// Always emitted, once, after every member has been attempted — including when none of them
    /// could be. Per-member rather than a single boolean because the failure that matters here is
    /// the partial one: some members reached and some not is indistinguishable from complete
    /// success unless the caller is told who. What it decides to do about that is its business;
    /// this only has to be honest about it.
    ///
    /// `delivered` means accepted for sending, exactly as everywhere else in this bridge. It is
    /// not delivery, and the caller must not present it as delivery.
    GroupSendResult {
        creator: String,
        group_id: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        message_id: Option<String>,
        delivered: Vec<String>,
        failed: Vec<String>,
    },
    /// The outcome of sending a reaction, one-to-one or into a group.
    ///
    /// Counts only, never identities: for a reaction, who could not be reached is not something
    /// worth writing down. Emitted once per reaction input, including when nothing could be sent.
    ReactionResult {
        /// The message the reaction was about, so the app can tell two answers apart.
        ///
        /// Without it, two reactions in flight at once are two collectors waiting on one event,
        /// and each takes whichever arrives first — which is how she gets told a heart went that
        /// did not. Named exactly as it came in, so no normalising is needed to match it.
        target_message_id: String,
        delivered: usize,
        failed: usize,
    },
    HandshakeComplete,
}

/// The body of a group text message, as it goes on the wire.
///
/// `creator(8) || group_id(8) || text`, under message type [`GROUP_TEXT_MESSAGE_TYPE`]. Note that
/// the creator *is* repeated here, unlike in a group-setup or group-rename body where the creator
/// is the sender — that asymmetry is the whole reason `libthreema` misdecodes those two types, and
/// getting it backwards here would send every group message with eight bytes of the group id read
/// as somebody's identity.
///
/// The text is not length-prefixed: it runs to the end of the body, and the padding the caller adds
/// afterwards is stripped by the recipient using its own last byte.
fn group_text_body(creator: &str, group_id: &[u8; 8], text: &str) -> Vec<u8> {
    group_body(creator, group_id, text.as_bytes())
}

/// `creator(8) || group_id(8) || payload`: every group message type that carries the group in
/// its body, words and files alike.
fn group_body(creator: &str, group_id: &[u8; 8], payload: &[u8]) -> Vec<u8> {
    let mut body = Vec::with_capacity(8 + 8 + payload.len());
    body.extend_from_slice(creator.as_bytes());
    body.extend_from_slice(group_id);
    body.extend_from_slice(payload);
    body
}

/// What a [`BridgeInput::SendGroupMessage`] puts on the wire: its type byte and its body.
///
/// A file when `file_hex` is there, words otherwise. `None` for a file whose hex does not decode
/// or that is empty, and for words with nothing in them — sending either would put a message in
/// eight conversations that nobody can read.
fn group_message_payload(
    creator: &str,
    group_id: &[u8; 8],
    text: &str,
    file_hex: Option<&str>,
) -> Option<(u8, Vec<u8>)> {
    match file_hex {
        Some(hex) => {
            let file = data_encoding::HEXLOWER_PERMISSIVE.decode(hex.trim().as_bytes()).ok()?;
            if file.is_empty() {
                return None;
            }
            Some((GROUP_FILE_MESSAGE_TYPE as u8, group_body(creator, group_id, &file)))
        }
        None if !text.is_empty() => Some((GROUP_TEXT_MESSAGE_TYPE, group_text_body(creator, group_id, text))),
        None => None,
    }
}

/// The protobuf body of a modern emoji reaction, sent to one person.
///
/// Inverse of [`emit_protobuf_reaction`]: encodes the target message id as a 64-bit integer and
/// the emoji as UTF-8 bytes into a protobuf `Reaction`. Returns `None` when the target is not
/// sixteen hexadecimal digits, rather than creating a reaction that points at message zero.
fn reaction_body(target_message_id: &str, emoji: &str, withdraw: bool) -> Option<Vec<u8>> {
    use libthreema::protobuf::csp_e2e::{reaction::Action, Reaction};
    use prost::Message as _;

    let hex = target_message_id.trim();
    if hex.len() != 16 {
        return None;
    }
    let message_id = u64::from_str_radix(hex, 16).ok()?;
    let action = if withdraw {
        Action::Withdraw(emoji.as_bytes().to_vec())
    } else {
        Action::Apply(emoji.as_bytes().to_vec())
    };
    let reaction = Reaction {
        message_id,
        action: Some(action),
    };
    Some(reaction.encode_to_vec())
}

/// The body of a group reaction, as it goes on the wire.
///
/// `creator(8) || group_id(8) || <the protobuf Reaction>`, under message type
/// [`GROUP_REACTION_MESSAGE_TYPE`]. Note that the creator *is* repeated here, unlike in a
/// group-setup or group-rename body where the creator is the sender — that asymmetry is the whole
/// reason `libthreema` misdecodes those two types, and getting it backwards here would send every
/// group reaction with eight bytes of the group id read as somebody's identity.
fn group_reaction_body(
    creator: &str,
    group_id: &[u8; 8],
    target: &str,
    emoji: &str,
    withdraw: bool,
) -> Option<Vec<u8>> {
    let proto = reaction_body(target, emoji, withdraw)?;
    let mut body = Vec::with_capacity(8 + 8 + proto.len());
    body.extend_from_slice(creator.as_bytes());
    body.extend_from_slice(group_id);
    body.extend_from_slice(&proto);
    Some(body)
}

/// The eight group-id bytes from the hex the app holds, or `None` if it is not eight bytes of hex.
///
/// Strict on length: seven bytes silently zero-padded to eight would be a *different group*, and
/// one that exists — the message would go to the right people about the wrong conversation.
fn parse_group_id(hex: &str) -> Option<[u8; 8]> {
    let bytes = HEXLOWER.decode(hex.trim().to_lowercase().as_bytes()).ok()?;
    let bytes: [u8; 8] = bytes.try_into().ok()?;
    Some(bytes)
}

/// CSP `group-text`. A member's message to a group, as opposed to `0x4a`/`0x4b`/`0x4c`, which are
/// the creator's control messages.
const GROUP_TEXT_MESSAGE_TYPE: u8 = 0x41;

/// Which command a stdin line is, and nothing else about it.
///
/// For the log. Every field of every command is either an address, a message someone wrote, or
/// both, so the whole line is exactly what must not be written down.
/// The id to send a message under: the one the app chose, or a fresh random one.
///
/// Parsed from the same sixteen-character hexadecimal form the bridge *prints* ids in —
/// `{:016x}` of the number, see every `message_id` below — so that what the app stores and what
/// it later sees in a reaction or a receipt are the same string.
///
/// Not `MessageId(val.to_be())`, which it was from 2026-08-23 to 2026-09-18. That made the id
/// match libthreema's `Display` (the wire bytes in order) instead of how this bridge prints
/// ids, so every reaction to a message of hers came back byte-reversed and matched nothing —
/// among them the family's 👍 on a help request, which is how a Threema member says they are
/// coming. Quotes, which name the wire order, are matched in both orders by the app's
/// `ConversationBuilder` and do not need the flip.
fn outgoing_message_id(chosen: Option<&str>) -> libthreema::common::MessageId {
    chosen
        .map(str::trim)
        .filter(|hex| hex.len() == 16)
        .and_then(|hex| u64::from_str_radix(hex, 16).ok())
        .map_or_else(libthreema::common::MessageId::random, libthreema::common::MessageId)
}

/// CSP `delivery-receipt`, one-to-one.
const DELIVERY_RECEIPT_MESSAGE_TYPE: i32 = 0x80;

/// A receipt body as it goes on the wire: the receipt type, then each id's wire bytes.
fn delivery_receipt_hex(receipt_type: u8, ids: &[libthreema::common::MessageId]) -> String {
    let mut body = Vec::with_capacity(1 + ids.len() * 8);
    body.push(receipt_type);
    for id in ids {
        body.extend_from_slice(&id.to_bytes());
    }
    HEXLOWER.encode(&body)
}

/// Threema's modern reaction, sent to one person, and its group counterpart.
///
/// Both carry a protobuf `Reaction`: the id of the message being reacted to, and the emoji,
/// either applied or withdrawn. Older clients send [`LEGACY_REACTION`] instead, which libthreema
/// decodes for us into its own variant.
const REACTION_MESSAGE_TYPE: i32 = 0x82;
const GROUP_REACTION_MESSAGE_TYPE: i32 = 0x83;

/// Emits one reaction against the message it refers to.
fn emit_reaction(
    sender: &str,
    target_message_id: String,
    emoji: String,
    withdrawn: bool,
    legacy: bool,
    timestamp: u64,
    group_creator: Option<String>,
    group_id: Option<String>,
) {
    if emoji.is_empty() {
        return;
    }
    println!("BRIDGE-JSON: {}", serde_json::to_string(&BridgeOutput::Reaction {
        sender: sender.to_owned(),
        target_message_id,
        emoji,
        withdrawn,
        legacy,
        timestamp,
        group_creator,
        group_id,
    }).unwrap_or_default());
}

/// Reads a modern reaction out of a message body.
///
/// `skip` is 16 for a group message, as everywhere else: the group header comes first.
fn emit_protobuf_reaction(
    sender: &str,
    body: &[u8],
    skip: usize,
    timestamp: u64,
    group_creator: Option<String>,
    group_id: Option<String>,
) {
    use libthreema::protobuf::csp_e2e::{reaction::Action, Reaction};
    use prost::Message as _;

    let Some(payload) = body.get(skip..) else {
        return;
    };
    let Ok(reaction) = Reaction::decode(payload) else {
        return;
    };
    // Named the same way this bridge names every other message, so the app can match it to the
    // row it already has.
    let target = format!("{:016x}", reaction.message_id);
    let (emoji, withdrawn) = match reaction.action {
        Some(Action::Apply(bytes)) => (String::from_utf8_lossy(&bytes).into_owned(), false),
        Some(Action::Withdraw(bytes)) => (String::from_utf8_lossy(&bytes).into_owned(), true),
        None => return,
    };
    emit_reaction(sender, target, emoji, withdrawn, false, timestamp, group_creator, group_id);
}

/// The thumbs-up and thumbs-down older Threema clients send instead of an emoji reaction.
///
/// Turned into the emoji they mean, because her screen has no vocabulary for "acknowledge": a
/// reaction is a picture of a hand or it is nothing.
fn legacy_reaction_emoji(reaction_type: libthreema::model::message::LegacyReactionType) -> &'static str {
    use libthreema::model::message::LegacyReactionType;
    match reaction_type {
        LegacyReactionType::Acknowledge => "\u{1F44D}",
        LegacyReactionType::Decline => "\u{1F44E}",
    }
}

/// Threema's modern attachment type, sent to one person. Photographs, voice messages, videos and
/// documents all arrive as this; the JSON body says which.
const FILE_MESSAGE_TYPE: i32 = 0x17;

/// The same thing sent to a group, which is a **different type byte**.
///
/// This cost a real photograph. The first version of this code compared a group message against
/// [`FILE_MESSAGE_TYPE`], which a group file message never equals, so a photograph and a voice
/// message sent to a family group fell straight through to the branch that drops them — exactly
/// the silence this whole change exists to end. Both constants come from Threema's own
/// `common.proto`: `FILE = 0x17`, `GROUP_FILE = 0x46`.
const GROUP_FILE_MESSAGE_TYPE: i32 = 0x46;

/// A group message body begins with the creator's eight-character identity and an eight-byte
/// group id. The payload starts after them.
const GROUP_HEADER_LEN: usize = 16;

/// Puts a Threema attachment on her screen, and goes and fetches it.
///
/// Two things happen here and the order matters. The message is emitted immediately, carrying the
/// caption somebody wrote or a placeholder naming what arrived — so that being sent a photograph
/// produces a message from a person, right away. Before this it produced nothing whatsoever: a
/// Threema photograph fell into the `Unknown` branch, whose only consumer is the group-control
/// parser, and was dropped without trace.
///
/// Then the blob is fetched in the background. That takes seconds on a mobile connection, and
/// there is no reason for her to wait for it in order to know that her daughter wrote.
fn emit_file_message(
    sender: &str,
    body: &[u8],
    skip: usize,
    timestamp: u64,
    message_id: &str,
    group_creator: Option<String>,
    group_id: Option<String>,
) {
    let Some(file) = media::parse(body, skip) else {
        // Not a file message after all, or one that cannot be read. Silent on purpose: it is
        // indistinguishable to her from a message nobody sent.
        return;
    };

    println!("BRIDGE-JSON: {}", serde_json::to_string(&BridgeOutput::Message {
        sender: sender.to_owned(),
        text: file.placeholder(),
        timestamp,
        message_id: message_id.to_owned(),
        group_creator,
        group_id,
    }).unwrap_or_default());

    if !media::is_configured() {
        return;
    }

    let message_id = message_id.to_owned();
    tokio::spawn(async move {
        for (id, path, kind) in media::fetch(message_id, file).await {
            println!("BRIDGE-JSON: {}", serde_json::to_string(&BridgeOutput::Media {
                message_id: id,
                path,
                kind,
            }).unwrap_or_default());
        }
    });
}

#[cfg(test)]
mod file_message_type_tests {
    use super::{FILE_MESSAGE_TYPE, GROUP_FILE_MESSAGE_TYPE};

    /// Pins both bytes against Threema's own `common.proto`.
    ///
    /// Worth a test of its own because getting it wrong is invisible: the message does not fail,
    /// it is silently dropped, and the only symptom is somebody saying "I sent you a photograph"
    /// about a phone that never showed one. That is precisely what happened — a group photograph
    /// was compared against the one-to-one constant and vanished.
    #[test]
    fn a_group_attachment_is_not_the_same_byte_as_a_private_one() {
        assert_eq!(FILE_MESSAGE_TYPE, 0x17);
        assert_eq!(GROUP_FILE_MESSAGE_TYPE, 0x46);
        assert_ne!(FILE_MESSAGE_TYPE, GROUP_FILE_MESSAGE_TYPE);
    }
}

fn describe_flow_result(result: &anyhow::Result<()>) -> String {
    match result {
        Ok(()) => "without an error".to_string(),
        Err(error) => format!("{error:#}"),
    }
}

fn command_kind(line: &str) -> &'static str {
    match serde_json::from_str::<serde_json::Value>(line)
        .ok()
        .as_ref()
        .and_then(|value| value.get("type"))
        .and_then(serde_json::Value::as_str)
    {
        Some("SendMessage") => "SendMessage",
        Some("SendRaw") => "SendRaw",
        Some("UploadMedia") => "UploadMedia",
        Some("SendCall") => "SendCall",
        Some("SendGroupMessage") => "SendGroupMessage",
        Some("SendReaction") => "SendReaction",
        Some("SendGroupReaction") => "SendGroupReaction",
        Some("AddContacts") => "AddContacts",
        Some("MatchContacts") => "MatchContacts",
        Some("FetchTurnCredentials") => "FetchTurnCredentials",
        // Deliberately not echoing an unrecognised `type`: it is attacker- or bug-supplied text
        // being written to a log, and the parse error that follows says as much as this can.
        Some(_) => "unrecognised command",
        None => "unparseable line",
    }
}

#[cfg(test)]
mod stdin_line_tests {
    use super::{command_kind, BridgeInput};
    use tokio::io::{AsyncBufReadExt as _, BufReader};

    /// Two commands, where the first one's handler leaves early.
    ///
    /// That is the shape that ate a contact sync on her test phone (2026-09-17): `read_line`
    /// appends to the buffer it is given, the `AddContacts` arm `continue`s when every identity
    /// is already known, and the next command was therefore appended to the one still sitting
    /// there. Two JSON objects in one string parse as "trailing characters at line 2 column 1",
    /// and the command was dropped with nothing but that in the log.
    #[tokio::test]
    async fn a_command_following_one_that_left_early_still_arrives() {
        let input: &[u8] = b"{\"type\":\"AddContacts\",\"identities\":[\"AAAAAAAA\"]}\n\
                             {\"type\":\"MatchContacts\",\"phone_numbers\":[\"41791234567\"],\"emails\":[]}\n";
        let mut reader = BufReader::new(input);
        let mut buffer = String::new();
        let mut kinds: Vec<&'static str> = Vec::new();
        let mut parsed: Vec<bool> = Vec::new();

        while let Ok(len) = reader.read_line(&mut buffer).await {
            if len == 0 {
                break;
            }
            // The one line this test is about: taken before any of the handlers can skip it.
            let line = std::mem::take(&mut buffer);
            let trimmed = line.trim();
            if trimmed.is_empty() {
                continue;
            }
            kinds.push(command_kind(trimmed));
            parsed.push(serde_json::from_str::<BridgeInput>(&line).is_ok());
            if kinds.len() == 1 {
                // As `AddContacts` does when it has nothing to look up.
                continue;
            }
        }

        assert_eq!(kinds, vec!["AddContacts", "MatchContacts"]);
        assert_eq!(parsed, vec![true, true]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn group_text_body_is_creator_then_group_then_text() {
        let body = group_text_body("TSTMBR01", &[0x52, 0x0c, 0x41, 0x40, 0x19, 0xfa, 0xd3, 0x1b], "Hallo");
        assert_eq!(&body[0..8], b"TSTMBR01");
        assert_eq!(&body[8..16], &[0x52, 0x0c, 0x41, 0x40, 0x19, 0xfa, 0xd3, 0x1b]);
        assert_eq!(&body[16..], b"Hallo");
    }

    #[test]
    fn group_text_body_keeps_the_text_as_written() {
        // Not ASCII, not length-prefixed, and not something to be trimmed: what she typed is what
        // the others have to read.
        let body = group_text_body("TSTMBR01", &[0; 8], "Grüezi mitenand 🌷");
        assert_eq!(&body[16..], "Grüezi mitenand 🌷".as_bytes());
    }

    #[test]
    fn a_group_photo_goes_out_as_a_group_file_message() {
        let gid = [0x52, 0x0c, 0x41, 0x40, 0x19, 0xfa, 0xd3, 0x1b];
        let json = br#"{"b":"00","m":"image/jpeg"}"#;
        let (message_type, body) =
            group_message_payload("TSTMBR01", &gid, "", Some(&HEXLOWER.encode(json))).unwrap();
        assert_eq!(message_type, 0x46);
        assert_eq!(&body[0..8], b"TSTMBR01");
        assert_eq!(&body[8..16], &gid);
        assert_eq!(&body[16..], json);
    }

    #[test]
    fn words_to_a_group_are_still_a_group_text_message() {
        let (message_type, body) = group_message_payload("TSTMBR01", &[0; 8], "Hallo", None).unwrap();
        assert_eq!(message_type, 0x41);
        assert_eq!(body, group_text_body("TSTMBR01", &[0; 8], "Hallo"));
    }

    #[test]
    fn a_group_send_with_nothing_readable_in_it_is_refused() {
        assert_eq!(group_message_payload("TSTMBR01", &[0; 8], "", None), None);
        assert_eq!(group_message_payload("TSTMBR01", &[0; 8], "", Some("")), None);
        assert_eq!(group_message_payload("TSTMBR01", &[0; 8], "", Some("not hex")), None);
    }

    #[test]
    fn a_group_photo_command_parses_without_text() {
        let line = r#"{"type":"SendGroupMessage","creator":"TSTMBR01","group_id":"520c414019fad31b","members":["ECHOECHO"],"file_hex":"7b7d"}"#;
        match serde_json::from_str::<BridgeInput>(line) {
            Ok(BridgeInput::SendGroupMessage { text, file_hex, .. }) => {
                assert_eq!(text, "");
                assert_eq!(file_hex.as_deref(), Some("7b7d"));
            }
            other => panic!("parsed as {:?}", other),
        }
    }

    #[test]
    fn group_send_result_serializes_with_message_id() {
        let res = BridgeOutput::GroupSendResult {
            creator: "TSTMBR01".to_string(),
            group_id: "520c414019fad31b".to_string(),
            message_id: Some("0123456789abcdef".to_string()),
            delivered: vec!["ECHOECHO".to_string()],
            failed: vec![],
        };
        let json = serde_json::to_string(&res).unwrap();
        assert!(json.contains(r#""message_id":"0123456789abcdef""#));
    }

    #[test]
    fn group_id_round_trips_from_the_hex_the_app_holds() {
        assert_eq!(
            parse_group_id("520c414019fad31b"),
            Some([0x52, 0x0c, 0x41, 0x40, 0x19, 0xfa, 0xd3, 0x1b])
        );
        assert_eq!(parse_group_id("520C414019FAD31B"), parse_group_id("520c414019fad31b"));
    }

    #[test]
    fn a_group_id_that_is_not_eight_bytes_is_refused() {
        // Padding a short one to eight bytes would name a different group that may well exist.
        assert_eq!(parse_group_id("520c4140"), None);
        assert_eq!(parse_group_id("520c414019fad31b00"), None);
        assert_eq!(parse_group_id("nonsense"), None);
        assert_eq!(parse_group_id(""), None);
    }

    #[test]
    fn reaction_body_decodes_back_to_emoji_and_target() {
        use libthreema::protobuf::csp_e2e::{reaction::Action, Reaction};
        use prost::Message as _;

        let target = "0123456789abcdef";
        let emoji = "❤️";
        let body = reaction_body(target, emoji, false).expect("reaction_body should succeed");
        let decoded = Reaction::decode(body.as_slice()).expect("decode should succeed");
        assert_eq!(format!("{:016x}", decoded.message_id), target);
        match decoded.action {
            Some(Action::Apply(bytes)) => assert_eq!(String::from_utf8_lossy(&bytes), emoji),
            _ => panic!("expected Action::Apply"),
        }
    }

    #[test]
    fn reaction_message_id_survives_hex_u64_format() {
        use libthreema::protobuf::csp_e2e::Reaction;
        use prost::Message as _;

        let hex = "a1b2c3d4e5f60718";
        let body = reaction_body(hex, "👍", false).expect("reaction_body should succeed");
        let decoded = Reaction::decode(body.as_slice()).expect("decode should succeed");
        assert_eq!(format!("{:016x}", decoded.message_id), hex);
    }

    #[test]
    fn reaction_target_that_is_not_sixteen_hex_chars_yields_none() {
        assert_eq!(reaction_body("12345", "👍", false), None);
        assert_eq!(reaction_body("0123456789abcdef00", "👍", false), None);
        assert_eq!(reaction_body("not_hex_chars!!", "👍", false), None);
        assert_eq!(reaction_body("", "👍", false), None);
    }

    #[test]
    fn group_reaction_body_is_creator_group_then_protobuf_at_group_header_len() {
        use libthreema::protobuf::csp_e2e::{reaction::Action, Reaction};
        use prost::Message as _;

        let creator = "TSTMBR01";
        let group_id = [0x52, 0x0c, 0x41, 0x40, 0x19, 0xfa, 0xd3, 0x1b];
        let target = "0123456789abcdef";
        let emoji = "🎉";
        let body = group_reaction_body(creator, &group_id, target, emoji, false)
            .expect("group_reaction_body should succeed");

        assert_eq!(&body[0..8], creator.as_bytes());
        assert_eq!(&body[8..16], &group_id);
        assert_eq!(GROUP_HEADER_LEN, 16);

        let decoded = Reaction::decode(&body[GROUP_HEADER_LEN..]).expect("protobuf decode should succeed");
        assert_eq!(format!("{:016x}", decoded.message_id), target);
        match decoded.action {
            Some(Action::Apply(bytes)) => assert_eq!(String::from_utf8_lossy(&bytes), emoji),
            _ => panic!("expected Action::Apply"),
        }
    }

    #[test]
    fn reaction_withdrawal_encodes_as_withdraw_action() {
        use libthreema::protobuf::csp_e2e::{reaction::Action, Reaction};
        use prost::Message as _;

        let target = "0123456789abcdef";
        let emoji = "❤️";
        let body = reaction_body(target, emoji, true).expect("reaction_body should succeed");
        let decoded = Reaction::decode(body.as_slice()).expect("decode should succeed");
        assert_eq!(format!("{:016x}", decoded.message_id), target);
        match decoded.action {
            Some(Action::Withdraw(bytes)) => assert_eq!(String::from_utf8_lossy(&bytes), emoji),
            Some(Action::Apply(_)) => panic!("expected Action::Withdraw, got Action::Apply"),
            None => panic!("expected Action::Withdraw, got None"),
        }
    }
}

// HMAC-SHA256 keys for contact matching, from the Threema Android app
// (`domain/.../api/APIConnector.java`). Fixed protocol constants, not secrets.
const EMAIL_HMAC_KEY: [u8; 32] = [
    0x30, 0xa5, 0x50, 0x0f, 0xed, 0x97, 0x01, 0xfa, 0x6d, 0xef, 0xdb, 0x61, 0x08, 0x41, 0x90, 0x0f,
    0xeb, 0xb8, 0xe4, 0x30, 0x88, 0x1f, 0x7a, 0xd8, 0x16, 0x82, 0x62, 0x64, 0xec, 0x09, 0xba, 0xd7,
];
const MOBILENO_HMAC_KEY: [u8; 32] = [
    0x85, 0xad, 0xf8, 0x22, 0x69, 0x53, 0xf3, 0xd9, 0x6c, 0xfd, 0x5d, 0x09, 0xbf, 0x29, 0x55, 0x5e,
    0xb9, 0x55, 0xfc, 0xd8, 0xaa, 0x5e, 0xc4, 0xf9, 0xfc, 0xd8, 0x69, 0xe2, 0x58, 0x37, 0x07, 0x23,
];

/// Base64 HMAC-SHA256 of `value` under `key`, which is the form the match endpoint expects.
fn contact_hash(key: &[u8; 32], value: &str) -> String {
    use hmac::{Hmac, Mac as _};
    let mut mac = <Hmac<sha2::Sha256>>::new_from_slice(key).expect("HMAC accepts any key length");
    mac.update(value.as_bytes());
    data_encoding::BASE64.encode(&mac.finalize().into_bytes())
}

/// Puts a base64 hash into one shape for use as a map key.
///
/// The server echoes back the hash that matched, and the address it stands for is recovered by
/// looking that string up. Padding or a URL-safe alphabet would make an identical hash miss,
/// which loses the address silently — the match is still counted, but nothing can be done with
/// it because nobody knows which contact it belongs to.
fn hash_key(encoded: &str) -> String {
    encoded.trim_end_matches('=').replace('-', "+").replace('_', "/")
}

struct LoggingConversationProvider {
    inner: libthreema::model::provider::in_memory::InMemoryDbMessageProvider,
}
impl libthreema::model::provider::ConversationProvider for LoggingConversationProvider {
    fn message_is_marked_used(
        &self,
        sender_identity: libthreema::common::ThreemaId,
        id: libthreema::common::MessageId,
    ) -> Result<bool, libthreema::model::provider::ProviderError> {
        self.inner.message_is_marked_used(sender_identity, id)
    }

    fn set_typing_indicator(
        &mut self,
        sender_identity: libthreema::common::ThreemaId,
        is_typing: bool,
    ) -> Result<(), libthreema::model::provider::ProviderError> {
        self.inner.set_typing_indicator(sender_identity, is_typing)
    }

    fn add_or_update_incoming_message(&mut self, message: libthreema::model::message::IncomingMessage) -> Result<(), libthreema::model::provider::ProviderError> {
        use libthreema::model::message::{IncomingMessageBody, ContactMessageBody, GroupMessageBody};
        match &message.body {
            IncomingMessageBody::Contact(contact_body) => {
                match contact_body {
                    ContactMessageBody::Text(text_msg) => {
                        println!("BRIDGE-JSON: {}", serde_json::to_string(&BridgeOutput::Message {
                            sender: message.sender_identity.to_string(),
                            text: text_msg.text.clone(),
                            timestamp: message.created_at,
                            message_id: format!("{:016x}", message.id.0),
                            group_creator: None,
                            group_id: None,
                        }).unwrap_or_default());
                    },
                    ContactMessageBody::LegacyReaction(reaction) => {
                        for target in &reaction.message_ids {
                            emit_reaction(
                                &message.sender_identity.to_string(),
                                format!("{:016x}", target.0),
                                legacy_reaction_emoji(reaction.reaction_type).to_owned(),
                                false,
                                true,
                                message.created_at,
                                None,
                                None,
                            );
                        }
                    },
                    ContactMessageBody::Unknown { r#type, data } => {
                        let msg_type = *r#type as i32;
                        if msg_type == REACTION_MESSAGE_TYPE {
                            emit_protobuf_reaction(
                                &message.sender_identity.to_string(),
                                data,
                                0,
                                message.created_at,
                                None,
                                None,
                            );
                        } else if msg_type == FILE_MESSAGE_TYPE {
                            emit_file_message(
                                &message.sender_identity.to_string(),
                                data,
                                0,
                                message.created_at,
                                &format!("{:016x}", message.id.0),
                                None,
                                None,
                            );
                        } else if (0x60..=0x64).contains(&msg_type) {
                            println!("BRIDGE-JSON: {}", serde_json::to_string(&BridgeOutput::Call {
                                sender: message.sender_identity.to_string(),
                                message_type: msg_type as u8,
                                data_hex: HEXLOWER.encode(data),
                                timestamp: message.created_at,
                                message_id: format!("{:016x}", message.id.0),
                            }).unwrap_or_default());
                        } else {
                            println!("BRIDGE-JSON: {}", serde_json::to_string(&BridgeOutput::Unknown {
                                sender: message.sender_identity.to_string(),
                                message_type: msg_type,
                                data_hex: HEXLOWER.encode(data),
                                timestamp: message.created_at,
                                message_id: format!("{:016x}", message.id.0),
                                group_creator: None,
                                group_id: None,
                            }).unwrap_or_default());
                        }
                    },
                    // libthreema decodes receipts itself, so they never reached the `Unknown` arm
                    // the app listens on and a Threema member never counted as having had the
                    // help request: the SMS backup went to them a minute later every time.
                    // Passed on as the raw body the app already parses — see
                    // `SupportManager.onThreemaReceipt`.
                    ContactMessageBody::DeliveryReceipt(receipt) => {
                        println!("BRIDGE-JSON: {}", serde_json::to_string(&BridgeOutput::Unknown {
                            sender: message.sender_identity.to_string(),
                            message_type: DELIVERY_RECEIPT_MESSAGE_TYPE,
                            data_hex: delivery_receipt_hex(receipt.receipt_type as u8, &receipt.message_ids),
                            timestamp: message.created_at,
                            message_id: format!("{:016x}", message.id.0),
                            group_creator: None,
                            group_id: None,
                        }).unwrap_or_default());
                    },
                    _ => {
                        println!("BRIDGE-JSON: {}", serde_json::to_string(&BridgeOutput::Log { level: "debug".to_string(), message: format!("Provider: other contact message type: {:?}", contact_body.message_type()) }).unwrap_or_default());
                    }
                }
            },
            IncomingMessageBody::Group(group_msg) => {
                match &group_msg.body {
                    GroupMessageBody::Text(text_msg) => {
                        println!("BRIDGE-JSON: {}", serde_json::to_string(&BridgeOutput::Message {
                            sender: message.sender_identity.to_string(),
                            text: text_msg.text.clone(),
                            timestamp: message.created_at,
                            message_id: format!("{:016x}", message.id.0),
                            group_creator: Some(group_msg.group_identity.creator_identity.to_string()),
                            group_id: Some(HEXLOWER.encode(&group_msg.group_identity.group_id.to_le_bytes())),
                        }).unwrap_or_default());
                    },
                    GroupMessageBody::LegacyReaction(reaction) => {
                        for target in &reaction.message_ids {
                            emit_reaction(
                                &message.sender_identity.to_string(),
                                format!("{:016x}", target.0),
                                legacy_reaction_emoji(reaction.reaction_type).to_owned(),
                                false,
                                true,
                                message.created_at,
                                Some(group_msg.group_identity.creator_identity.to_string()),
                                Some(HEXLOWER.encode(&group_msg.group_identity.group_id.to_le_bytes())),
                            );
                        }
                    },
                    GroupMessageBody::Unknown { r#type, data } if *r#type as i32 == GROUP_REACTION_MESSAGE_TYPE => {
                        emit_protobuf_reaction(
                            &message.sender_identity.to_string(),
                            data,
                            GROUP_HEADER_LEN,
                            message.created_at,
                            Some(group_msg.group_identity.creator_identity.to_string()),
                            Some(HEXLOWER.encode(&group_msg.group_identity.group_id.to_le_bytes())),
                        );
                    },
                    GroupMessageBody::Unknown { r#type, data } if *r#type as i32 == GROUP_FILE_MESSAGE_TYPE => {
                        // `data` carries the group header first, so the JSON starts after it.
                        emit_file_message(
                            &message.sender_identity.to_string(),
                            data,
                            GROUP_HEADER_LEN,
                            message.created_at,
                            &format!("{:016x}", message.id.0),
                            Some(group_msg.group_identity.creator_identity.to_string()),
                            Some(HEXLOWER.encode(&group_msg.group_identity.group_id.to_le_bytes())),
                        );
                    },
                    GroupMessageBody::Unknown { r#type, data } => {
                        // Note: `data` still contains the full body including the
                        // 16-byte group header (creator + group ID).
                        println!("BRIDGE-JSON: {}", serde_json::to_string(&BridgeOutput::Unknown {
                            sender: message.sender_identity.to_string(),
                            message_type: *r#type as i32,
                            data_hex: HEXLOWER.encode(data),
                            timestamp: message.created_at,
                            message_id: format!("{:016x}", message.id.0),
                            group_creator: Some(group_msg.group_identity.creator_identity.to_string()),
                            group_id: Some(HEXLOWER.encode(&group_msg.group_identity.group_id.to_le_bytes())),
                        }).unwrap_or_default());
                    },
                    _ => {
                        println!("BRIDGE-JSON: {}", serde_json::to_string(&BridgeOutput::Log { level: "debug".to_string(), message: format!("Provider: other group message type: {:?}", group_msg.body.message_type()) }).unwrap_or_default());
                    }
                }
            }
        }
        self.inner.add_or_update_incoming_message(message)
    }
}


#[derive(Parser)]
#[command()]
struct CspE2eReceiveCommand {
    #[command(flatten)]
    config: FullIdentityConfigOptions,

    /// User nickname
    #[arg(long, default_value = "gentle connection")]
    nickname: String,

    /// Where to write photographs and voice messages. App-private, never shared storage.
    ///
    /// Optional: without it a file message still becomes a visible message, it simply keeps its
    /// placeholder instead of gaining a picture or a play control.
    #[arg(long)]
    media_dir: Option<std::path::PathBuf>,
}

enum IncomingPayloadForCspE2e {
    Message(MessageWithMetadataBox),
    MessageAck(MessageAck),
}

enum OutgoingPayloadForCspE2e {
    UnblockIncomingMessages,
    MessageAck(MessageAck),
    MessageWithMetadataBox(MessageWithMetadataBox),
}
impl From<OutgoingPayloadForCspE2e> for OutgoingPayload {
    fn from(payload: OutgoingPayloadForCspE2e) -> Self {
        match payload {
            OutgoingPayloadForCspE2e::MessageAck(message_ack) => OutgoingPayload::MessageAck(message_ack),
            OutgoingPayloadForCspE2e::UnblockIncomingMessages => OutgoingPayload::UnblockIncomingMessages,
            OutgoingPayloadForCspE2e::MessageWithMetadataBox(msg) => OutgoingPayload::MessageWithMetadataBox(msg),
        }
    }
}


/// Payload queues for the main process
struct PayloadQueuesForCspE2e {
    incoming: mpsc::UnboundedReceiver<IncomingPayloadForCspE2e>,
    outgoing: mpsc::UnboundedSender<OutgoingPayloadForCspE2e>,
}

/// Payload queues for the protocol flow runner
struct PayloadQueuesForCsp {
    incoming: mpsc::UnboundedSender<IncomingPayloadForCspE2e>,
    outgoing: mpsc::UnboundedReceiver<OutgoingPayloadForCspE2e>,
}

struct CspProtocolRunner {
    /// The TCP stream
    stream: TcpStream,

    /// An instance of the [`CspProtocol`] state machine
    protocol: CspProtocol,
}
/// How often the bridge asks the chat server whether the connection is still there. The server
/// never asks on its own, so without this a connection that died quietly (a NAT that forgot it, a
/// network that went away under the socket) is only noticed when she tries to send something.
const ECHO_INTERVAL: Duration = Duration::from_secs(180);

/// Connecting and logging in either finish in seconds or never will. Without a limit a server
/// that accepts the TCP connection and then says nothing holds the bridge forever.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);

impl CspProtocolRunner {
    /// Initiate a CSP protocol connection and hand out the initial `client_hello` message
    #[tracing::instrument(skip_all)]
    async fn new(
        server_address: Vec<(String, u16)>,
        context: CspProtocolContext,
    ) -> anyhow::Result<(Self, OutgoingFrame)> {
        // Connect via TCP
        debug!(?server_address, "Establishing TCP connection to chat server",);
        let tcp_stream = time::timeout(
            CONNECT_TIMEOUT,
            TcpStream::connect(
                server_address
                    .first()
                    .expect("CSP config should have at least one address"),
            ),
        )
        .await
        .map_err(|_| anyhow::anyhow!("no TCP connection to the chat server within {CONNECT_TIMEOUT:?}"))??;

        // Create the protocol
        let (csp_protocol, client_hello) = CspProtocol::new(context);
        Ok((
            Self {
                stream: tcp_stream,
                protocol: csp_protocol,
            },
            client_hello,
        ))
    }

    /// Do the handshake with the chat server by exchanging the following messages:
    ///
    /// ```txt
    /// C -- client-hello -> S
    /// C <- server-hello -- S
    /// C ---- login ---- -> S
    /// C <-- login-ack ---- S
    /// ```
    #[tracing::instrument(skip_all)]
    async fn run_handshake_flow(&mut self, client_hello: OutgoingFrame) -> anyhow::Result<()> {
        // Send the client hello
        debug!(length = client_hello.0.len(), "Sending client hello");
        self.send(&client_hello.0).await?;

        // Handshake by polling the CSP state
        for iteration in 1_usize.. {
            trace!("Iteration #{iteration}");

            // Receive required bytes and add them
            let bytes = self.receive_required().await?;
            self.protocol.add_chunks(&[&bytes])?;

            // Handle instruction
            let Some(instruction) = self.protocol.poll()? else {
                continue;
            };

            // We do not expect an incoming payload at this stage
            if let Some(incoming_payload) = instruction.incoming_payload {
                let message = "Unexpected incoming payload during handshake";
                error!(?incoming_payload, message);
                bail!(message)
            }

            // Send any outgoing frame
            if let Some(frame) = instruction.outgoing_frame {
                self.send(&frame.0).await?;
            }

            // Check if we've completed the handshake
            if let Some(CspStateUpdate::PostHandshake(login_ack_data)) = instruction.state_update {
                info!(?login_ack_data, "Handshake complete");
println!("{}", serde_json::to_string(&BridgeOutput::HandshakeComplete).unwrap());
                break;
            }
        }

        Ok(())
    }

    /// Run the payload exchange flow until stopped.
    #[tracing::instrument(skip_all)]
    async fn run_payload_flow(&mut self, mut queues: PayloadQueuesForCsp) -> anyhow::Result<()> {
        let mut read_buffer = [0_u8; 8192];
        let mut next_instruction: Option<CspProtocolInstruction> = None;

        // One echo request in flight at a time. If it is still unanswered when the next one is
        // due, the server has had a whole interval to reply and the connection is gone.
        let mut echo_timer = time::interval_at(Instant::now() + ECHO_INTERVAL, ECHO_INTERVAL);
        echo_timer.set_missed_tick_behavior(MissedTickBehavior::Delay);
        let mut echo_outstanding = false;

        for iteration in 1_usize.. {
            trace!("Iteration #{iteration}");

            // Poll for an instruction, if necessary
            if next_instruction.is_none() {
                next_instruction = self.protocol.poll()?;
            }

            // Wait for more input, if necessary
            if next_instruction.is_none() {
                next_instruction = tokio::select! {
                    // Incoming bytes first: after the phone wakes, a reply already waiting in the
                    // socket must be read before the echo timer can call the connection dead.
                    biased;

                    // Forward any incoming chunks from the TCP stream
                    _ = self.stream.readable() => {
                        let length = self.try_receive(&mut read_buffer)?;

                        // Add chunks (poll in the next iteration)
                        self.protocol
                            .add_chunks(&[read_buffer.get(..length)
                            .expect("Amount of read bytes should be available")])?;
                        None
                    },

                    // Forward any outgoing payloads
                    outgoing_payload = queues.outgoing.recv() => {
                        if let Some(outgoing_payload) = outgoing_payload {
                            let outgoing_payload = OutgoingPayload::from(outgoing_payload);
                            debug!(?outgoing_payload, "Sending payload");
                            Some(self.protocol.create_payload(&outgoing_payload)?)
                        } else {
                            break
                        }
                    },

                    _ = echo_timer.tick() => {
                        if echo_outstanding {
                            bail!("chat server did not answer an echo request within {ECHO_INTERVAL:?}");
                        }
                        echo_outstanding = true;
                        trace!("Sending echo request");
                        Some(self.protocol.create_payload(&OutgoingPayload::EchoRequest(
                            EchoPayload(b"gentle".to_vec()),
                        ))?)
                    }
                };
            }

            // Handle instruction
            let Some(current_instruction) = next_instruction.take() else {
                continue;
            };

            // We do not expect any state updates at this stage
            if let Some(state_update) = current_instruction.state_update {
                let message = "Unexpected state update after handshake";
                error!(?state_update, message);
                bail!(message)
            }

            // Handle any incoming payload
            if let Some(incoming_payload) = current_instruction.incoming_payload {
                debug!(?incoming_payload, "Received payload");
                println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "debug".to_string(), message: format!("RAW PAYLOAD: {:?}", incoming_payload) }).unwrap_or_default());
                match incoming_payload {
                    IncomingPayload::EchoRequest(echo_payload) => {
                        // Respond to echo request
                        next_instruction = Some(
                            self.protocol
                                .create_payload(&OutgoingPayload::EchoResponse(echo_payload))?,
                        );
                    },
                    IncomingPayload::MessageWithMetadataBox(payload) => {
                        // Forward message
                        queues
                            .incoming
                            .send(IncomingPayloadForCspE2e::Message(payload))
                            .map_err(|e| anyhow::anyhow!("incoming channel send error: {}", e))?;
                    },
                    IncomingPayload::MessageAck(payload) => {
                        // Forward message ack
                        queues
                            .incoming
                            .send(IncomingPayloadForCspE2e::MessageAck(payload))
                            .map_err(|e| anyhow::anyhow!("incoming channel send error: {}", e))?;
                    },

                    IncomingPayload::EchoResponse(_) => {
                        echo_outstanding = false;
                    },
                    IncomingPayload::QueueSendComplete
                    | IncomingPayload::DeviceCookieChangeIndication
                    | IncomingPayload::CloseError(_)
                    | IncomingPayload::ServerAlert(_)
                    | IncomingPayload::UnknownPayload { .. } => {},
                }
            }

            // Send any outgoing frame
            if let Some(frame) = current_instruction.outgoing_frame {
                self.send(&frame.0).await?;
            }
        }

        Ok(())
    }

    /// Shut down the TCP connection
    #[tracing::instrument(skip_all)]
    async fn shutdown(&mut self) -> anyhow::Result<()> {
        info!("Shutting down TCP connection");
        Ok(self.stream.shutdown().await?)
    }

    /// Send bytes to the server over the TCP connection
    #[tracing::instrument(skip_all, fields(bytes_length = bytes.len()))]
    async fn send(&mut self, bytes: &[u8]) -> anyhow::Result<()> {
        trace!(length = bytes.len(), "Sending bytes");
        self.stream.write_all(bytes).await?;

        Ok(())
    }

    #[tracing::instrument(skip_all)]
    async fn receive_required(&mut self) -> anyhow::Result<Vec<u8>> {
        // Get the minimum amount of bytes we'll need to receive
        let length = self.protocol.next_required_length()?;
        let mut buffer = vec![0; length];
        trace!(?length, "Reading bytes");

        // If there is nothing to read, return immediately
        if length == 0 {
            return Ok(buffer);
        }

        // Read the exact number of bytes required
        let _ = self.stream.read_exact(&mut buffer).await?;

        // Read more if available
        match self.stream.try_read_buf(&mut buffer) {
            Ok(0) => {
                // Remote shut down our reading end gracefully.
                //
                // IMPORTANT: An implementation needs to ensure that it stops gracefully by processing any
                // remaining payloads prior to stopping the protocol. This example implementation ensures this
                // by handling all pending instructions prior to polling for more data. The only case we bail
                // is therefore when our instruction queue is already dry.
                bail!("TCP reading end closed")
            },
            Ok(length) => {
                trace!(length, "Got additional bytes");
            },
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                trace!("No additional bytes available");
            },
            Err(error) => {
                return Err(error.into());
            },
        }
        debug!(length = buffer.len(), "Received bytes");

        Ok(buffer)
    }

    #[tracing::instrument(skip_all)]
    fn try_receive(&mut self, buffer: &mut [u8]) -> anyhow::Result<usize> {
        match self.stream.try_read(buffer) {
            Ok(0) => {
                // Remote shut down our reading end.
                let message = "TCP reading end closed";
                warn!(message);
                bail!(message);
            },
            Ok(length) => {
                debug!(length, "Received bytes");
                Ok(length)
            },
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                trace!("No bytes to receive");
                Ok(0)
            },
            Err(error) => Err(error.into()),
        }
    }
}

struct CspE2eProtocolRunner {
    /// An instance of the [`CspE2eProtocol`] state machine
    protocol: CspE2eProtocol,

    /// HTTP client
    http_client: reqwest::Client,
}
impl CspE2eProtocolRunner {
    #[tracing::instrument(skip_all)]
    fn new(http_client: reqwest::Client, context: CspE2eProtocolContextInit) -> anyhow::Result<Self> {
        Ok(Self {
            protocol: CspE2eProtocol::new(context),
            http_client,
        })
    }

    /// Run the receive flow until stopped.
    #[tracing::instrument(skip_all)]
    async fn run_receive_flow(&mut self, mut queues: PayloadQueuesForCspE2e) -> anyhow::Result<()> {
        let mut pending_task: Option<IncomingMessageTask> = None;

        for iteration in 1_usize.. {
            trace!("Receive flow iteration #{iteration}");

            // Handle any incoming payloads until we have a task
            let Some(task) = &mut pending_task else {
                match queues.incoming.recv().await {
                    Some(IncomingPayloadForCspE2e::Message(message)) => {
                        trace!(message = HEXLOWER.encode(&message.bytes), "Raw incoming message");
                        info!(?message, "Incoming message");
                        pending_task = Some(self.protocol.handle_incoming_message(message));
                    },
                    Some(IncomingPayloadForCspE2e::MessageAck(message_ack)) => {
                        warn!(?message_ack, "Unexpected message-ack");
                    },
                    None => {},
                }
                continue;
            };

            // Handle task
            match task.poll(self.protocol.context())? {
                IncomingMessageLoop::Instruction(IncomingMessageInstruction::FetchSender(instruction)) => {
                    // Run both requests simultaneously
                    let work_directory_request_future = async {
                        match instruction.work_directory_request {
                            Some(work_directory_request) => {
                                work_directory_request.send(&self.http_client).await.map(Some)
                            },
                            None => Ok(None),
                        }
                    };
                    let (directory_result, work_directory_result) = tokio::join!(
                        instruction.directory_request.send(&self.http_client),
                        work_directory_request_future,
                    );

                    // Forward response
                    task.response(IncomingMessageResponse::FetchSender(ContactsLookupResponse {
                        directory_result,
                        work_directory_result: work_directory_result.transpose(),
                    }))?;
                },
                IncomingMessageLoop::Instruction(IncomingMessageInstruction::CreateContact(instruction)) => {
                    match instruction {
                        CreateContactsInstruction::BeginTransaction(instruction) => {
                            // Begin transaction and forward response, if any
                            let response = self.begin_transaction(instruction).await?;
                            if let Some(response) = response {
                                task.response(IncomingMessageResponse::CreateContact(
                                    CreateContactsResponse::BeginTransactionResponse(response),
                                ))?;
                            }
                        },
                        CreateContactsInstruction::ReflectAndCommitTransaction(instruction) => {
                            // Reflect and commit transaction and forward response
                            task.response(IncomingMessageResponse::CreateContact(
                                CreateContactsResponse::CommitTransactionResponse(
                                    self.reflect_and_commit_transaction(instruction).await?,
                                ),
                            ))?;
                        },
                    }
                },
                IncomingMessageLoop::Instruction(IncomingMessageInstruction::UpdateContact(instruction)) => {
                    match instruction {
                        UpdateContactsInstruction::BeginTransaction(instruction) => {
                            // Begin transaction and forward response, if any
                            let response = self.begin_transaction(instruction).await?;
                            if let Some(response) = response {
                                task.response(IncomingMessageResponse::UpdateContact(
                                    UpdateContactsResponse::BeginTransactionResponse(response),
                                ))?;
                            }
                        },
                        UpdateContactsInstruction::ReflectAndCommitTransaction(instruction) => {
                            // Reflect and commit transaction and forward response
                            task.response(IncomingMessageResponse::UpdateContact(
                                UpdateContactsResponse::CommitTransactionResponse(
                                    self.reflect_and_commit_transaction(instruction).await?,
                                ),
                            ))?;
                        },
                    }
                },
                IncomingMessageLoop::Instruction(IncomingMessageInstruction::ReflectMessage(instruction)) => {
                    task.response(IncomingMessageResponse::ReflectMessage(
                        self.reflect(instruction).await?,
                    ))?;
                },

                IncomingMessageLoop::Done(result) => {
                    // Send message acknowledgement, if any
                    if let Some(outgoing_message_ack) = result.outgoing_message_ack {
                        queues
                            .outgoing
                            .send(OutgoingPayloadForCspE2e::MessageAck(outgoing_message_ack))
                            .map_err(|e| anyhow::anyhow!("outgoing channel send error: {}", e))?;
                    }
                    pending_task = None;
                },
            }
        }

        Ok(())
    }

    #[tracing::instrument(skip_all)]
    async fn begin_transaction(
        &self,
        instruction: BeginTransactionInstruction,
    ) -> anyhow::Result<Option<BeginTransactionResponse>> {
        match instruction {
            BeginTransactionInstruction::TransactionRejected => {
                // TODO(LIB-16). Await TransactionEnded
                Ok(None)
            },
            BeginTransactionInstruction::BeginTransaction { message } => {
                // TODO(LIB-16). Send `BeginTransaction, await BeginTransactionAck or TransactionRejected,
                // then return BeginTransactionResponse(message)
                Ok(Some(BeginTransactionResponse::BeginTransactionReply(todo!())))
            },
            BeginTransactionInstruction::AbortTransaction { message } => {
                // TODO(LIB-16). Send `CommitTransaction`, await CommitTransactionAck, then return
                // AbortTransaction(CommitTransactionAck)
                Ok(Some(BeginTransactionResponse::AbortTransactionResponse(todo!())))
            },
        }
    }

    #[tracing::instrument(skip_all)]
    async fn reflect_and_commit_transaction(
        &self,
        instruction: CommitTransactionInstruction,
    ) -> anyhow::Result<CommitTransactionResponse> {
        // TODO(LIB-16). Reflect messages, then immediately commit. Await CommitAck and gather any
        // reflect-acks
        Ok(CommitTransactionResponse {
            acknowledged_reflect_ids: todo!(),
            commit_transaction_ack: todo!(),
        })
    }

    #[tracing::instrument(skip_all)]
    async fn reflect(&self, instruction: ReflectInstruction) -> anyhow::Result<ReflectResponse> {
        // TODO(LIB-16). Reflect messages, then wait for corresponding reflect-acks
        Ok(ReflectResponse {
            acknowledged_reflect_ids: todo!(),
        })
    }
}

/// Build the argument vector, taking the client key from `THREEMA_CLIENT_KEY` if it is not already
/// on the command line.
///
/// The client key *is* the identity: anyone holding it can impersonate it completely and read its
/// messages. Passed as a command-line argument it lands in `/proc/<pid>/cmdline`, which is
/// world-readable — every local user can lift it straight out of `ps`. `/proc/<pid>/environ` is
/// readable only by the owning user, so moving the key into the environment closes that hole.
///
/// Rebuilding the argument vector in-process is safe for this purpose precisely because
/// `/proc/<pid>/cmdline` reflects the original `exec` and is not affected by what we do here — the
/// key never appears there at all. It also means the reverse is impossible: a key passed as a real
/// argument cannot be hidden after the fact, only warned about.
fn arguments_with_client_key_from_env() -> Vec<OsString> {
    let mut arguments: Vec<OsString> = std::env::args_os().collect();

    // Matches both `--client-key VALUE` and `--client-key=VALUE`.
    let passed_on_command_line = arguments
        .iter()
        .any(|argument| argument.to_string_lossy().starts_with("--client-key"));

    if passed_on_command_line {
        eprintln!(
            "WARNING: --client-key puts the client key in /proc/<pid>/cmdline, which is \
             world-readable. Pass it as THREEMA_CLIENT_KEY in the environment instead."
        );
    } else if let Ok(client_key) = std::env::var("THREEMA_CLIENT_KEY") {
        arguments.push(OsString::from("--client-key"));
        arguments.push(OsString::from(client_key));
    }

    arguments
}

/// Read a credential that must not travel in argv.
///
/// Everything this file handles in the way of secrets — the client key, the license, and now the
/// backup password — comes through the environment for one reason: argv is world-readable on this
/// platform through `/proc/<pid>/cmdline`, so any other app on her phone could read a password
/// passed as a flag.
fn secret_from_env(name: &str) -> anyhow::Result<String> {
    std::env::var(name).map_err(|_| anyhow::anyhow!("{name} must be set in the environment"))
}

/// Turn the identity this device already holds into a Threema ID backup string.
///
/// Its own mode, and deliberately offline: exporting an account is not a conversation with the
/// chat server, and a caregiver should be able to take a copy of her account while the phone has
/// no signal. Nothing here touches the network or the message store.
///
/// The password is chosen by the caller, not here, and is never printed: it is shown to the
/// caregiver once by the app that generated it, and this process only borrows it.
fn export_identity() -> anyhow::Result<()> {
    let identity = secret_from_env("THREEMA_IDENTITY")?;
    let client_key = secret_from_env("THREEMA_CLIENT_KEY")?;
    let password = secret_from_env("THREEMA_BACKUP_PASSWORD")?;

    let threema_id = ThreemaId::try_from(identity.trim())
        .map_err(|error| anyhow::anyhow!("not a Threema identity: {error}"))?;
    let key_bytes: [u8; ClientKey::LENGTH] = HEXLOWER
        .decode(client_key.trim().to_lowercase().as_bytes())
        .map_err(|error| anyhow::anyhow!("client key is not hex: {error}"))?
        .try_into()
        .map_err(|_| anyhow::anyhow!("client key must be {} bytes", ClientKey::LENGTH))?;

    let backup = encrypt_identity_backup(
        &password,
        &IdentityBackupData {
            threema_id,
            client_key: ClientKey::from(key_bytes),
        },
    )
    .map_err(|error| anyhow::anyhow!("could not encrypt the backup: {error}"))?;

    println!(
        "{}",
        serde_json::to_string(&BridgeOutput::IdentityBackup { backup })?
    );
    Ok(())
}

/// Read a Threema ID backup string back into an identity and a client key.
///
/// Also its own mode, and for a harder reason than export: this runs *before* the device has an
/// identity at all, so it cannot be a command on the normal run — the normal run refuses to start
/// without the very thing this produces.
///
/// Deciding what to do with the result is the caller's: this only decrypts. Restoring the identity
/// that is already stored is therefore a no-op at the far end rather than an error here, which is
/// what makes importing the same backup twice harmless.
fn import_identity() -> anyhow::Result<()> {
    let backup = secret_from_env("THREEMA_BACKUP")?;
    let password = secret_from_env("THREEMA_BACKUP_PASSWORD")?;

    let (version, data) = decrypt_identity_backup(&password, backup.trim())
        .map_err(|error| anyhow::anyhow!("could not read the backup: {error}"))?;

    println!(
        "{}",
        serde_json::to_string(&BridgeOutput::IdentityImported {
            identity: data.threema_id.to_string(),
            client_key: HEXLOWER.encode(data.client_key.as_bytes()),
            scheme: format!("{version:?}"),
        })?
    );
    Ok(())
}

/// Register a brand new Threema identity against a serial ("shop") license.
///
/// Deliberately a separate mode rather than a step of the normal run: an identity is created
/// exactly once and then persisted by the caller. Doing it on every start would mint a new
/// account each time the app launched, and each one would be a stranger to all her contacts.
async fn create_identity_with_license(http_client: &reqwest::Client) -> anyhow::Result<()> {
    // The license is a credential, so it comes from the environment for the same reason the
    // client key does: argv is world-readable through /proc/<pid>/cmdline.
    let Ok(license_key) = std::env::var("THREEMA_LICENSE_KEY") else {
        bail!("THREEMA_LICENSE_KEY must be set to create an identity");
    };
    let device_id = std::env::var("THREEMA_DEVICE_ID").unwrap_or_else(|_| "gentle-connection".to_owned());

    let common = CommonConfig::from_options(
        http_client,
        CommonConfigOptions {
            consumer: Some(ConfigEnvironmentOption::Production),
            work: None,
            onprem: None,
            credentials: None,
        },
    )
    .await?;

    let client_key = ClientKey::random();
    let public_key = client_key.public_key();

    // Phase 1: ask for a challenge.
    let challenge_result = create_identity_phase1_request(
        &ClientInfo::Libthreema,
        &common.config.directory_server_url,
        &public_key,
    )
    .send(http_client)
    .await;
    let authentication = handle_authentication_challenge(&client_key, challenge_result)?;

    // Phase 2: answer it, presenting the license.
    let create_result = create_identity_with_license_phase2_request(
        &ClientInfo::Libthreema,
        &common.config.directory_server_url,
        &public_key,
        &device_id,
        &license_key,
        authentication,
    )
    .send(http_client)
    .await;
    let created = handle_create_identity_with_license_result(create_result)?;

    println!(
        "{}",
        serde_json::to_string(&BridgeOutput::IdentityCreated {
            identity: created.identity.to_string(),
            server_group: created.server_group.to_string(),
            client_key: HEXLOWER.encode(client_key.as_bytes()),
        })?
    );
    Ok(())
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // Configure logging: in release builds, log at Level::DEBUG to avoid flooding the rotating app.log
    // with TRACE lines (which accounted for >40% of a 5 MB rotating log). In debug builds, preserve
    // Level::TRACE for detailed local troubleshooting.
    let log_level = if cfg!(debug_assertions) {
        Level::TRACE
    } else {
        Level::DEBUG
    };
    init_stderr_logging(log_level);

    // Create HTTP client
    let http_client = https_client_builder().build()?;

    // Registration is its own mode and exits without touching the chat protocol, so the normal
    // run below still parses exactly the arguments it always did.
    if std::env::args_os().any(|argument| argument == *"--create-identity") {
        return create_identity_with_license(&http_client).await;
    }

    // Moving the account on and off this phone. Both are offline and neither needs the identity
    // the normal run below insists on, so they exit here for the same reason registration does.
    if std::env::args_os().any(|argument| argument == *"--export-identity") {
        return export_identity();
    }
    if std::env::args_os().any(|argument| argument == *"--import-identity") {
        return import_identity();
    }

    // Parse arguments for command
    let arguments = CspE2eReceiveCommand::parse_from(arguments_with_client_key_from_env());
    let nickname: &'static str = Box::leak(arguments.nickname.into_boxed_str());

    // Attachments, if this build was given somewhere to put them. A directory that cannot be
    // created is not fatal: every file message still becomes a visible message.
    if let Some(dir) = arguments.media_dir.as_ref() {
        match std::fs::create_dir_all(dir) {
            Ok(()) => media::configure(dir.clone(), http_client.clone()),
            Err(error) => println!("BRIDGE-JSON: {}", serde_json::to_string(&BridgeOutput::Log {
                level: "warn".to_owned(),
                message: format!("no media directory: {error}"),
            }).unwrap_or_default()),
        }
    }
    let config = FullIdentityConfig::from_options(&http_client, arguments.config).await?;

    // Set feature mask to include O2O_AUDIO_CALL_SUPPORT (0x10)
    {
        let user_identity = config.minimal.user_identity;
        let client_key = libthreema::common::keys::ClientKey::from(&config.minimal.client_key);
        // What her contacts' Threema clients are told this identity can do. It decides what they
        // are willing to send: a client that is not told about reactions will not send a modern
        // one, and says "this contact is using an older version of Threema" to the person trying.
        //
        // REACTION_SUPPORT is spelled out rather than named because this version of libthreema
        // does not define it — its FeatureMask stops at DELETE_MESSAGE_SUPPORT. The value is from
        // Threema's own `common.proto`: `REACTION_SUPPORT = 0x0400`.
        const REACTION_SUPPORT: u64 = 0x0400;
        let feature_mask = libthreema::common::FeatureMask::VOICE_MESSAGE_SUPPORT
            | libthreema::common::FeatureMask::GROUP_SUPPORT
            | libthreema::common::FeatureMask::POLL_SUPPORT
            | libthreema::common::FeatureMask::FILE_MESSAGE_SUPPORT
            | libthreema::common::FeatureMask::O2O_AUDIO_CALL_SUPPORT
            | REACTION_SUPPORT;

        let p1_req = set_feature_mask_phase1_request(
            &ClientInfo::Libthreema,
            &config.minimal.common.config.directory_server_url,
            &config.minimal.common.flavor,
            user_identity,
            feature_mask,
        );
        let p1_res = p1_req.send(&http_client).await;
        match handle_authentication_challenge(&client_key, p1_res) {
            Ok(auth_response) => {
                let p2_req = set_feature_mask_phase2_request(
                    &ClientInfo::Libthreema,
                    &config.minimal.common.config.directory_server_url,
                    &config.minimal.common.flavor,
                    user_identity,
                    feature_mask,
                    auth_response,
                );
                match handle_set_feature_mask_phase2_result(p2_req.send(&http_client).await) {
                    Ok(()) => {
                        println!("{}", serde_json::to_string(&BridgeOutput::Log {
                            level: "info".to_string(),
                            message: format!("Successfully set feature mask to {:#x}", feature_mask),
                        }).unwrap_or_default());
                    },
                    Err(e) => {
                        println!("{}", serde_json::to_string(&BridgeOutput::Log {
                            level: "error".to_string(),
                            message: format!("Failed set_featuremask phase 2: {}", e),
                        }).unwrap_or_default());
                    }
                }
            },
            Err(e) => {
                println!("{}", serde_json::to_string(&BridgeOutput::Log {
                    level: "error".to_string(),
                    message: format!("Failed set_featuremask phase 1: {}", e),
                }).unwrap_or_default());
            }
        }
    }

    // Create CSP E2EE protocol context
    let mut database = InMemoryDb::from(InMemoryDbInit {
        user_identity: config.minimal.user_identity,
        settings: InMemoryDbSettings {
            block_unknown_identities: false,
        },
        contacts: vec![],
        blocked_identities: vec![],
    });
    
    let database_contacts = database.contacts.clone();
    let csp_e2e_context = CspE2eProtocolContextInit {
        client_info: ClientInfo::Libthreema,
        config: Rc::clone(&config.minimal.common.config),
        csp_e2e: config.csp_e2e_context_init(Box::new(RefCell::new(database.csp_e2e_nonce_provider()))),
        d2x: config.d2x_context_init(Box::new(RefCell::new(database.d2d_nonce_provider()))),
        shortcut: Box::new(DefaultShortcutProvider),
        settings: Box::new(RefCell::new(database.settings_provider())),
        contacts: {
        Box::new(RefCell::new(database.contact_provider()))
    },
        conversations: Box::new(RefCell::new(LoggingConversationProvider { inner: database.message_provider() })),
    };

    // Create payload queues
    let (csp_e2e_queues, csp_queues) = {
        let (incoming_tx, incoming_rx) = mpsc::unbounded_channel();
        let (outgoing_tx, outgoing_rx) = mpsc::unbounded_channel();
        (
            PayloadQueuesForCspE2e {
                incoming: incoming_rx,
                outgoing: outgoing_tx,
            },
            PayloadQueuesForCsp {
                incoming: incoming_tx,
                outgoing: outgoing_rx,
            },
        )
    };

    // Create CSP protocol and establish a connection
    let (mut csp_runner, client_hello) = CspProtocolRunner::new(
        config
            .minimal
            .common
            .config
            .chat_server_address
            .addresses(config.csp_server_group),
        config
            .csp_context_init()
            .try_into()
            .expect("Configuration should be valid"),
    )
    .await?;

    // Run the handshake flow
    time::timeout(CONNECT_TIMEOUT, csp_runner.run_handshake_flow(client_hello))
        .await
        .map_err(|_| anyhow::anyhow!("chat server login did not complete within {CONNECT_TIMEOUT:?}"))??;

    
    // Create CSP E2E protocol
    let mut csp_e2e_protocol = CspE2eProtocolRunner::new(http_client.clone(), csp_e2e_context)?;

    // Spawn stdin reader
    let stdin_tx = csp_e2e_queues.outgoing.clone();
    let user_identity = config.minimal.user_identity;
    let client_key = libthreema::common::keys::ClientKey::from(&config.minimal.client_key);
    let contacts_ref = Rc::clone(&database_contacts);
    
    

    let mut stdin_lines = tokio::io::BufReader::new(tokio::io::stdin());
    let mut current_line = String::new();
    println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "info".to_string(), message: "Stdin reader ready".to_string() }).unwrap_or_default());
    
    // Run the protocols. Whichever ends first ends the bridge, and the app then reconnects —
    // so say which one it was and why. Without this a dropped server connection exits 0 with
    // nothing in any log, which is how a restart in the middle of placing a call (Fold,
    // 2026-09-16 21:35:32) could not be explained.
    let exit_reason: String = tokio::select! {
        result = csp_runner.run_payload_flow(csp_queues) => {
            format!("chat server connection ended: {}", describe_flow_result(&result))
        },
        result = csp_e2e_protocol.run_receive_flow(csp_e2e_queues) => {
            format!("message receive flow ended: {}", describe_flow_result(&result))
        },
        _ = signal::ctrl_c() => "interrupted".to_string(),
        _ = async {
            use tokio::io::AsyncBufReadExt;
            struct TurnCacheEntry {
                response: TurnServerResponse,
                fetched_at: std::time::Instant,
            }
            let mut turn_cache: Option<TurnCacheEntry> = None;

            // Held for the life of the connection. The server keeps accepting a match token until
            // it doesn't, so this is refreshed on failure rather than on a timer.
            let mut match_token: Option<String> = None;

            let _ = stdin_tx.send(OutgoingPayloadForCspE2e::UnblockIncomingMessages);
            while let Ok(len) = stdin_lines.read_line(&mut current_line).await {
                if len == 0 { break; }

                // Taken out of the buffer before anything can go wrong with it.
                //
                // `read_line` *appends*, and this loop has three dozen `continue`s in it — one of
                // them for an `AddContacts` whose identities are all known already, which is the
                // commonest command there is. Any of them left the line sitting in the buffer, and
                // the next command was then appended to it: two JSON objects in one string, which
                // parses as "trailing characters at line 2 column 1" and is thrown away. So a
                // contact sync (2026-09-17, her test phone) was never sent at all, and the
                // caregiver was told it had failed thirty seconds later with nothing to say why.
                // Every command after such a `continue` was being eaten, silently, including
                // messages.
                let line = std::mem::take(&mut current_line);
                let trimmed = line.trim();
                if trimmed.is_empty() { continue; }
                
                // The kind of command only, never the line. The caller writes these straight into
                // logcat, which is readable by anything that has held the phone — and a group send
                // carries the group, everybody in it and what she wrote. That is her social graph
                // and her words, echoed at debug level for no diagnostic gain the type does not
                // already give.
                println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "debug".to_string(), message: format!("STDIN RECEIVE: {}", command_kind(trimmed)) }).unwrap_or_default());

                match serde_json::from_str::<BridgeInput>(&line) {
                    Ok(BridgeInput::SendMessage { recipient, text, message_id }) => {
                        if let Ok(receiver_identity) = libthreema::common::ThreemaId::try_from(recipient.as_str()) {
                            // Check if contact exists
                            let contact_opt = {
                                let contacts = database_contacts.borrow();
                                contacts.get(&receiver_identity).map(|(c, _)| c.clone())
                            };
                            
                            let contact = if let Some(c) = contact_opt {
                                Some(c)
                            } else {
                                // Perform Directory Lookup
                                println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "info".to_string(), message: format!("Contact {} not in database, performing directory lookup...", recipient) }).unwrap_or_default());
                                let request = request_identities(
                                    &ClientInfo::Libthreema,
                                    &config.minimal.common.config.directory_server_url,
                                    &config.minimal.common.flavor,
                                    &[receiver_identity],
                                );
                                match handle_identities_result(request.send(&http_client).await) {
                                    Ok(identities) => {
                                        let identities: Vec<libthreema::model::contact::ContactInit> = identities;
                                        if let Some(init) = identities.into_iter().next() {
                                            let new_contact = libthreema::model::contact::Contact::from(init);
                                            // Store in database
                                            database_contacts.borrow_mut().insert(receiver_identity, (new_contact.clone(), None));
                                            Some(new_contact)
                                        } else {
                                            println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: format!("Recipient {} not found in Threema directory", recipient) }).unwrap_or_default());
                                            None
                                        }
                                    },
                                    Err(e) => {
                                        println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: format!("Directory lookup failed for {}: {}", recipient, e) }).unwrap_or_default());
                                        None
                                    }
                                }
                            };

                            if let Some(contact) = contact {
                                println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "info".to_string(), message: "Contact resolved successfully, encrypting...".to_string() }).unwrap_or_default());
                                let shared_secret = client_key.derive_csp_e2e_key(&contact.public_key);
                                
                                let message = libthreema::model::message::OutgoingMessage {
                                    id: outgoing_message_id(message_id.as_deref()),
                                    overrides: libthreema::model::message::MessageOverrides::default(),
                                    created_at: (std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64),
                                    body: libthreema::model::message::OutgoingMessageBody::Contact(
                                        libthreema::model::message::OutgoingContactMessageBody {
                                            receiver_identity,
                                            body: libthreema::model::message::ContactMessageBody::Text(
                                                libthreema::model::message::TextMessage { text }
                                            )
                                        }
                                    )
                                };
                                
                                let outgoing_box = libthreema::csp_e2e::message::task::outgoing::encode_and_encrypt_message(
                                    user_identity,
                                    (None, libthreema::common::Delta::Update(nickname)),
                                    receiver_identity,
                                    shared_secret,
                                    &message,
                                    libthreema::common::Nonce::random(),
                                );
                                println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "info".to_string(), message: "Encryption complete, converting to MessageWithMetadataBox...".to_string() }).unwrap_or_default());
                                
                                match libthreema::csp::payload::MessageWithMetadataBox::try_from(outgoing_box) {
                                    Ok(msg_box) => {
                                        println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "info".to_string(), message: "Conversion successful, sending to channel...".to_string() }).unwrap_or_default());
                                        if let Err(e) = stdin_tx.send(OutgoingPayloadForCspE2e::MessageWithMetadataBox(msg_box)) {
                                            println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: format!("Failed to send message payload to channel: {}", e) }).unwrap_or_default());
                                        } else {
                                            println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "info".to_string(), message: "Payload enqueued successfully in outgoing channel".to_string() }).unwrap_or_default());
                                        }
                                    },
                                    Err(e) => {
                                        println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: format!("Failed to convert outgoing box: {:?}", e) }).unwrap_or_default());
                                    }
                                }
                            }
                        }
                    },
                    Ok(BridgeInput::UploadMedia { request_id, path, thumbnail_path }) => {
                        // Spawned, like a download: an upload over a mobile connection takes
                        // seconds to minutes, and nothing else the bridge does may wait on it —
                        // least of all the messages arriving while it runs.
                        tokio::spawn(async move {
                            let uploaded = media::upload(&path, thumbnail_path.as_deref()).await;
                            let event = match uploaded {
                                Some(media) => BridgeOutput::MediaUploaded {
                                    request_id,
                                    blob_id: media.blob_id,
                                    thumbnail_blob_id: media.thumbnail_blob_id,
                                    key: media.key,
                                    size: media.size,
                                },
                                None => BridgeOutput::MediaUploadFailed { request_id },
                            };
                            println!("BRIDGE-JSON: {}", serde_json::to_string(&event).unwrap_or_default());
                        });
                    }
                    Ok(BridgeInput::SendRaw { recipient, message_type, data_hex }) => {
                        if let Ok(receiver_identity) = libthreema::common::ThreemaId::try_from(recipient.as_str()) {
                            let data = match HEXLOWER.decode(data_hex.as_bytes()) {
                                Ok(d) => d,
                                Err(e) => {
                                    println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: format!("SendRaw: invalid hex: {}", e) }).unwrap_or_default());
                                    continue;
                                }
                            };
                            
                            let contact_opt = {
                                let contacts = database_contacts.borrow();
                                contacts.get(&receiver_identity).map(|(c, _)| c.clone())
                            };
                            
                            let contact = if let Some(c) = contact_opt {
                                Some(c)
                            } else {
                                // Perform Directory Lookup
                                let request = request_identities(
                                    &ClientInfo::Libthreema,
                                    &config.minimal.common.config.directory_server_url,
                                    &config.minimal.common.flavor,
                                    &[receiver_identity],
                                );
                                match handle_identities_result(request.send(&http_client).await) {
                                    Ok(identities) => {
                                        let identities: Vec<libthreema::model::contact::ContactInit> = identities;
                                        if let Some(init) = identities.into_iter().next() {
                                            let new_contact = libthreema::model::contact::Contact::from(init);
                                            database_contacts.borrow_mut().insert(receiver_identity, (new_contact.clone(), None));
                                            Some(new_contact)
                                        } else {
                                            None
                                        }
                                    },
                                    Err(_) => None
                                }
                            };

                            if let Some(contact) = contact {
                                let shared_secret = client_key.derive_csp_e2e_key(&contact.public_key);
                                let nonce = libthreema::common::Nonce::random();
                                
                                let mut message_container = vec![message_type];
                                message_container.extend_from_slice(&data);
                                
                                // PKCS#7 padding to block size 16
                                let padding_len = 16 - (message_container.len() % 16);
                                message_container.resize(message_container.len() + padding_len, padding_len as u8);
                                
                                // Encrypt message container
                                libthreema::csp_e2e::message::task::outgoing::encrypt_message_container_in_place(&shared_secret, &nonce, &mut message_container);
                                
                                // Encode and encrypt metadata
                                let message_id = libthreema::common::MessageId::random();
                                let created_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64;
                                let mut metadata: Vec<u8> = libthreema::common::MessageMetadata {
                                    message_id,
                                    created_at,
                                    nickname: libthreema::common::Delta::Update(nickname.to_owned()),
                                }.into();
                                libthreema::csp_e2e::message::task::outgoing::encrypt_metadata_in_place(&shared_secret, &nonce, &mut metadata);
                                
                                let legacy_created_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs() as u32;
                                
                                let outgoing_box = libthreema::csp_e2e::message::payload::OutgoingMessageWithMetadataBox {
                                    sender_identity: user_identity,
                                    receiver_identity,
                                    id: message_id,
                                    legacy_created_at,
                                    flags: libthreema::common::MessageFlags(1),
                                    legacy_sender_nickname: Some(nickname.to_owned()),
                                    metadata,
                                    nonce,
                                    message_container,
                                };
                                
                                match libthreema::csp::payload::MessageWithMetadataBox::try_from(outgoing_box) {
                                    Ok(msg_box) => {
                                         if let Err(e) = stdin_tx.send(OutgoingPayloadForCspE2e::MessageWithMetadataBox(msg_box)) {
                                             println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: format!("Failed to send raw payload to channel: {}", e) }).unwrap_or_default());
                                         }
                                    },
                                    Err(e) => {
                                        println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: format!("Failed to convert outgoing raw box: {:?}", e) }).unwrap_or_default());
                                    }
                                }
                            }
                        }
                    },
                    Ok(BridgeInput::SendCall { recipient, message_type, data_hex }) => {
                        if !(0x60..=0x64).contains(&message_type) {
                            println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: format!("SendCall: invalid message_type {:#x}, expected 0x60..0x64", message_type) }).unwrap_or_default());
                            continue;
                        }
                        if let Ok(receiver_identity) = libthreema::common::ThreemaId::try_from(recipient.as_str()) {
                            let data = match HEXLOWER.decode(data_hex.as_bytes()) {
                                Ok(d) => d,
                                Err(e) => {
                                    println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: format!("SendCall: invalid hex: {}", e) }).unwrap_or_default());
                                    continue;
                                }
                            };
                            
                            let contact_opt = {
                                let contacts = database_contacts.borrow();
                                contacts.get(&receiver_identity).map(|(c, _)| c.clone())
                            };
                            
                            let contact = if let Some(c) = contact_opt {
                                Some(c)
                            } else {
                                // Perform Directory Lookup
                                let request = request_identities(
                                    &ClientInfo::Libthreema,
                                    &config.minimal.common.config.directory_server_url,
                                    &config.minimal.common.flavor,
                                    &[receiver_identity],
                                );
                                match handle_identities_result(request.send(&http_client).await) {
                                    Ok(identities) => {
                                        let identities: Vec<libthreema::model::contact::ContactInit> = identities;
                                        if let Some(init) = identities.into_iter().next() {
                                            let new_contact = libthreema::model::contact::Contact::from(init);
                                            database_contacts.borrow_mut().insert(receiver_identity, (new_contact.clone(), None));
                                            Some(new_contact)
                                        } else {
                                            None
                                        }
                                    },
                                    Err(_) => None
                                }
                            };

                            if let Some(contact) = contact {
                                let shared_secret = client_key.derive_csp_e2e_key(&contact.public_key);
                                let nonce = libthreema::common::Nonce::random();
                                
                                let mut message_container = vec![message_type];
                                message_container.extend_from_slice(&data);
                                
                                // PKCS#7 padding to block size 16
                                let padding_len = 16 - (message_container.len() % 16);
                                message_container.resize(message_container.len() + padding_len, padding_len as u8);
                                
                                // Encrypt message container
                                libthreema::csp_e2e::message::task::outgoing::encrypt_message_container_in_place(&shared_secret, &nonce, &mut message_container);
                                
                                // Encode and encrypt metadata
                                let message_id = libthreema::common::MessageId::random();
                                let created_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64;
                                let mut metadata: Vec<u8> = libthreema::common::MessageMetadata {
                                    message_id,
                                    created_at,
                                    nickname: libthreema::common::Delta::Update(nickname.to_owned()),
                                }.into();
                                libthreema::csp_e2e::message::task::outgoing::encrypt_metadata_in_place(&shared_secret, &nonce, &mut metadata);
                                
                                let legacy_created_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs() as u32;
                                
                                let outgoing_box = libthreema::csp_e2e::message::payload::OutgoingMessageWithMetadataBox {
                                    sender_identity: user_identity,
                                    receiver_identity,
                                    id: message_id,
                                    legacy_created_at,
                                    flags: libthreema::common::MessageFlags(if message_type == 0x63 { 0x01 } else { 0x21 }),
                                    legacy_sender_nickname: Some(nickname.to_owned()),
                                    metadata,
                                    nonce,
                                    message_container,
                                };
                                
                                match libthreema::csp::payload::MessageWithMetadataBox::try_from(outgoing_box) {
                                    Ok(msg_box) => {
                                         if let Err(e) = stdin_tx.send(OutgoingPayloadForCspE2e::MessageWithMetadataBox(msg_box)) {
                                             println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: format!("Failed to send call payload to channel: {}", e) }).unwrap_or_default());
                                         }
                                    },
                                    Err(e) => {
                                        println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: format!("Failed to convert outgoing call box: {:?}", e) }).unwrap_or_default());
                                    }
                                }
                            }
                        }
                    },
                    Ok(BridgeInput::SendGroupMessage { creator, group_id, members, text, file_hex, message_id }) => {
                        // Reported member by member, so a fan-out that reached three of five is
                        // not indistinguishable from one that reached everybody. Built up as we
                        // go and emitted once at the end, whatever happens in between — including
                        // the early exits below, where nobody was even attempted.
                        let mut delivered: Vec<String> = Vec::new();
                        let mut failed: Vec<String> = Vec::new();

                        let creator_upper = creator.trim().to_uppercase();
                        let group_id_bytes = parse_group_id(&group_id);
                        let creator_valid = libthreema::common::ThreemaId::try_from(creator_upper.as_str()).is_ok();

                        let payload = group_id_bytes.as_ref()
                            .and_then(|id| group_message_payload(&creator_upper, id, &text, file_hex.as_deref()));

                        match (payload, creator_valid) {
                            (Some((message_type, body)), true) => {
                                // The same id on every copy, exactly as the Threema app does it:
                                // this is one message that happens to be sent several times, not
                                // several messages, and the recipients' clients use the id to
                                // recognise that. Chosen by the app where it named one, so that a
                                // reaction to it can be matched to the row the app already has.
                                let message_id = outgoing_message_id(message_id.as_deref());
                                let created_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64;
                                let legacy_created_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs() as u32;

                                for member in &members {
                                    let member_upper = member.trim().to_uppercase();
                                    let receiver_identity = match libthreema::common::ThreemaId::try_from(member_upper.as_str()) {
                                        Ok(id) => id,
                                        Err(_) => {
                                            failed.push(member_upper);
                                            continue;
                                        }
                                    };
                                    if receiver_identity == user_identity {
                                        // Sending to herself would put her own message back in
                                        // her own list as though somebody had written it.
                                        continue;
                                    }

                                    let contact_opt = {
                                        let contacts = database_contacts.borrow();
                                        contacts.get(&receiver_identity).map(|(c, _)| c.clone())
                                    };
                                    let contact = if let Some(c) = contact_opt {
                                        Some(c)
                                    } else {
                                        let request = request_identities(
                                            &ClientInfo::Libthreema,
                                            &config.minimal.common.config.directory_server_url,
                                            &config.minimal.common.flavor,
                                            &[receiver_identity],
                                        );
                                        match handle_identities_result(request.send(&http_client).await) {
                                            Ok(identities) => {
                                                let identities: Vec<libthreema::model::contact::ContactInit> = identities;
                                                identities.into_iter().next().map(|init| {
                                                    let new_contact = libthreema::model::contact::Contact::from(init);
                                                    database_contacts.borrow_mut().insert(receiver_identity, (new_contact.clone(), None));
                                                    new_contact
                                                })
                                            },
                                            Err(_) => None,
                                        }
                                    };

                                    let contact = match contact {
                                        Some(c) => c,
                                        None => {
                                            failed.push(member_upper);
                                            continue;
                                        }
                                    };

                                    let shared_secret = client_key.derive_csp_e2e_key(&contact.public_key);
                                    let nonce = libthreema::common::Nonce::random();

                                    let mut message_container = vec![message_type];
                                    message_container.extend_from_slice(&body);
                                    let padding_len = 16 - (message_container.len() % 16);
                                    message_container.resize(message_container.len() + padding_len, padding_len as u8);
                                    libthreema::csp_e2e::message::task::outgoing::encrypt_message_container_in_place(&shared_secret, &nonce, &mut message_container);

                                    let mut metadata: Vec<u8> = libthreema::common::MessageMetadata {
                                        message_id,
                                        created_at,
                                        nickname: libthreema::common::Delta::Update(nickname.to_owned()),
                                    }.into();
                                    libthreema::csp_e2e::message::task::outgoing::encrypt_metadata_in_place(&shared_secret, &nonce, &mut metadata);

                                    let outgoing_box = libthreema::csp_e2e::message::payload::OutgoingMessageWithMetadataBox {
                                        sender_identity: user_identity,
                                        receiver_identity,
                                        id: message_id,
                                        legacy_created_at,
                                        // Push, plus the group marker the Threema app still sets
                                        // on every group message (`MESSAGE_FLAG_GROUP`, 0x10).
                                        // libthreema calls that bit reserved; real clients send
                                        // it, and this has to look like a real client.
                                        flags: libthreema::common::MessageFlags(0x11),
                                        legacy_sender_nickname: Some(nickname.to_owned()),
                                        metadata,
                                        nonce,
                                        message_container,
                                    };

                                    match libthreema::csp::payload::MessageWithMetadataBox::try_from(outgoing_box) {
                                        Ok(msg_box) => {
                                            if stdin_tx.send(OutgoingPayloadForCspE2e::MessageWithMetadataBox(msg_box)).is_ok() {
                                                delivered.push(member_upper);
                                            } else {
                                                failed.push(member_upper);
                                            }
                                        },
                                        Err(_) => failed.push(member_upper),
                                    }
                                }
                            },
                            _ => {
                                // A group we cannot name is a group nobody can be written to, so
                                // every member counts as failed rather than the send counting as
                                // having reached zero people successfully.
                                println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: "SendGroupMessage: malformed group identity or nothing to send".to_string() }).unwrap_or_default());
                                failed.extend(members.iter().map(|m| m.trim().to_uppercase()));
                            }
                        }

                        println!("{}", serde_json::to_string(&BridgeOutput::GroupSendResult {
                            creator: creator_upper,
                            group_id: group_id.trim().to_lowercase(),
                            message_id: message_id.clone(),
                            delivered,
                            failed,
                        }).unwrap_or_default());
                    },
                    Ok(BridgeInput::SendReaction { recipient, target_message_id, emoji, withdraw }) => {
                        let mut delivered = 0usize;
                        let mut failed = 0usize;

                        let recipient_upper = recipient.trim().to_uppercase();
                        if let (Ok(receiver_identity), Some(body)) = (
                            libthreema::common::ThreemaId::try_from(recipient_upper.as_str()),
                            reaction_body(&target_message_id, &emoji, withdraw),
                        ) {
                            if receiver_identity != user_identity {
                                let contact_opt = {
                                    let contacts = database_contacts.borrow();
                                    contacts.get(&receiver_identity).map(|(c, _)| c.clone())
                                };
                                let contact = if let Some(c) = contact_opt {
                                    Some(c)
                                } else {
                                    let request = request_identities(
                                        &ClientInfo::Libthreema,
                                        &config.minimal.common.config.directory_server_url,
                                        &config.minimal.common.flavor,
                                        &[receiver_identity],
                                    );
                                    match handle_identities_result(request.send(&http_client).await) {
                                        Ok(identities) => {
                                            let identities: Vec<libthreema::model::contact::ContactInit> = identities;
                                            identities.into_iter().next().map(|init| {
                                                let new_contact = libthreema::model::contact::Contact::from(init);
                                                database_contacts.borrow_mut().insert(receiver_identity, (new_contact.clone(), None));
                                                new_contact
                                            })
                                        },
                                        Err(_) => None,
                                    }
                                };

                                if let Some(contact) = contact {
                                    let shared_secret = client_key.derive_csp_e2e_key(&contact.public_key);
                                    let nonce = libthreema::common::Nonce::random();

                                    let mut message_container = vec![REACTION_MESSAGE_TYPE as u8];
                                    message_container.extend_from_slice(&body);
                                    let padding_len = 16 - (message_container.len() % 16);
                                    message_container.resize(message_container.len() + padding_len, padding_len as u8);
                                    libthreema::csp_e2e::message::task::outgoing::encrypt_message_container_in_place(&shared_secret, &nonce, &mut message_container);

                                    let message_id = libthreema::common::MessageId::random();
                                    let created_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64;
                                    let mut metadata: Vec<u8> = libthreema::common::MessageMetadata {
                                        message_id,
                                        created_at,
                                        nickname: libthreema::common::Delta::Update(nickname.to_owned()),
                                    }.into();
                                    libthreema::csp_e2e::message::task::outgoing::encrypt_metadata_in_place(&shared_secret, &nonce, &mut metadata);

                                    let legacy_created_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs() as u32;

                                    // One-to-one reaction: plain push (0x01).
                                    // encode_and_encrypt_message sets 0x01 for contact text messages
                                    // (TextMessage CONTACT_PROPERTIES has push: true and no other flags enabled).
                                    let outgoing_box = libthreema::csp_e2e::message::payload::OutgoingMessageWithMetadataBox {
                                        sender_identity: user_identity,
                                        receiver_identity,
                                        id: message_id,
                                        legacy_created_at,
                                        flags: libthreema::common::MessageFlags(libthreema::common::MessageFlags::SEND_PUSH_NOTIFICATION),
                                        legacy_sender_nickname: Some(nickname.to_owned()),
                                        metadata,
                                        nonce,
                                        message_container,
                                    };

                                    match libthreema::csp::payload::MessageWithMetadataBox::try_from(outgoing_box) {
                                        Ok(msg_box) => {
                                            if stdin_tx.send(OutgoingPayloadForCspE2e::MessageWithMetadataBox(msg_box)).is_ok() {
                                                delivered += 1;
                                            } else {
                                                failed += 1;
                                            }
                                        },
                                        Err(_) => {
                                            failed += 1;
                                        }
                                    }
                                } else {
                                    failed += 1;
                                }
                            } else {
                                failed += 1;
                            }
                        } else {
                            failed += 1;
                        }

                        println!("{}", serde_json::to_string(&BridgeOutput::ReactionResult {
                            target_message_id: target_message_id.clone(),
                            delivered,
                            failed,
                        }).unwrap_or_default());
                    },
                    Ok(BridgeInput::SendGroupReaction { creator, group_id, members, target_message_id, emoji, withdraw }) => {
                        let mut delivered = 0usize;
                        let mut failed = 0usize;

                        let creator_upper = creator.trim().to_uppercase();
                        let group_id_bytes = parse_group_id(&group_id);
                        let creator_valid = libthreema::common::ThreemaId::try_from(creator_upper.as_str()).is_ok();

                        match (group_id_bytes, creator_valid) {
                            (Some(group_id_bytes), true) => {
                                if let Some(body) = group_reaction_body(&creator_upper, &group_id_bytes, &target_message_id, &emoji, withdraw) {
                                    let message_id = libthreema::common::MessageId::random();
                                    let created_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64;
                                    let legacy_created_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs() as u32;

                                    for member in &members {
                                        let member_upper = member.trim().to_uppercase();
                                        let receiver_identity = match libthreema::common::ThreemaId::try_from(member_upper.as_str()) {
                                            Ok(id) => id,
                                            Err(_) => {
                                                failed += 1;
                                                continue;
                                            }
                                        };
                                        if receiver_identity == user_identity {
                                            // Sending to herself would put her own reaction back in
                                            // her own list as though somebody had sent it.
                                            continue;
                                        }

                                        let contact_opt = {
                                            let contacts = database_contacts.borrow();
                                            contacts.get(&receiver_identity).map(|(c, _)| c.clone())
                                        };
                                        let contact = if let Some(c) = contact_opt {
                                            Some(c)
                                        } else {
                                            let request = request_identities(
                                                &ClientInfo::Libthreema,
                                                &config.minimal.common.config.directory_server_url,
                                                &config.minimal.common.flavor,
                                                &[receiver_identity],
                                            );
                                            match handle_identities_result(request.send(&http_client).await) {
                                                Ok(identities) => {
                                                    let identities: Vec<libthreema::model::contact::ContactInit> = identities;
                                                    identities.into_iter().next().map(|init| {
                                                        let new_contact = libthreema::model::contact::Contact::from(init);
                                                        database_contacts.borrow_mut().insert(receiver_identity, (new_contact.clone(), None));
                                                        new_contact
                                                    })
                                                },
                                                Err(_) => None,
                                            }
                                        };

                                        let contact = match contact {
                                            Some(c) => c,
                                            None => {
                                                failed += 1;
                                                continue;
                                            }
                                        };

                                        let shared_secret = client_key.derive_csp_e2e_key(&contact.public_key);
                                        let nonce = libthreema::common::Nonce::random();

                                        let mut message_container = vec![GROUP_REACTION_MESSAGE_TYPE as u8];
                                        message_container.extend_from_slice(&body);
                                        let padding_len = 16 - (message_container.len() % 16);
                                        message_container.resize(message_container.len() + padding_len, padding_len as u8);
                                        libthreema::csp_e2e::message::task::outgoing::encrypt_message_container_in_place(&shared_secret, &nonce, &mut message_container);

                                        let mut metadata: Vec<u8> = libthreema::common::MessageMetadata {
                                            message_id,
                                            created_at,
                                            nickname: libthreema::common::Delta::Update(nickname.to_owned()),
                                        }.into();
                                        libthreema::csp_e2e::message::task::outgoing::encrypt_metadata_in_place(&shared_secret, &nonce, &mut metadata);

                                        let outgoing_box = libthreema::csp_e2e::message::payload::OutgoingMessageWithMetadataBox {
                                            sender_identity: user_identity,
                                            receiver_identity,
                                            id: message_id,
                                            legacy_created_at,
                                            // Push, plus the group marker the Threema app still sets
                                            // on every group message (`MESSAGE_FLAG_GROUP`, 0x10).
                                            // libthreema calls that bit reserved; real clients send
                                            // it, and this has to look like a real client.
                                            flags: libthreema::common::MessageFlags(0x11),
                                            legacy_sender_nickname: Some(nickname.to_owned()),
                                            metadata,
                                            nonce,
                                            message_container,
                                        };

                                        match libthreema::csp::payload::MessageWithMetadataBox::try_from(outgoing_box) {
                                            Ok(msg_box) => {
                                                if stdin_tx.send(OutgoingPayloadForCspE2e::MessageWithMetadataBox(msg_box)).is_ok() {
                                                    delivered += 1;
                                                } else {
                                                    failed += 1;
                                                }
                                            },
                                            Err(_) => failed += 1,
                                        }
                                    }
                                } else {
                                    failed += members.len();
                                }
                            },
                            _ => {
                                println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: "SendGroupReaction: malformed group identity".to_string() }).unwrap_or_default());
                                failed += members.len();
                            }
                        }

                        println!("{}", serde_json::to_string(&BridgeOutput::ReactionResult {
                            target_message_id: target_message_id.clone(),
                            delivered,
                            failed,
                        }).unwrap_or_default());
                    },
                    Ok(BridgeInput::AddContacts { identities }) => {
                        let unknown_identities: Vec<libthreema::common::ThreemaId> = identities
                            .iter()
                            .filter_map(|id| libthreema::common::ThreemaId::try_from(id.as_str()).ok())
                            .filter(|id| !database_contacts.borrow().contains_key(id))
                            .collect();
                        if unknown_identities.is_empty() {
                            println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "info".to_string(), message: "AddContacts: all identities already known".to_string() }).unwrap_or_default());
                            continue;
                        }
                        let request = request_identities(
                            &ClientInfo::Libthreema,
                            &config.minimal.common.config.directory_server_url,
                            &config.minimal.common.flavor,
                            &unknown_identities,
                        );
                        match handle_identities_result(request.send(&http_client).await) {
                            Ok(identities) => {
                                let identities: Vec<libthreema::model::contact::ContactInit> = identities;
                                let count = identities.len();
                                for init in identities {
                                    let contact = libthreema::model::contact::Contact::from(init);
                                    database_contacts.borrow_mut().insert(contact.identity, (contact, None));
                                }
                                println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "info".to_string(), message: format!("AddContacts: seeded {} of {} requested contacts", count, unknown_identities.len()) }).unwrap_or_default());
                            },
                            Err(e) => {
                                println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: format!("AddContacts: directory lookup failed: {:?}", e) }).unwrap_or_default());
                            }
                        }
                    },
                    Ok(BridgeInput::MatchContacts { phone_numbers, emails }) => {
                        // Hash first, so the plain addresses never leave this process. Keep the
                        // hash -> address mapping locally: the server answers with hashes, and
                        // without this map the caller could not tell which contact matched.
                        let mut by_phone_hash: HashMap<String, String> = HashMap::new();
                        let mut phone_hashes: Vec<String> = Vec::with_capacity(phone_numbers.len());
                        for number in &phone_numbers {
                            let encoded = contact_hash(&MOBILENO_HMAC_KEY, number);
                            by_phone_hash.insert(hash_key(&encoded), number.clone());
                            phone_hashes.push(encoded);
                        }
                        let mut by_email_hash: HashMap<String, String> = HashMap::new();
                        let mut email_hashes: Vec<String> = Vec::with_capacity(emails.len());
                        for email in &emails {
                            let encoded = contact_hash(&EMAIL_HMAC_KEY, email);
                            by_email_hash.insert(hash_key(&encoded), email.clone());
                            email_hashes.push(encoded);
                        }

                        if phone_hashes.is_empty() && email_hashes.is_empty() {
                            println!("{}", serde_json::to_string(&BridgeOutput::ContactMatches {
                                matches: vec![],
                                error: None,
                            }).unwrap_or_default());
                            continue;
                        }

                        // One retry, because the only way to learn that a match token has expired
                        // is to have the server reject it.
                        let mut outcome: Result<Vec<libthreema::https::directory::MatchedIdentity>, String> =
                            Err("not attempted".to_string());
                        for attempt in 0..2 {
                            if match_token.is_none() || attempt > 0 {
                                match_token = None;
                                let p1_req = match_token_phase1_request(
                                    &ClientInfo::Libthreema,
                                    &config.minimal.common.config.directory_server_url,
                                    &config.minimal.common.flavor,
                                    user_identity,
                                );
                                let p1_res = p1_req.send(&http_client).await;
                                match handle_authentication_challenge(&client_key, p1_res) {
                                    Ok(auth_response) => {
                                        let p2_req = match_token_phase2_request(
                                            &ClientInfo::Libthreema,
                                            &config.minimal.common.config.directory_server_url,
                                            &config.minimal.common.flavor,
                                            user_identity,
                                            auth_response,
                                        );
                                        match handle_match_token_phase2_result(p2_req.send(&http_client).await) {
                                            Ok(token) => match_token = Some(token),
                                            Err(e) => {
                                                outcome = Err(format!("match token rejected: {:?}", e));
                                                continue;
                                            }
                                        }
                                    },
                                    Err(e) => {
                                        outcome = Err(format!("match token challenge failed: {:?}", e));
                                        continue;
                                    }
                                }
                            }

                            let Some(ref token) = match_token else { continue };
                            let req = match_identities_request(
                                &ClientInfo::Libthreema,
                                &config.minimal.common.config.directory_server_url,
                                &config.minimal.common.flavor,
                                token,
                                &email_hashes,
                                &phone_hashes,
                            );
                            match handle_match_identities_result(req.send(&http_client).await) {
                                Ok(identities) => {
                                    outcome = Ok(identities);
                                    break;
                                },
                                Err(e) => {
                                    outcome = Err(format!("match failed: {:?}", e));
                                    match_token = None;
                                }
                            }
                        }

                        match outcome {
                            Ok(identities) => {
                                let mut matches: Vec<ContactMatch> = Vec::with_capacity(identities.len());
                                for matched in identities {
                                    // The match response carries the public key, so a matched
                                    // contact is ready to be messaged without a second lookup.
                                    if !database_contacts.borrow().contains_key(&matched.identity) {
                                        let request = request_identities(
                                            &ClientInfo::Libthreema,
                                            &config.minimal.common.config.directory_server_url,
                                            &config.minimal.common.flavor,
                                            &[matched.identity],
                                        );
                                        if let Ok(inits) = handle_identities_result(request.send(&http_client).await) {
                                            let inits: Vec<libthreema::model::contact::ContactInit> = inits;
                                            for init in inits {
                                                let contact = libthreema::model::contact::Contact::from(init);
                                                database_contacts.borrow_mut().insert(contact.identity, (contact, None));
                                            }
                                        }
                                    }
                                    matches.push(ContactMatch {
                                        identity: matched.identity.to_string(),
                                        phone_number: matched.mobile_no_hash
                                            .as_ref()
                                            .and_then(|hash| by_phone_hash.get(&hash_key(hash)).cloned()),
                                        email: matched.email_hash
                                            .as_ref()
                                            .and_then(|hash| by_email_hash.get(&hash_key(hash)).cloned()),
                                    });
                                }
                                // Counted separately because a match whose address could not be
                                // recovered is useless: it names an identity with no way to tell
                                // which contact it belongs to. If these two numbers ever differ,
                                // the hash echo is the thing to look at.
                                let attributable = matches
                                    .iter()
                                    .filter(|m| m.phone_number.is_some() || m.email.is_some())
                                    .count();
                                println!("{}", serde_json::to_string(&BridgeOutput::Log {
                                    level: "info".to_string(),
                                    message: format!(
                                        "MatchContacts: {} of {} addresses are on Threema ({} attributable)",
                                        matches.len(),
                                        phone_hashes.len() + email_hashes.len(),
                                        attributable
                                    ),
                                }).unwrap_or_default());
                                println!("{}", serde_json::to_string(&BridgeOutput::ContactMatches {
                                    matches,
                                    error: None,
                                }).unwrap_or_default());
                            },
                            Err(reason) => {
                                println!("{}", serde_json::to_string(&BridgeOutput::ContactMatches {
                                    matches: vec![],
                                    error: Some(reason),
                                }).unwrap_or_default());
                            }
                        }
                    },
                    Ok(BridgeInput::FetchTurnCredentials { turn_type }) => {
                        let desired_type = turn_type.as_deref().unwrap_or("voip");
                        let mut cached_valid = false;

                        if let Some(ref c) = turn_cache {
                            let elapsed = c.fetched_at.elapsed().as_secs();
                            if c.response.expiration > elapsed + 60 && c.response.success {
                                cached_valid = true;
                                let mut resp = c.response.clone();
                                resp.expiration = resp.expiration.saturating_sub(elapsed);
                                println!("{}", serde_json::to_string(&BridgeOutput::TurnCredentials {
                                    turn_urls: resp.turn_urls,
                                    turn_urls_dual_stack: resp.turn_urls_dual_stack,
                                    turn_username: resp.turn_username,
                                    turn_password: resp.turn_password,
                                    expiration: resp.expiration,
                                    error: None,
                                }).unwrap_or_default());
                            }
                        }

                        if !cached_valid {
                            let p1_req = obtain_turn_servers_phase1_request(
                                &ClientInfo::Libthreema,
                                &config.minimal.common.config.directory_server_url,
                                &config.minimal.common.flavor,
                                user_identity,
                                desired_type,
                            );
                            let p1_res = p1_req.send(&http_client).await;
                            match handle_authentication_challenge(&client_key, p1_res) {
                                Ok(auth_response) => {
                                    let p2_req = obtain_turn_servers_phase2_request(
                                        &ClientInfo::Libthreema,
                                        &config.minimal.common.config.directory_server_url,
                                        &config.minimal.common.flavor,
                                        user_identity,
                                        desired_type,
                                        auth_response,
                                    );
                                    match handle_obtain_turn_servers_phase2_result(p2_req.send(&http_client).await) {
                                        Ok(turn_resp) => {
                                            if turn_resp.success {
                                                turn_cache = Some(TurnCacheEntry {
                                                    response: turn_resp.clone(),
                                                    fetched_at: std::time::Instant::now(),
                                                });
                                                println!("{}", serde_json::to_string(&BridgeOutput::TurnCredentials {
                                                    turn_urls: turn_resp.turn_urls,
                                                    turn_urls_dual_stack: turn_resp.turn_urls_dual_stack,
                                                    turn_username: turn_resp.turn_username,
                                                    turn_password: turn_resp.turn_password,
                                                    expiration: turn_resp.expiration,
                                                    error: None,
                                                }).unwrap_or_default());
                                            } else {
                                                let err_msg = turn_resp.error.clone().unwrap_or_else(|| "TURN fetch unsuccessful".to_string());
                                                println!("{}", serde_json::to_string(&BridgeOutput::TurnCredentials {
                                                    turn_urls: vec![],
                                                    turn_urls_dual_stack: vec![],
                                                    turn_username: String::new(),
                                                    turn_password: String::new(),
                                                    expiration: 0,
                                                    error: Some(err_msg),
                                                }).unwrap_or_default());
                                            }
                                        },
                                        Err(e) => {
                                            println!("{}", serde_json::to_string(&BridgeOutput::TurnCredentials {
                                                turn_urls: vec![],
                                                turn_urls_dual_stack: vec![],
                                                turn_username: String::new(),
                                                turn_password: String::new(),
                                                expiration: 0,
                                                error: Some(format!("Failed TURN cred phase 2: {}", e)),
                                            }).unwrap_or_default());
                                        }
                                    }
                                },
                                Err(e) => {
                                    println!("{}", serde_json::to_string(&BridgeOutput::TurnCredentials {
                                        turn_urls: vec![],
                                        turn_urls_dual_stack: vec![],
                                        turn_username: String::new(),
                                        turn_password: String::new(),
                                        expiration: 0,
                                        error: Some(format!("Failed TURN cred phase 1: {}", e)),
                                    }).unwrap_or_default());
                                }
                            }
                        }
                    },

                    Err(e) => {
                        println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "error".to_string(), message: format!("JSON parse error: {}", e) }).unwrap_or_default());
                    }
                }
            }
        } => "stdin closed by the app".to_string(),
    };
    println!("{}", serde_json::to_string(&BridgeOutput::Log { level: "warn".to_string(), message: format!("Bridge exiting: {}", exit_reason) }).unwrap_or_default());

    // Shut down, then leave for real. Returning from main is not enough: the stdin reader is a
    // blocking read tokio cannot cancel, so the runtime would wait on it until the app next sends
    // a command — overnight, never. The app only reconnects once this process has exited, so a
    // bridge stuck here kept Threema silent from one evening to the next morning (Fold,
    // 2026-09-17/18).
    let _ = time::timeout(Duration::from_secs(5), csp_runner.shutdown()).await;
    {
        use std::io::Write as _;
        let _ = std::io::stdout().flush();
    }
    std::process::exit(0)
}

#[test]
fn verify_cli() {
    use clap::CommandFactory;
    CspE2eReceiveCommand::command().debug_assert();
}

#[test]
fn outgoing_message_id_is_printed_back_as_chosen() {
    let hex = "121d580deea0ad5b";
    let msg_id = outgoing_message_id(Some(hex));
    assert_eq!(format!("{:016x}", msg_id.0), hex);
}

/// Sample real-device trace: a Threema 👍 on a help request, as it arrived. Its target
/// is the alert's wire bytes, `16 97 96 c0 09 a9 eb 92`, read as a little-endian fixed64.
#[test]
fn a_real_reaction_names_the_message_as_the_bridge_prints_it() {
    use libthreema::protobuf::csp_e2e::Reaction;
    use prost::Message as _;

    let wire = [0x16, 0x97, 0x96, 0xc0, 0x09, 0xa9, 0xeb, 0x92];
    let body = [9, 0x16, 0x97, 0x96, 0xc0, 0x09, 0xa9, 0xeb, 0x92, 18, 4, 0xf0, 0x9f, 0x91, 0x8d];
    let reaction = Reaction::decode(&body[..]).expect("the real reaction decodes");
    let printed = format!("{:016x}", libthreema::common::MessageId::from(wire).0);
    assert_eq!(format!("{:016x}", reaction.message_id), printed);
}

/// What the app chose is what a reaction to it comes back as.
#[test]
fn a_reaction_to_her_own_message_names_the_id_the_app_chose() {
    use libthreema::protobuf::csp_e2e::Reaction;
    use prost::Message as _;

    let chosen = "169796c009a9eb92";
    let wire = outgoing_message_id(Some(chosen)).to_bytes();
    // A Threema client reacting reads those eight bytes as the protobuf's fixed64.
    let mut body = vec![9];
    body.extend_from_slice(&wire);
    body.extend_from_slice(&[18, 4, 0xf0, 0x9f, 0x91, 0x8d]);
    let reaction = Reaction::decode(body.as_slice()).expect("decodes");
    assert_eq!(format!("{:016x}", reaction.message_id), chosen);
}

#[test]
fn a_receipt_body_carries_each_id_in_wire_order() {
    let id = outgoing_message_id(Some("169796c009a9eb92"));
    assert_eq!(delivery_receipt_hex(0x01, &[id]), "0192eba909c0969716");
}


#[cfg(test)]
mod id_backup_tests {
    use data_encoding::HEXLOWER;
    use libthreema::common::ThreemaId;
    use libthreema::common::keys::ClientKey;
    use libthreema::id_backup::{IdentityBackupData, decrypt_identity_backup, encrypt_identity_backup};

    fn backup_data() -> IdentityBackupData {
        IdentityBackupData {
            threema_id: ThreemaId::try_from("ABCD1234").expect("a valid test identity"),
            client_key: ClientKey::from([7_u8; ClientKey::LENGTH]),
        }
    }

    /// The account has to survive the trip exactly, because what comes back IS the account: a
    /// client key that differs by one byte is not a damaged backup, it is a stranger.
    #[test]
    fn round_trips_the_identity_and_the_key() {
        let backup = encrypt_identity_backup("a chosen password", &backup_data()).expect("encrypts");
        let (_version, restored) = decrypt_identity_backup("a chosen password", &backup).expect("decrypts");

        assert_eq!(restored.threema_id.to_string(), "ABCD1234");
        assert_eq!(
            HEXLOWER.encode(restored.client_key.as_bytes()),
            HEXLOWER.encode(backup_data().client_key.as_bytes())
        );
    }

    /// A wrong password must fail rather than hand back a plausible-looking identity. Restoring
    /// rubbish would leave her with an account nobody can write to and no sign anything went wrong.
    #[test]
    fn refuses_a_wrong_password() {
        let backup = encrypt_identity_backup("the right one", &backup_data()).expect("encrypts");
        assert!(decrypt_identity_backup("the wrong one", &backup).is_err());
    }

    /// Two exports of the same account are both valid, even though the ciphertext differs — the
    /// scheme salts each one. Importing either must give the same account back, which is what
    /// makes re-exporting safe and re-importing idempotent.
    #[test]
    fn every_export_of_one_account_restores_to_that_account() {
        let first = encrypt_identity_backup("password", &backup_data()).expect("encrypts");
        let second = encrypt_identity_backup("password", &backup_data()).expect("encrypts");

        let (_, from_first) = decrypt_identity_backup("password", &first).expect("decrypts");
        let (_, from_second) = decrypt_identity_backup("password", &second).expect("decrypts");
        assert_eq!(from_first.threema_id, from_second.threema_id);
        assert_eq!(from_first.client_key.as_bytes(), from_second.client_key.as_bytes());
    }
}
