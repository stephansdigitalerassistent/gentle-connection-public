package ch.heuscher.gentleconnect.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /**
     * IGNORE rather than REPLACE, which is the whole point of it.
     *
     * The primary key is what stops a redelivery becoming a second message. But REPLACE deletes the
     * row and writes a fresh one, and the fresh one carries the `isRead` the Threema client knows —
     * which is false. So a message she had already read would quietly become unread again, and
     * reappear in the nudge at the top of her home screen as though somebody had just written.
     * Nothing that arrives twice has anything new to tell us; the first copy stands.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(message: StoredMessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(messages: List<StoredMessageEntity>)

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllFlow(): Flow<List<StoredMessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAllMessages(): List<StoredMessageEntity>

    /** What the caregiver report counts — no text, no people's names. See `CaregiverStatsExport`. */
    @Query("SELECT timestamp, direction, transport, groupId, groupCreator FROM messages WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun countsSince(since: Long): List<MessageCountRow>

    /**
     * Whether a message this transport calls [wireId] is already stored.
     *
     * Asked of a message of hers coming back off the wire, to tell an echo of something this app
     * sent — which is already on her screen — from something she wrote in the messenger's own app,
     * which exists nowhere else. See `ArrivalPipeline.isOwnEcho`.
     *
     * Narrowed by transport because the id spaces are unrelated: the app invents sixteen
     * hexadecimal characters for a Threema message, and a WhatsApp id is whatever WhatsApp chose.
     * A collision across the two is unlikely and would be invisible — a message of hers silently
     * missing — which is exactly the kind of fault worth spending a WHERE clause on.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM messages WHERE wireMessageId = :wireId AND transport = :transport)"
    )
    suspend fun hasWireMessage(wireId: String, transport: String): Boolean

    /**
     * Attaches a photograph to a message that is already on her screen.
     *
     * An update rather than part of the insert: the picture is fetched after the message arrives,
     * and during a history import several hundred of them land minutes apart. Matching nothing is
     * an ordinary outcome — the message may have been dropped as unreadable, or the row may be
     * from a different messenger entirely — so this deliberately reports nothing.
     */
    @Query("UPDATE messages SET photoPath = :path WHERE id = :id")
    suspend fun attachPhoto(id: String, path: String)

    /**
     * Writes the whole of what is on a message, or clears it when [emoji] is null.
     *
     * The stored form is `Reactions`-encoded: one emoji as itself, several separated. Callers that
     * mean "add one" or "take one back" go through `ConversationRepository`, which reads what is
     * there first — this only ever writes what it is given.
     *
     * Matching nothing is ordinary: people react to messages older than anything this phone has
     * ever stored, and a reaction to a message she has never seen is nothing at all to her.
     */
    @Query("UPDATE messages SET reaction = :emoji WHERE id = :id OR wireMessageId = :wireId")
    suspend fun setReaction(id: String, wireId: String, emoji: String?)

    /**
     * What is already on a message, so that one reaction can be added to it or taken off it.
     *
     * Matched the same two ways [setReaction] writes. Rows holding nothing are skipped rather than
     * returned: a message can be known by both its names at once, and either row is the same
     * message, so what is wanted is whichever of them has a reaction on it. Null means the message
     * has none, or is not here at all — the caller treats those the same way.
     */
    @Query(
        "SELECT reaction FROM messages " +
            "WHERE (id = :id OR wireMessageId = :wireId) AND reaction IS NOT NULL LIMIT 1"
    )
    suspend fun reactionOn(id: String, wireId: String): String?

    /** As [attachPhoto], for somebody's voice. */
    @Query("UPDATE messages SET audioPath = :path WHERE id = :id")
    suspend fun attachAudio(id: String, path: String)

    /**
     * As [attachPhoto], for a film.
     *
     * Touches nothing but its own column on purpose: the still from the same video message has
     * already been written to `photoPath`, and it is what she sees while this is downloading.
     */
    @Query("UPDATE messages SET videoPath = :path WHERE id = :id")
    suspend fun attachVideo(id: String, path: String)

    /** Every message with a still and no film — see `ConversationRepository.attachStrandedFilms`. */
    @Query("SELECT id, photoPath FROM messages WHERE photoPath IS NOT NULL AND videoPath IS NULL")
    suspend fun stillsWithoutFilm(): List<StillRow>

    @Query("UPDATE messages SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: String)

    @Query("UPDATE messages SET sendState = :sendState WHERE id = :id")
    suspend fun updateSendState(id: String, sendState: String)

    /**
     * There is deliberately no delete here beyond this one, and this one is for the caregiver's
     * retry path rather than for tidying up. Her history is the only copy that exists.
     */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun forget(id: String)

    /**
     * Removes all messages of one conversation from this phone's database.
     *
     * Used by caregiver Advanced Mode when explicitly deleting a conversation on this device.
     */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    /**
     * Remove the messages an archive is about to write again, and no others.
     *
     * The one exception to the rule above, and it is narrow: the ids come out of the backup file
     * itself, so this can only reach rows a previous import of that same archive created. It
     * exists because [insertIfNew] cannot correct a row it already put there — see
     * ThreemaBackupParser.parseMessageIds, and the 82 telephone calls that stayed in her father's
     * conversation for two imports after the parser had learned they were not messages.
     *
     * A message somebody has reacted to is left alone even so. A backup carries no reactions, so
     * writing that row again would take her son's heart off it to correct a word — and the word
     * is cosmetic where the heart is the whole message.
     */
    @Query("DELETE FROM messages WHERE id IN (:ids) AND reaction IS NULL")
    suspend fun deleteByIds(ids: List<String>): Int
}

/** One message as the caregiver report sees it: when, which way, over what, and whether in a group. */
data class MessageCountRow(
    val timestamp: Long,
    val direction: String,
    val transport: String,
    val groupId: String?,
    /** With [groupId], which group it was; see `GroupIdentity`. */
    val groupCreator: String? = null
)

/** A message's still, for finding the film that should be beside it. */
data class StillRow(
    val id: String,
    val photoPath: String?
)
