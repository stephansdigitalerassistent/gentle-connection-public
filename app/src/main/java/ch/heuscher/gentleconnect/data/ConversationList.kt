package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.isSameMessageAs

/**
 * One row per conversation, showing the newest thing said in it — by either of them.
 *
 * Found on the phone. She answered the family group, went back, and the row still read
 * "Yesterday · Guten Abend zusammen": the list was built from messages that had *arrived*, so her
 * own reply was nowhere in it. From where she was standing the message she had just sent had not
 * happened. That is the worst possible thing for this list to say, and it says it silently.
 *
 * The list was also one row per message rather than per conversation, which is why it needed a
 * rule against her carrier's four identical tariff texts occupying four rows. Newest-per-
 * conversation subsumes that rule and is the thing she already expects a list of messages to be.
 *
 * Pure, and given the conversation key as a function, because working it out needs her address
 * book and her address book lives in the view model.
 */
object ConversationList {

    /**
     * Newest first, one row per conversation.
     *
     * Messages with no conversation of their own — an SMS from a name rather than a number, which
     * has no telephone behind it to key on — fall back to sender-and-text. That keeps the old
     * behaviour exactly where the old behaviour was needed: four identical roaming texts still
     * collapse to one row, and two different messages from the same service still get one row
     * each, because there is no conversation to open for either of them.
     */
    fun newestPerConversation(
        messages: List<Message>,
        conversationIdOf: (Message) -> String?
    ): List<Message> =
        messages
            .sortedByDescending { it.timestamp }
            .distinctBy { message ->
                conversationIdOf(message) ?: "${message.senderName} ${message.text}"
            }

    /**
     * Two records of the same conversation, joined so that a message appears once.
     *
     * A text message she sends is written down twice, and both records are wanted. The app keeps
     * its own ([recorded]) — the copy that carries whether it actually went, which is what draws it
     * as unfinished and what a long press resends. The telephone's own sent box gets the other,
     * because a message this app does not file there has never been sent as far as the rest of the
     * phone is concerned: a caregiver looking at her phone in the ordinary messages app would find
     * her side of every conversation missing. That second copy comes back on the next launch
     * through the SMS import ([alsoKnown]), and without this the two sit in the thread one under
     * the other, identical, as though she had written it twice.
     *
     * [recorded] wins every collision, for the reason above: it is the copy that knows what became
     * of the message.
     */
    fun joined(recorded: List<Message>, alsoKnown: List<Message>): List<Message> {
        if (recorded.isEmpty()) return alsoKnown
        val joined = ArrayList<Message>(recorded.size + alsoKnown.size)
        joined += recorded
        alsoKnown.forEach { candidate ->
            if (recorded.none { it.isSameMessageAs(candidate) }) joined += candidate
        }
        return joined
    }
}
