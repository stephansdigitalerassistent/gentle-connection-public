package ch.heuscher.gentleconnect.model

import ch.heuscher.gentleconnect.data.MessageRouter

/**
 * Whether the thing that sent a message is a person or a company.
 *
 * Her inbox is not only people. "Reinigung!" says her dry cleaning is ready and "RoamingInfo"
 * quotes tariffs in Swedish kronor — both arrived on the phone looking exactly like a message
 * from her daughter: same card, same face in the same green circle. The rule that a person is a
 * person and never a logo says nothing about giving a laundry the same portrait as her family;
 * that comparison is the one thing on the screen that tells her whether a message is worth
 * opening.
 *
 * The signal is the sender's address, and it is decisive. An SMS whose originator contains a
 * letter cannot be a subscriber — the alphanumeric originating address is reserved for services,
 * which is also why she can never reply to one. Numbers too short to be a telephone number are
 * short codes, likewise services.
 */
enum class SenderKind {
    PERSON,
    ORGANISATION;

    /** She can only write back to a person; there is no subscriber behind a service address. */
    val canReply: Boolean
        get() = this == PERSON

    companion object {
        /**
         * Shorter than this is a short code, not somebody's telephone. The shortest real
         * national numbers in use run to seven digits, so this errs on the generous side: a
         * borderline number is treated as a person rather than filed away as a company.
         */
        private const val SHORTEST_REAL_NUMBER = 7

        fun of(
            senderAddress: String?,
            channel: MessageChannel = MessageChannel.DEVICE,
            contactId: String? = null
        ): SenderKind {
            // In her address book, so she decided this was a person long before we did.
            if (!contactId.isNullOrBlank()) return PERSON
            // A messenger only ever carries people she is in contact with — the bridge its
            // paired partners, the embedded Threema client the identities that write to her.
            // Neither is a channel a laundry can reach her on.
            if (channel == MessageChannel.BRIDGE || channel == MessageChannel.ON_DEVICE) {
                return PERSON
            }

            val address = senderAddress?.trim().orEmpty()
            if (address.isEmpty()) return ORGANISATION
            if (address.any { it.isLetter() }) return ORGANISATION
            return if (address.count { it.isDigit() } < SHORTEST_REAL_NUMBER) {
                ORGANISATION
            } else {
                PERSON
            }
        }
    }
}

/**
 * What her screen makes of whoever sent this: a face and a place on the home screen, or a mark.
 *
 * The address decides it, except where somebody looking after her has said otherwise — her
 * doctor's surgery writes from four digits and is not a marketing service, and there is no rule
 * that could tell the two apart. [Message.treatedAsPerson] carries that correction in; see
 * [SenderJudgement] for who makes it and what it deliberately does not reach.
 */
val Message.senderKind: SenderKind
    get() = if (treatedAsPerson) SenderKind.PERSON else fromTheAddress

/**
 * What the address alone says, uncorrected.
 *
 * Kept apart from [senderKind] because one question in this file must never be answered by the
 * caregiver's correction: whether there is anybody there to write back to. See
 * [namesSomebodyToWriteTo].
 */
private val Message.fromTheAddress: SenderKind
    get() = SenderKind.of(senderAddress, channel, contactId)

/**
 * Whether she can answer this message at all.
 *
 * Three separate reasons she cannot. There may be nobody at the other end — a service address,
 * see [SenderKind]. There may be a person there and no way to reach them: a text message can only
 * be answered by a text message, and whether the app sends those at all is one switch
 * ([MessageRouter.SMS_ENABLED]).
 *
 * And it may have been sent to several people. A reply always goes back the way the message came,
 * which for everything else is exactly right — but a group message came from one person on behalf
 * of several, and answering the sender would reach that one person while everyone else saw
 * nothing. She would have no way to notice: she answered what looked like a private message and
 * got a private reply. So a group is answerable only when we know who is in it, which is what
 * [Message.groupCanBeAnswered] carries in from `GroupReplyPolicy` — an incomplete membership means
 * a message that reaches some of the family and looks exactly like it reached all of them.
 *
 * And a message of her own is not something to answer. It is here so that the check holds wherever
 * a conversation hands a message to a reply path, rather than depending on the screen having picked
 * the right one out of the list.
 *
 * Asked of the address ([Message.fromTheAddress]) and never of [Message.senderKind], which a
 * caregiver can correct. Deciding that the surgery is a person is a decision about her home screen;
 * it cannot put a subscriber behind an address that has none, and a reply field that appeared
 * because somebody ticked a box would be her writing an answer that goes nowhere, with nothing on
 * the screen to say so. See [SenderJudgement].
 */
val Message.canBeAnswered: Boolean
    get() = !isOutgoing && namesSomebodyToWriteTo

/**
 * Whether this message names somebody she could write to, whichever way it went.
 *
 * Everything [canBeAnswered] asks except who wrote it. A message of hers is not something to
 * *answer* — but it does say who she was talking to, and it says it in the same fields: her own
 * rows carry the other person in [Message.senderName], [Message.senderId] and
 * [Message.senderAddress], because that is the one thing a conversation of two people needs to
 * know. So the reply paths, which all read those three, work from one of hers unchanged.
 *
 * This exists for a conversation whose only message is hers — she wrote to somebody who has not
 * yet written back, and had no way to write a second time. There was nothing on the screen to
 * explain it: the reply field is present or absent, so an empty side of the conversation simply
 * ended it.
 *
 * A group of hers is deliberately not included. [Message.groupCanBeAnswered] is derived when a
 * message *arrives*, so her own row never carries it and a fan-out to a membership nobody
 * confirmed is exactly the partial delivery the group rule above exists to prevent.
 */
val Message.namesSomebodyToWriteTo: Boolean
    get() = (!isGroupMessage || groupCanBeAnswered) && fromTheAddress.canReply && when (channel) {
        MessageChannel.BRIDGE -> true
        MessageChannel.ON_DEVICE -> true
        MessageChannel.DEVICE -> MessageRouter.SMS_ENABLED
    }
