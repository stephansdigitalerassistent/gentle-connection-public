package ch.heuscher.gentleconnect.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.heuscher.gentleconnect.data.CaregiverLog
import ch.heuscher.gentleconnect.data.ContactsRepository
import ch.heuscher.gentleconnect.data.ConversationLinks
import ch.heuscher.gentleconnect.data.ConversationLinksSource
import ch.heuscher.gentleconnect.data.ConversationList
import ch.heuscher.gentleconnect.data.ConversationRepository
import ch.heuscher.gentleconnect.data.LocalMessageStore
import ch.heuscher.gentleconnect.data.MessageCatalog
import ch.heuscher.gentleconnect.data.MutedConversationsRepository
import ch.heuscher.gentleconnect.data.Outbox
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import ch.heuscher.gentleconnect.model.ConversationReply
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.Recipient
import ch.heuscher.gentleconnect.model.Seen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The conversation she has open: which one it is, what is in it, what a reply would answer, and
 * everything she can do in it — answer, react, send again, pass a picture on, mute, delete.
 *
 * The messages come from [MessageCatalog] and every send goes through [Outbox]; this holds only
 * which conversation is open and turns her presses into calls on those two.
 */
class ConversationViewModel(
    private val messageStore: LocalMessageStore,
    private val catalog: MessageCatalog,
    private val outbox: Outbox,
    /** Her address book, read fresh for a number another app asked her to write to. */
    private val contactsRepository: ContactsRepository?,
    private val mutedConversationsRepository: MutedConversationsRepository?,
    private val conversationRepository: ConversationRepository?,
    /** Writes a fault down for the caregiver. Never waited for. */
    private val recordFault: (CaregiverEvent) -> Unit,
    private val onGoal: (String) -> Unit = {}
) : ViewModel() {

    private val conversationLinks: StateFlow<ConversationLinks> get() = catalog.conversationLinks
    private val namedMessages: StateFlow<List<Message>> get() = catalog.namedMessages
    private val namedStoredMessages: StateFlow<List<Message>> get() = catalog.namedStoredMessages
    private val visibleMessages: StateFlow<List<Message>> get() = catalog.visibleMessages

    private fun conversationIdOf(message: Message, links: ConversationLinks): String? =
        ConversationLinksSource.conversationIdOf(message, links)

    private fun conversationIdOf(recipient: Recipient, links: ConversationLinks): String? =
        ConversationLinksSource.conversationIdOf(recipient, links)

    private fun normalizedNumber(raw: String?): String? = ConversationLinksSource.normalizedNumber(raw)

    /** Writes a fault down, the subject anonymised on the way in. */
    private fun recordCaregiverEvent(kind: CaregiverEventKind, subject: String? = null) {
        recordFault(
            CaregiverEvent(
                kind = kind,
                at = System.currentTimeMillis(),
                subject = CaregiverLog.anonymise(subject)
            )
        )
    }

    /** The conversation she has open, if any. Null means she is on a list screen. */
    private val _openConversationId = MutableStateFlow<String?>(null)
    val openConversationId: StateFlow<String?> = _openConversationId.asStateFlow()

    private val _openConversationTitle = MutableStateFlow("")

    /** Whose conversation is open — a person's name, never a service's. */
    val openConversationTitle: StateFlow<String> = _openConversationTitle.asStateFlow()

    /**
     * The open conversation: what they wrote and what she wrote, in one list, oldest first.
     *
     * Drawn from [messages] rather than [visibleMessages] on purpose. The flat list collapses
     * repeats so that her carrier sending the same tariff four times occupies one row; inside a
     * conversation the same collapse would delete a message somebody deliberately sent twice.
     *
     * The one thing that is folded is a message of hers the phone knows about twice — see
     * [ConversationList.joined]. That is not two things somebody said; it is one message this app
     * both recorded and filed in the telephone's sent box, met again on the next launch by the SMS
     * import.
     */
    val openConversationMessages: StateFlow<List<Message>> = combine(
        namedMessages,
        namedStoredMessages,
        _openConversationId,
        conversationLinks
    ) { arrived, stored, openId, links ->
        if (openId == null) return@combine emptyList()
        val outgoing = stored.filter { it.direction == MessageDirection.OUTGOING }
        ConversationList.joined(recorded = outgoing, alsoKnown = arrived)
            .filter { conversationIdOf(it, links) == openId }
            .sortedBy { it.timestamp }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The message a reply in the open conversation answers.
     *
     * Which one that is belongs to [ConversationReply], because getting it wrong sends a message
     * to somebody she was not talking to and nothing on the screen would say so. Null means the
     * reply affordance is absent — no greyed-out field and no explanation, per the rule that her
     * screen carries no error copy.
     */
    val openConversationReplyTarget: StateFlow<Message?> = openConversationMessages.map { list ->
        ConversationReply.targetIn(list)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * @return whether there was a conversation to open. False is rare now that a sender with no
     *   telephone number still has one ([ConversationKey.SERVICE_PREFIX]) — but it happens, and the
     *   caller must not move her to the conversation screen when it does. It used to: the screen
     *   changed whatever this said, so a message the app could not file put her on a conversation
     *   with no name at the top and nothing underneath it.
     */
    fun openConversation(message: Message): Boolean {
        val id = conversationIdOf(message, conversationLinks.value) ?: return false
        _openConversationId.value = id
        // Several people are named by the group; one person is named by their name. Never a
        // label saying which of the two this is — she can see that from the conversation.
        _openConversationTitle.value = message.displayName
        markConversationRead(id, message)
        return true
    }

    /**
     * Everything in an opened conversation counts as seen, not only the line she tapped.
     *
     * This used to mark the one message the list row stood for, which is the newest. Anything
     * older that had never been opened stayed unread for good — and because the card at the top of
     * her home screen and the mark on the Message button both ask "is anything unread from a
     * person", both of them stayed lit after she had read everything. A day later the card still
     * said her neighbour had written. That is worse than no card at all: the one thing on the
     * screen that is supposed to mean "something new" comes to mean nothing, and she has no way to
     * clear it and no reason to trust it again.
     *
     * Reading a conversation is how a person clears it. There is no other gesture in this app for
     * saying "I have seen that", and there must not be one — a "mark as read" button is exactly
     * the kind of housekeeping this launcher exists to remove.
     */
    private fun markConversationRead(conversationId: String, opened: Message) {
        val links = conversationLinks.value
        val inConversation = namedMessages.value.filter { conversationIdOf(it, links) == conversationId }
        Seen.whatOpeningClears(opened, inConversation).forEach { outbox.markMessageAsRead(it) }
    }

    /**
     * Open the conversation a single message belongs to, given only the message's id.
     *
     * For the notice: what it carries is an id, because that is all a notification can carry — the
     * message itself has to be found again on this side. See MainActivity's arrival handling.
     *
     * Waits, rather than failing on a miss. When the press *starts* the app, this runs while the
     * message lists are still filling: Firestore has to answer, the database has to be read, and
     * for a moment the id belongs to nothing. Giving up in that moment would leave her on the home
     * screen for the one case — a cold phone on a kitchen table — this whole path exists for.
     *
     * @return whether the conversation was opened. False means the message never appeared, which
     *   the caller answers by leaving her on the home screen, where the card still is.
     */
    suspend fun openConversationForMessage(messageId: String): Boolean {
        val wanted = messageId.trim().ifEmpty { return false }
        val message = withTimeoutOrNull(WAIT_FOR_MESSAGE_MS) {
            visibleMessages.mapNotNull { list -> list.firstOrNull { it.id == wanted } }.first()
        } ?: return false

        return openConversation(message)
    }

    /**
     * Open the conversation with whoever owns a telephone number.
     *
     * For another app's "write to this person" — see MainActivity's `showForWriting`. All it hands
     * over is a number, so the person has to be found again on this side, exactly as the notice
     * hands over a message id and [openConversationForMessage] finds the message.
     *
     * Found by looking for something they have written rather than by building a conversation key
     * from the number: a message that reached her already carries whatever the app has since
     * learned about that person — their entry in her address book, their Threema identity, the
     * conversation her replies are already filed under. A key built here from the number alone can
     * disagree with all of that, and disagreeing means a second, empty conversation opening beside
     * the one she has been reading.
     *
     * Waits, for the reason [openConversationForMessage] waits: the request can be what starts the
     * app, and for a moment the lists are still filling.
     *
     * @return whether a conversation was opened. False means nobody on the phone has ever written
     *   from that number. Inventing an empty conversation for it would be a screen with nothing on
     *   it, so the caller offers the line to write on instead — see [recipientForNumber].
     */
    suspend fun openConversationWithNumber(number: String): Boolean {
        val wanted = normalizedNumber(number) ?: return false
        val message = withTimeoutOrNull(WAIT_FOR_MESSAGE_MS) {
            visibleMessages.mapNotNull { list ->
                list.firstOrNull { normalizedNumber(it.senderAddress ?: it.senderId) == wanted }
            }.first()
        } ?: return false

        return openConversation(message)
    }

    /**
     * Who a number another app asked her to write to belongs to, for when nobody has written from
     * it yet — see [openConversationWithNumber], which is asked first.
     *
     * Her address book first, so the dialog carries a name and a face and the message goes the way
     * everybody she writes to from the phone book goes. A number nobody on the phone knows is
     * still somebody she can text: a shop's "text us" button, a doctor's appointment line. It
     * comes back as the number alone, because refusing it left her on the message list with no
     * sign that anything had been asked for — the request "did not work", from where she stands.
     *
     * Read fresh rather than from [systemContacts], which is only filled once the phone book has
     * been opened, and a request from another app can be the first thing the launcher does.
     */
    suspend fun recipientForNumber(number: String): Recipient? {
        val wanted = normalizedNumber(number) ?: return null
        val contact = withContext(Dispatchers.IO) {
            runCatching { contactsRepository?.querySystemContacts() }.getOrNull().orEmpty()
        }.firstOrNull { normalizedNumber(it.phoneNumber) == wanted }

        val recipient = if (contact != null) {
            Recipient(
                name = contact.name,
                photoUri = contact.photoUri,
                threemaId = contact.threemaId,
                phoneNumber = contact.phoneNumber
            )
        } else {
            Recipient(name = number.trim(), phoneNumber = number.trim())
        }
        return recipient.takeIf { it.isReachable }
    }

    /** Opens the conversation with somebody she is about to write to, before anything is sent. */
    fun openConversationWith(recipient: Recipient) {
        val id = conversationIdOf(recipient, conversationLinks.value) ?: return
        _openConversationId.value = id
        _openConversationTitle.value = recipient.name
    }

    fun closeConversation() {
        _openConversationId.value = null
        _openConversationTitle.value = ""
    }

    /** Sends one of her messages again, after she has held it. See [Outbox.resend]. */
    suspend fun resend(message: Message): Boolean = outbox.resend(message)

    /** React to a message with a big emoji (primary keyboard-free reply). See [Outbox.sendReaction]. */
    suspend fun sendReaction(message: Message, emoji: String): Boolean = outbox.sendReaction(message, emoji)

    /** Send an optional short text reply. See [Outbox.sendTextReply]. */
    suspend fun sendTextReply(message: Message, text: String): Boolean =
        outbox.sendTextReply(message, text).also { if (it) onGoal("message sent") }

    /** Write to someone first, over whichever route reaches them. See [Outbox.sendNewMessage]. */
    suspend fun sendNewMessage(recipient: Recipient, text: String): Boolean =
        outbox.sendNewMessage(recipient, text).also { if (it) onGoal("message sent") }

    /** Sends a photograph, film or voice message on to somebody else. See [Outbox.forwardMedia]. */
    suspend fun forwardMedia(message: Message, recipient: Recipient): Boolean = outbox.forwardMedia(message, recipient)

    /** Sends a photo taken with the camera to a single recipient. See [Outbox.sendPhoto]. */
    suspend fun sendPhoto(recipient: Recipient, path: String): Boolean =
        outbox.sendPhoto(recipient, path).also { if (it) onGoal("message sent") }

    /**
     * Whether [conversationId] is currently muted on this phone.
     */
    fun isConversationMuted(conversationId: String): Boolean =
        mutedConversationsRepository?.isMuted(conversationId) ?: false

    /**
     * Toggles muting for [conversationId] on this phone.
     * Logs [CaregiverEventKind.CONVERSATION_MUTED] when muting is turned on.
     */
    fun toggleMuteConversation(conversationId: String, subjectName: String? = null): Boolean {
        val repo = mutedConversationsRepository ?: return false
        val newMuted = repo.toggleMute(conversationId)
        if (newMuted) {
            recordCaregiverEvent(CaregiverEventKind.CONVERSATION_MUTED, subjectName)
        }
        return newMuted
    }

    /**
     * Deletes a single message from local in-memory and persistent storage on this phone only.
     * Logs [CaregiverEventKind.MESSAGE_DELETED_ON_PHONE].
     */
    fun deleteMessage(messageId: String, subjectName: String? = null) {
        messageStore.deleteMessage(messageId)
        val repo = conversationRepository
        if (repo != null) {
            viewModelScope.launch {
                repo.deleteMessage(messageId)
            }
        }
        recordCaregiverEvent(CaregiverEventKind.MESSAGE_DELETED_ON_PHONE, subjectName)
    }

    /**
     * Deletes all messages of one conversation from local storage on this phone only.
     * Closes the conversation if it is currently open on her screen.
     * Logs [CaregiverEventKind.CONVERSATION_DELETED_ON_PHONE].
     */
    fun deleteConversation(conversationId: String, subjectName: String? = null) {
        // Asked before the deletion, because afterwards there is nothing left to ask. The two
        // stores file messages differently — see [LocalMessageStore.conversationMessageIds] —
        // so the database is told both the key and the exact ids the screen just lost.
        val ids = messageStore.conversationMessageIds(conversationId)
        messageStore.deleteConversation(conversationId)
        val repo = conversationRepository
        if (repo != null) {
            viewModelScope.launch {
                repo.deleteConversation(conversationId)
                ids.forEach { repo.deleteMessage(it) }
            }
        }
        if (_openConversationId.value == conversationId) {
            closeConversation()
        }
        recordCaregiverEvent(CaregiverEventKind.CONVERSATION_DELETED_ON_PHONE, subjectName)
    }

    private companion object {
        /**
         * How long the notice waits for its message to turn up before settling for the home screen.
         *
         * Long enough for a cold start on a phone that has just been woken — sign-in, the first
         * Firestore answer, the first read of the database — and short enough that a message which
         * is never coming back (deleted at the far end, pruned here) does not leave her looking at
         * a screen that is about to change under her hand.
         */
        const val WAIT_FOR_MESSAGE_MS = 8_000L
    }
}
