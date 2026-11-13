package ch.heuscher.gentleconnect.model

/**
 * Which message in an open conversation a reply is addressed to.
 *
 * A conversation is a list; a reply needs one message out of it, because every reply path in the
 * app reads who to write to off the message it is given. Picking the wrong one is not a visible
 * mistake — it is a message that goes to somebody she was not talking to — which is why the
 * choice is here, where it can be tested, rather than inline in the screen that needs it.
 */
object ConversationReply {

    /**
     * @param conversation everything in the open conversation, oldest first.
     * @return the message a reply should be addressed to, or null when there is nobody to write
     *   to. Null means the reply affordance is absent altogether: no greyed-out field and no
     *   explanation beside it.
     */
    fun targetIn(conversation: List<Message>): Message? =
        // The newest she can actually answer, rather than simply the newest — a laundry's
        // automatic message arriving after her daughter's must not be what a reply is addressed
        // to.
        conversation.lastOrNull { it.canBeAnswered }
            // Failing that, the newest of her own that still names somebody. This is what lets
            // her write again to a person who has not written back: nothing here answers her own
            // message, it is read only for who is at the other end of it.
            ?: conversation.lastOrNull { it.isOutgoing && it.namesSomebodyToWriteTo }
}
