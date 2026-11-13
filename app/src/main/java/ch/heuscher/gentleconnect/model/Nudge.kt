package ch.heuscher.gentleconnect.model

/**
 * Single nudge prompt representing the single most important unread event on the home screen.
 */
sealed class Nudge {
    abstract val timestamp: Long
    abstract val senderName: String

    data class MessageNudge(
        val message: Message
    ) : Nudge() {
        override val timestamp: Long get() = message.timestamp

        /**
         * Who wrote, not what they wrote to.
         *
         * The card at the top of her screen answers one question — who is asking for her — and in
         * a group the answer is her daughter, not "Familie Hartmann". The list behind it still
         * names the group, because a list is about conversations and this is about a person.
         *
         * The group is the fallback and not the first choice, which is the part worth keeping:
         * where nobody has managed to resolve the sender, naming the group is far better than
         * putting a Threema identity in front of her. That happened once and is what
         * [PersonName.isUnresolved] is asked about here.
         */
        override val senderName: String get() =
            if (PersonName.isUnresolved(message.senderName, message.senderId)) {
                message.displayName
            } else {
                message.senderName
            }
    }

    data class MissedCallNudge(
        val contactName: String,
        override val timestamp: Long,
        /**
         * The number that rang, for finding the person behind it — see [Home.callBack].
         *
         * Kept beside the name rather than instead of it, because the two answer different
         * questions. The name is what she reads; the number is what identifies a telephone. Blank
         * when the missed call came from a message rather than from the telephone's own log, and
         * blank when the caller withheld it.
         */
        val number: String = "",
        /**
         * The message it arrived as, when it came through a messenger rather than the telephone.
         *
         * Such a call is not in the telephone's record, so the calls tab has nothing to show for
         * it and leaving that tab cannot clear it. It is a message, and reading it is what clears
         * it — the card opens the conversation instead.
         */
        val message: Message? = null
    ) : Nudge() {
        override val senderName: String get() = contactName
    }
}
