package ch.heuscher.gentleconnect.model

/**
 * Several pictures posted in one go, treated as the one thing they are.
 *
 * People do not post a photograph. They post the roll: read out of her phone, Martin Nauer put up
 * **35 pictures in nine seconds**, Alex Sommer 19 in a minute, and a dozen others between five and
 * thirteen. Each one arrived as its own status, so her post read "Martin Nauer — Photo" thirty-five
 * times over, with the same face and the same minute beside every line. Two screens' worth of one
 * man's afternoon, and nothing on any of the rows to tell it from the row above.
 *
 * It cost her more than tidiness. Home has room for two things at a time — see [Home.LIST_LENGTH] —
 * so a single burst took the whole section, and a letter from her son that arrived the same
 * afternoon was pushed off the screen by the thirty-fourth copy of a stranger's holiday.
 *
 * ### Where the line falls
 *
 * A burst is every picture, film (and words-only status) one person posts, until somebody else's
 * status or a letter lands between them in the Look feed. There is no time limit.
 *
 * A known consequence of this is that if a person posts multiple times across a day with nothing
 * breaking the run, they fold into one album. Katrin Brunner posted three times the same evening —
 * 17:36, 18:13, 18:33, each with its own caption, each about something different. These now become
 * one album, though each caption remains visible per page in the viewer.
 */
object StatusBurst {

    /**
     * Whether these two were put up in one go, [newer] being the later of the two.
     *
     * Only ever asked of two posts already next to each other in the feed. Anything at all
     * between them — a letter, somebody else's picture — and they are not adjacent, so the
     * question is never put and the order of her post is never rearranged to make a burst.
     */
    fun sameBurst(newer: Message, older: Message): Boolean {
        if (!newer.isStatus || !older.isStatus) return false
        val poster = posterOf(newer)
        if (poster.isEmpty() || poster != posterOf(older)) return false
        return true
    }

    /**
     * Who posted, as firmly as this message can say it.
     *
     * The address before the name, because a name is what the sender chose to call themselves and
     * two people are perfectly free to choose the same one. Falling back to the name is still
     * worth doing — an SMS-shaped status has no address at all — and the risk it carries is one
     * repeat too few in a list of holiday photographs, not a message going to the wrong person.
     */
    fun posterOf(message: Message): String {
        message.senderId?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { return it }
        message.senderAddress?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { return it }
        return message.senderName.trim().lowercase()
    }
}
