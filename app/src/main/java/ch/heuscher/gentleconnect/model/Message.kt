package ch.heuscher.gentleconnect.model

/**
 * Where a message came from. [DEVICE] is her own phone — the SMS inbox; [BRIDGE] is a message
 * relayed by a server-side bridge through Firestore; [ON_DEVICE] came from a messenger client
 * running inside this app, which today means the embedded Threema client.
 *
 * The distinction between [BRIDGE] and [ON_DEVICE] is not cosmetic: it decides where an answer
 * goes. This used to name a NOTIFICATION channel, back when the launcher also read other apps'
 * notifications off the status bar; that source is gone.
 */
enum class MessageChannel { DEVICE, BRIDGE, ON_DEVICE }

/**
 * Normalized incoming message model across all messaging services.
 * Note: [sourcePackage] is kept for reply dispatch, but never shown to the user.
 */
data class Message(
    val id: String,
    val senderName: String,
    val senderAddress: String? = null,
    val contactId: String? = null,
    val text: String,
    val timestamp: Long,
    val sourcePackage: String,
    val isRead: Boolean = false,
    val reaction: String? = null,
    val isMissedCall: Boolean = false,
    /** Sender's address on [transport] — a Threema ID, a WhatsApp number, etc. */
    val senderId: String? = null,
    val transport: BridgeTransport = BridgeTransport.UNKNOWN,
    val channel: MessageChannel = MessageChannel.DEVICE,
    /**
     * The group this was sent to, or null when it was sent to her alone.
     *
     * Load-bearing rather than informational: without it a message to eight people is
     * indistinguishable from a private one, and answering it replies to whoever happened to
     * write. See [canBeAnswered].
     */
    val groupIdentity: GroupIdentity? = null,
    /**
     * Which way this went. Defaulted to [MessageDirection.INCOMING] because everything that
     * existed before conversations did was something that had arrived.
     */
    val direction: MessageDirection = MessageDirection.INCOMING,
    /** How far one of her own messages got. Null for anything that arrived; see [SendState]. */
    val sendState: SendState? = null,
    /**
     * Which conversation this belongs to, when it came from the store, which already knows.
     * Null for a message we have only just been handed, where the caller derives it with
     * `ConversationKey`.
     */
    val conversationId: String? = null,
    /**
     * What the several people this was sent to are called on her screen — the group's own name, or
     * their names. Null for a one-to-one message and for a group we have been told nothing about.
     *
     * Derived rather than stored: it is filled in when the message is read, from what the group
     * control messages have told us, so a group that renames itself renames everywhere at once.
     * [senderName] stays the person who actually wrote, because inside a conversation with several
     * people that is the one thing she cannot work out for herself.
     */
    val groupName: String? = null,
    /**
     * Whether the several people this went to can be written back to.
     *
     * Derived when the message is read, like [groupName], from what the group control messages
     * have told us — a group whose membership we were never sent cannot be answered, because a
     * fan-out to a guessed membership reaches some people and looks exactly like reaching
     * everybody. `GroupReplyPolicy` is what decides it; this only carries the answer to the screen
     * that has to decide whether to offer a reply field.
     *
     * False by default, which is the safe way round: a message that has not been through that
     * decision is not answerable.
     */
    val groupCanBeAnswered: Boolean = false,
    /**
     * A photograph that came with this message, as a path into app-private storage.
     *
     * Null for nearly every message, and null is not a failure: the file arrives after the
     * message does, may never arrive at all — WhatsApp expires media, so most of an imported
     * history has none — and is deleted again once a few hundred newer ones exist. Everything
     * that draws it has to be happy with the message's text alone, because that is the common
     * case and the fallback at the same time.
     */
    val photoPath: String? = null,
    /**
     * A voice message that came with this message, as a path into app-private storage.
     *
     * The most valuable thing this app carries, for somebody who is losing her sight: it needs no
     * reading at all. Null for nearly every message, and null is not a failure — same rules as
     * [photoPath].
     */
    val audioPath: String? = null,
    /**
     * A film that came with this message, as a path into app-private storage.
     *
     * Separate from [photoPath] rather than replacing it, because a video message normally has
     * both and they arrive at different times: the still is inside the message and is on her
     * screen immediately, the film has to be fetched and is much the larger of the two. So the
     * bubble shows the picture first and grows a way to play the film underneath it if and when
     * one lands. Null is the ordinary case — same rules as [photoPath].
     */
    val videoPath: String? = null,
    /**
     * What the messenger calls this message, where that differs from [id].
     *
     * Set on her own messages so that a reaction to one can be matched. See `ThreemaWireId`.
     */
    val wireMessageId: String? = null,
    /**
     * The message this one is answering, in this app's id space ("threema_<hex>" / "whatsapp_<id>").
     *
     * Null for nearly every message. Derived from the wire marker on Threema; read from ContextInfo
     * on WhatsApp.
     */
    val quotedId: String? = null,
    /**
     * What the quoted sender was called on the wire, when carried by the transport.
     *
     * WhatsApp puts a copy of the sender on the wire; Threema does not. When [quotedId] resolves
     * against a message in the store, the resolved message's sender name wins over this one so
     * that a renamed contact renames inside quotes too.
     */
    val quotedSenderName: String? = null,
    /**
     * The quoted words as carried by the transport, when the transport carries a copy.
     *
     * WhatsApp carries the text; Threema sends only the id. As with [quotedSenderName], when the
     * quoted message is present in the store, its own text is preferred.
     */
    val quotedText: String? = null,
    /**
     * Whether the message being quoted was sent by her ([MessageDirection.OUTGOING]).
     *
     * Carried explicitly rather than inferred from a null sender name: a WhatsApp quote whose
     * Participant is missing arrives with text but no sender, and without this flag she would
     * be told her daughter's message was her own.
     */
    val quotedIsHers: Boolean = false,
    /**
     * Whether a caregiver has said this sender is a person, whatever the address suggests.
     *
     * Derived on the way to the screen, like [groupName] and [groupCanBeAnswered], rather than
     * stored: the correction is a live setting somebody changes on a website, and a phone that
     * had written it into its own database would go on showing last week's answer. See
     * [SenderJudgement], which owns the rule, and [senderKind], which is the only thing that
     * reads this.
     *
     * False by default and false everywhere the list has not been through that decision, which
     * is the safe way round — an uncorrected message is filed exactly as it always was.
     */
    val treatedAsPerson: Boolean = false,
    /**
     * When this phone actually got the message, as opposed to [timestamp], which is when it was
     * written. Zero when it is not known.
     */
    val receivedAt: Long = 0L
) {
    val isUnread: Boolean
        get() = !isRead

    /** Hers rather than theirs — drawn differently, and never with a "You:" label. */
    val isOutgoing: Boolean
        get() = direction == MessageDirection.OUTGOING

    /** Whether this went to several people rather than to her alone. */
    val isGroupMessage: Boolean
        get() = groupIdentity != null && !isStatus

    /**
     * Posted to everybody, rather than sent to her — see [StatusBroadcast].
     *
     * Deliberately excluded from [isGroupMessage] above. A status arrives wearing a group's
     * clothes, and everything that asks "is this a group?" is really asking "does a reply to this
     * go to several people?" — for which the answer here is that there is no reply at all.
     */
    val isStatus: Boolean
        get() = StatusBroadcast.isStatus(groupIdentity)

    /**
     * Who she would say this message is from: the several people, or the one person.
     *
     * A single property because the answer is needed in three places — the nudge, the message list
     * and the conversation's title — and they must agree. They did not: the nudge was left saying
     * "TSTMBR01 wrote a message" about a group the other two named correctly.
     */
    val displayName: String
        get() = groupName ?: senderName

    /**
     * Whether this message has anything to say in words, or the thing itself is the whole of it.
     *
     * An attachment arrives as a message whose text is a token — `[Foto]`, `[Video]`,
     * `[Sprachnachricht]`; see [Attachment]. Where the thing that token stands for is on the
     * screen, the word is the app describing what she is already looking at or about to press: a
     * line reading "Photo" above a photograph, "Video" above a still with a play control under it,
     * "Voice message" above the button that plays the voice. It was the only text in the message,
     * so nothing is lost by leaving it out, and what remains is what her daughter actually sent.
     *
     * Only when the thing is really there, and only the thing that token names. A photograph that
     * never downloaded keeps its word; so does a film that never arrived, even though its still
     * did — a still with no control is a picture, and the word is the only thing left saying there
     * was a film. An empty bubble with a name and a time on it would be her being told somebody
     * wrote and shown nothing at all.
     *
     * A place, a document, a poll, a contact card: nothing of these is ever drawn, so their word is
     * all there is and it always stays.
     *
     * A caption always counts as words, whatever came with it — see [Attachment.captionIn].
     */
    val showsWords: Boolean
        get() = Attachment.captionIn(text).isNotBlank() || !attachmentIsOnScreen

    /** Whether what the token names is drawn in this bubble, rather than only named. */
    private val attachmentIsOnScreen: Boolean
        get() = when (Attachment.of(text)) {
            Attachment.PHOTO -> !photoPath.isNullOrBlank()
            Attachment.VIDEO -> !videoPath.isNullOrBlank()
            Attachment.VOICE -> !audioPath.isNullOrBlank()
            else -> false
        }
}

/**
 * Whether these are two copies of one message rather than two messages.
 *
 * One thing somebody said reaches this app by more than one route and under a different id on each
 * of them, so nothing but the content says they are the same. Two arrivals of a message have to
 * become one line on her screen: seeing her daughter's question twice is her being asked twice.
 *
 * The same rule serves both places that need it — the store that takes arrivals in
 * ([ch.heuscher.gentleconnect.data.LocalMessageStore]) and the conversation that merges what is
 * stored with what has arrived — and it lives here so the two cannot drift apart.
 *
 * **Direction is part of it.** Without that, a real exchange collapses: her sister writes "Ja
 * gerne", she answers "Ja gerne" seconds later, and every other field matches, so the reply — the
 * one message that proves she answered — is swallowed as a repeat of the question.
 */
fun Message.isSameMessageAs(other: Message): Boolean =
    id == other.id || (
        direction == other.direction &&
            senderName.equals(other.senderName, ignoreCase = true) &&
            text == other.text &&
            kotlin.math.abs(timestamp - other.timestamp) < SAME_MESSAGE_WINDOW_MS
        )

/**
 * How far apart two copies of one message can be timestamped.
 *
 * They are stamped by whoever handed them over — the sender's clock, the network's, the moment the
 * radio delivered it — so the same message is never the same millisecond twice.
 */
private const val SAME_MESSAGE_WINDOW_MS = 30_000L

/** The clock to judge arrival by: when the phone got it, falling back to when it was written. */
fun Message.arrivedAt(): Long = if (receivedAt > 0L) receivedAt else timestamp

