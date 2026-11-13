package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.data.db.MessageDao
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.Reactions
import ch.heuscher.gentleconnect.model.SendState
import ch.heuscher.gentleconnect.model.StrandedFilms
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Her conversations, kept.
 *
 * Thin on purpose: everything that decides anything lives in [ConversationKey],
 * [ConversationBuilder] and [SendStatePolicy], which are pure and therefore tested. This holds the
 * database handle, which is the one thing CI cannot exercise.
 */
class ConversationRepository(private val dao: MessageDao) {

    /** Everything, oldest first. Bounded by how much she has ever written, which is small. */
    val messages: Flow<List<Message>> =
        dao.getAllFlow().map { rows -> rows.map(StoredMessageMapper::toMessage) }

    suspend fun record(message: Message, conversationId: String) {
        dao.insertIfNew(StoredMessageMapper.toEntity(message, conversationId))
    }

    suspend fun markAsRead(id: String) {
        dao.markAsRead(id)
    }

    /**
     * Whether a message this transport already knows by [wireMessageId] has been stored.
     *
     * The one question a messenger's echo of her own words has to answer before it is kept: did
     * this app send it, or did she write it somewhere else?
     */
    suspend fun hasWireMessage(wireMessageId: String, transport: BridgeTransport): Boolean =
        dao.hasWireMessage(wireMessageId, transport.name)

    /**
     * Attaches a photograph that has finished downloading to the message it belongs to.
     *
     * Arrives after the message, sometimes long after: the words are in the message itself, the
     * picture has to be fetched. Matching no row is a normal outcome and says nothing is wrong.
     */
    suspend fun attachPhoto(messageId: String, path: String) {
        dao.attachPhoto(messageId, path)
    }

    /**
     * This emoji is now the whole of what this sender has on the message — or none of theirs is.
     *
     * For the transports where a person has one reaction at a time and a change arrives as a
     * replacement with no withdrawal of what it replaces: see [Reactions.replacedBy]. Where a person
     * can have several at once, [applyReaction] and [withdrawReaction] are the pair to use.
     *
     * Matched two ways because a message can be known by two names. Anything that arrived is
     * filed under the messenger's own id; anything she sent is filed under one the app invented,
     * and carries the messenger's id alongside. A reaction only ever names the latter.
     */
    suspend fun setReaction(messageId: String, wireMessageId: String, sender: String, emoji: String?) {
        val existing = dao.reactionOn(messageId, wireMessageId)
        dao.setReaction(messageId, wireMessageId, Reactions.replacedBy(existing, sender, emoji ?: ""))
    }

    /**
     * Somebody added a reaction to what is already on the message.
     *
     * Read-then-write rather than one statement, because SQLite cannot be asked to append to a
     * set without either a second table or string surgery in SQL, and the reaction column is
     * touched a handful of times a day by messages that arrive one at a time on a single
     * collector. Two of them landing on the same message in the same millisecond would lose the
     * older of the two, which is the same thing that happened to every reaction before this.
     */
    suspend fun applyReaction(messageId: String, wireMessageId: String, sender: String, emoji: String) {
        val existing = dao.reactionOn(messageId, wireMessageId)
        dao.setReaction(messageId, wireMessageId, Reactions.appliedBy(existing, sender, emoji))
    }

    /**
     * Somebody took one reaction back, leaving the rest of theirs where they were.
     *
     * The withdrawal names the emoji, and only that one goes — see [Reactions.withdrawnBy].
     * Passing null or blank removes all marks from that sender.
     */
    suspend fun withdrawReaction(messageId: String, wireMessageId: String, sender: String, emoji: String?) {
        val existing = dao.reactionOn(messageId, wireMessageId) ?: return
        dao.setReaction(messageId, wireMessageId, Reactions.withdrawnBy(existing, sender, emoji))
    }

    /** As [attachPhoto], for a voice message. */
    suspend fun attachAudio(messageId: String, path: String) {
        dao.attachAudio(messageId, path)
    }

    /** As [attachPhoto], for a film. Its still arrived separately and is already on the message. */
    suspend fun attachVideo(messageId: String, path: String) {
        dao.attachVideo(messageId, path)
    }

    /**
     * Gives every video message back a film that is on disk but was never attached to it.
     *
     * The bridge says a film has landed with an event, and an event can be lost — four films on
     * the Fold were, so the messages showed a still with nothing to play. The file itself cannot
     * be lost that way, so this goes by the file: wherever a film lies beside a message's still
     * (see [StrandedFilms]) and the message has none, it is attached. Both bridges rename a film
     * into place only once it is whole, so a film found here is one that plays.
     *
     * Idempotent, and cheap enough for every start: one query, and a file check per picture.
     *
     * @return how many films were attached.
     */
    suspend fun attachStrandedFilms(): Int {
        var attached = 0
        for (row in dao.stillsWithoutFilm()) {
            val film = StrandedFilms.filmBeside(row.photoPath)?.let { File(it) } ?: continue
            if (film.isFile && film.length() > 0) {
                dao.attachVideo(row.id, film.path)
                attached++
            }
        }
        return attached
    }

    /**
     * Records what became of a message of hers.
     *
     * Called immediately after the transport answers, so that a `PENDING` row is only ever the
     * moment between handing a message over and hearing back — see
     * [SendStatePolicy.STALE_PENDING_AFTER_MS] for what happens when that moment does not end.
     */
    suspend fun updateSendState(id: String, state: SendState) {
        dao.updateSendState(id, state.name)
    }

    /**
     * Deletes a single message from the persistent database on this phone.
     */
    suspend fun deleteMessage(messageId: String) {
        dao.forget(messageId)
    }

    /**
     * Deletes all messages of one conversation from the persistent database on this phone.
     */
    suspend fun deleteConversation(conversationId: String) {
        dao.deleteConversation(conversationId)
    }
}

