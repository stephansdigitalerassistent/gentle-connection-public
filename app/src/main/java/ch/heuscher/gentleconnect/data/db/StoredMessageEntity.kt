package ch.heuscher.gentleconnect.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One message she sent or received, kept for good.
 *
 * Two separate gaps this closes. Nothing she *sent* was ever stored — [MessageDirection] did not
 * exist and there was nowhere for an outgoing message to live. And messages the embedded Threema
 * client *received* lived in a `MutableStateFlow` in the ViewModel, so they lasted exactly as long
 * as the process did; bridge messages persist in Firestore and SMS persists in the system inbox,
 * but a Threema message vanished when the launcher restarted.
 *
 * Her history is not a cache. There is no retention window here and nothing prunes this table.
 *
 * Note on the column names: the spec's table said `senderId`/`senderName`, but for a message of
 * hers the sender is her, and what the conversation actually needs to know is **who is at the other
 * end** — otherwise a conversation she started, before any reply arrives, has no name to show.
 * So one pair of columns holds the counterpart in both directions.
 */
@Entity(
    tableName = "messages",
    indices = [Index("conversationId"), Index("timestamp")]
)
data class StoredMessageEntity(
    /** Source-prefixed (`threema_`, `bridge_`, `sms_`, `out_`) — the existing dedup key. */
    @PrimaryKey val id: String,
    /** Derived by `ConversationKey`, stored so a conversation is one query rather than a scan. */
    val conversationId: String,
    /** `INCOMING` / `OUTGOING`, the name of a `MessageDirection`. */
    val direction: String,
    /** The other person: their Threema ID or telephone number. Never her own. */
    val counterpartId: String?,
    /** The other person's name as it was resolved when this was written. */
    val counterpartName: String,
    val text: String,
    val timestamp: Long,
    /** Incoming only; an outgoing message is one she wrote, so there is nothing to have read. */
    val isRead: Boolean = false,
    /** `PENDING` / `SENT` / `FAILED`, the name of a `SendState`. Null for incoming. */
    val sendState: String? = null,
    /** For routing a reply, never for display — she wrote to a person, not to a service. */
    val transport: String,
    /** Which route carried it, so a reply goes back the way it came. */
    val channel: String,
    /** Both null for a one-to-one conversation; see `GroupIdentity`. */
    val groupCreator: String? = null,
    val groupId: String? = null,
    /**
     * A photograph belonging to this message, in app-private storage. Null for almost everything.
     *
     * A path rather than the image itself: a Room row is read on every conversation query, and
     * putting photographs in the database means carrying them through every one of those reads.
     * The file may be gone — the bridge keeps only the newest few hundred — so whatever draws
     * this has to survive the file having vanished under it, and show the message's text.
     */
    val photoPath: String? = null,
    /**
     * A voice message belonging to this message, in app-private storage.
     *
     * Its own column rather than a kind beside [photoPath], because the two are not alternatives:
     * a photograph is drawn and a voice message gets a control to press, and nothing stops a
     * message one day having both. Same rules as [photoPath] — a path, and it may have been
     * pruned since.
     */
    val audioPath: String? = null,
    /**
     * A film belonging to this message, in app-private storage.
     *
     * A third column rather than a kind beside [photoPath], for the reason [audioPath] is one: a
     * video message has a still *and* a film, and they arrive minutes apart. Storing one path for
     * both would mean the film overwriting the picture that was already on her screen — and then
     * the pruner deleting the film, leaving a row pointing at nothing where a picture used to be.
     */
    val videoPath: String? = null,
    /**
     * What somebody has reacted to this message with, or null.
     *
     * Stored rather than derived, because a reaction arrives as its own message and the thing it
     * refers to may be four years old. Before this column existed the model carried a reaction
     * that the database silently dropped, so it survived exactly as long as the process did.
     *
     * Several of them, separated, rather than the newest one — `Reactions` holds the encoding and
     * the reasoning. It reads as one column and needs no migration: a row written when this held
     * a single emoji still holds exactly that.
     *
     * Still not a tally. What this refuses is the count, the names and the ordering by popularity,
     * not the second thing a person did: a row of six hearts from six people is a counting
     * exercise she has no reason to be set, but a heart and a laughing face from her daughter are
     * two things her daughter did, and dropping one of them told her a change of mind that never
     * happened.
     */
    val reaction: String? = null,
    /**
     * What the messenger itself calls this message, when that differs from [id].
     *
     * Only ever set on messages she sent. Incoming messages are already filed under the
     * messenger's own id; her own were filed under one the app invented, because the id was
     * chosen inside the bridge and never reported back. A reaction names the messenger's id, so
     * without this column a reaction to something she wrote matched nothing at all.
     */
    val wireMessageId: String? = null,
    /**
     * The message this one is answering, under this app's own id prefix (`threema_`, `whatsapp_`).
     *
     * Nullable: most messages are not answers to anything.
     */
    val quotedId: String? = null,
    /** The quoted person's name as carried on the wire (WhatsApp). Null for Threema. */
    val quotedSenderName: String? = null,
    /** The quoted words as carried on the wire (WhatsApp). Null for Threema. */
    val quotedText: String? = null,
    /** Whether the quoted message was sent by her. Defaulted to false. */
    val quotedIsHers: Boolean = false,
    /**
     * When this phone actually got the message, kept apart from [timestamp], which is when it was
     * written. The two differ by hours for anything that was queued while offline. Zero when not
     * known (migrated rows and bridge messages).
     */
    val receivedAt: Long = 0L
)
