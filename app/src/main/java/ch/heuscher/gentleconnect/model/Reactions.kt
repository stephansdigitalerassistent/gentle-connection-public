package ch.heuscher.gentleconnect.model

/**
 * Everything somebody has put on one message, as one stored string.
 *
 * A person does not react once. Her daughter hearts a photograph and then, a minute later, adds a
 * laughing face to the same one — two separate things she did, and on every messenger she uses
 * both of them sit there. This app kept only the newest, so the second reaction erased the first,
 * and taking the second one back erased the reaction that was never withdrawn along with it. What
 * she saw was a heart that turned into a laugh and then into nothing, none of which had happened.
 *
 * **Still not a tally.** The old note on `StoredMessageEntity.reaction` was right about the thing
 * it was refusing: a row of six hearts from six people, with counts, is a counting exercise. This
 * keeps what somebody did and drops the arithmetic — no numbers, no names, no ordering by
 * popularity. Just, in the order they happened, the marks that are on the message now, [MAX_KEPT]
 * of them at the outside.
 *
 * **Kept by whose it is, shown without saying so.** Every mark records who made it. The earlier
 * version of this file recorded only the emoji and was honest about what that cost: in a group,
 * one person taking their heart back took the heart away even if somebody else still had one
 * there, and one person changing their mind overwrote everybody. That was not anonymity, it was
 * wrongness wearing anonymity's clothes. The name is a key, and a key only — it decides whose
 * mark to replace and whose to take back, and it never reaches the screen. Six people sending
 * the same heart is still one heart on her screen, because [of] hands out the distinct emoji and
 * nothing else. No numbers, no names, no ordering by popularity.
 *
 * **Except hers.** The one mark she is entitled to find again is her own, so it is kept apart
 * under [SELF] and drawn highlighted. That takes one bit — whether a reaction came from her —
 * not a name, which is why it costs the refusal above nothing.
 *
 * Stored in the single `reaction` column, [SEPARATOR] between marks and [FIELD_SEPARATOR] inside
 * one, so there is still no migration: a row written before any of this existed has no field
 * separator, reads back as the reaction it holds, and is filed under [UNKNOWN].
 */
object Reactions {

    /**
     * ASCII unit separator: one mark from the next. Never part of an emoji, never typed, and
     * invisible if one ever leaks onto the screen. A comma or a space would each be a candidate
     * for turning up inside a reaction one day — Threema carries an arbitrary string, not a
     * single code point.
     */
    const val SEPARATOR = "\u001F"

    /**
     * ASCII record separator: who made a mark, from the mark itself. Chosen for the reason
     * [SEPARATOR] was, and it has to be a different character: a sender is an arbitrary string
     * too, and the two must never be able to be read as each other.
     */
    const val FIELD_SEPARATOR = "\u001E"

    /**
     * Hers.
     *
     * A control character because it has to be something no messenger could ever hand over as a
     * real sender — a telephone number, a LID, a Threema identity. If a person's id could collide
     * with this, their reaction would be drawn as hers, which is a lie about what she did.
     */
    const val SELF = "\u0001"

    /**
     * A mark from before this file knew to ask.
     *
     * Every reaction stored before per-sender keys existed is one of these. They are shown like
     * any other — she has no idea the column changed — but they cannot be replaced or withdrawn
     * by name, because nothing ever recorded whose they were.
     */
    const val UNKNOWN = "\u0002"

    /**
     * How many are kept on one message.
     *
     * Her screen sets this, not the protocol. Four emoji at headline size is already a full line
     * on a phone held at arm's length; past that they would either wrap under the message or
     * shrink to something she cannot make out, and both are worse than losing the oldest mark on
     * a message somebody has reacted to five times.
     */
    const val MAX_KEPT = 4

    /**
     * How many are kept at all, which is not the same question as [MAX_KEPT].
     *
     * Her screen shows four. This is the column: enough that a large family group all reacting to
     * one photograph is still stored truthfully — so replacing and withdrawing keep working for
     * each of them — and bounded so one row cannot grow forever.
     */
    const val MAX_STORED_MARKS = 24

    /** One emoji, and whose it is. The sender is a key for this file's use, never for the screen. */
    data class Mark(val sender: String, val emoji: String)

    /** What is on the message now, oldest first. Empty when nothing is. */
    fun of(stored: String?): List<String> =
        marksOf(stored).map { it.emoji }.distinct()

    /**
     * Everything on the message and whose it is, oldest first.
     *
     * An entry with no [FIELD_SEPARATOR] is one written before this file kept the key, and is
     * filed under [UNKNOWN] rather than dropped — those are real reactions real people made.
     */
    fun marksOf(stored: String?): List<Mark> {
        if (stored.isNullOrBlank()) return emptyList()
        return stored.split(SEPARATOR).mapNotNull { entry ->
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val index = trimmed.indexOf(FIELD_SEPARATOR)
            if (index >= 0) {
                val sender = trimmed.substring(0, index)
                val emoji = trimmed.substring(index + FIELD_SEPARATOR.length).trim()
                if (emoji.isNotEmpty()) Mark(sender, emoji) else null
            } else {
                Mark(UNKNOWN, trimmed)
            }
        }
    }

    /**
     * How marks go back into the column. Null for none, so the column is empty rather than blank.
     *
     * Over [MAX_STORED_MARKS] the oldest go. That cap is about the column, not her screen — see
     * [MAX_KEPT] for what she actually sees — and exists so a group of forty reacting to the same
     * photograph cannot grow one row without bound.
     */
    fun storeMarks(marks: List<Mark>): String? =
        marks.filter { it.emoji.isNotBlank() }
            .takeLast(MAX_STORED_MARKS)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(SEPARATOR) { "${it.sender}$FIELD_SEPARATOR${it.emoji}" }

    /**
     * This is now the whole of what that person has on the message.
     *
     * For the transports where somebody has one slot rather than a set: WhatsApp, where changing
     * your reaction replaces it and no withdrawal of the old one is ever sent, and Threema's
     * older acknowledge/decline, which is that same single slot wearing an emoji's clothes.
     * Accumulating those would leave her looking at every reaction a person had ever changed
     * their mind about — and keying them by message rather than by person, which is what this
     * used to do, meant her daughter's laugh erased her son's heart.
     *
     * A blank emoji takes theirs off and puts nothing in its place.
     */
    fun replacedBy(stored: String?, sender: String, emoji: String): String? {
        val existing = marksOf(stored)
        val withoutSender = existing.filterNot { it.sender == sender }
        if (emoji.isBlank()) return storeMarks(withoutSender)
        return storeMarks(withoutSender + Mark(sender, emoji))
    }

    /**
     * Somebody added one to what they already have there.
     *
     * A person does not react once: her daughter hearts a photograph and then, a minute later,
     * adds a laughing face to the same one — two separate things she did, and on the messengers
     * that allow it both of them sit there. The same emoji arriving twice from the same person is
     * not two marks, so it changes nothing; the same emoji from two people is one mark on her
     * screen and two here, which is the whole point of keeping the key.
     */
    fun appliedBy(stored: String?, sender: String, emoji: String): String? {
        if (emoji.isBlank()) return storeMarks(marksOf(stored))
        val existing = marksOf(stored)
        if (existing.any { it.sender == sender && it.emoji == emoji }) return storeMarks(existing)
        return storeMarks(existing + Mark(sender, emoji))
    }

    /**
     * Somebody took one back — theirs, and only theirs.
     *
     * Where the withdrawal names an emoji, that one of theirs goes and the rest of what they put
     * on the message stays. Where it names none, which is what WhatsApp sends, everything of
     * theirs goes and nobody else is touched. Before any of this was keyed, a withdrawal cleared
     * the column outright: her daughter taking back a laugh removed a heart nobody had touched.
     */
    fun withdrawnBy(stored: String?, sender: String, emoji: String?): String? {
        val existing = marksOf(stored)
        if (existing.isEmpty()) return null
        val remaining = if (emoji.isNullOrBlank()) {
            existing.filterNot { it.sender == sender }
        } else {
            existing.filterNot { it.sender == sender && it.emoji == emoji }
        }
        return storeMarks(remaining)
    }

    /**
     * Hers, if she has put one there.
     *
     * Drawn apart from the rest and never dropped by [MAX_KEPT]: the mark she is most likely to
     * be looking for on a message is the evidence that she answered it. Several under [SELF] is
     * something no transport can currently produce, and the newest wins if one ever does.
     */
    fun ownOf(stored: String?): String? =
        marksOf(stored).lastOrNull { it.sender == SELF }?.emoji

    /**
     * Everybody else's, in the order the first of each arrived.
     *
     * Distinct, so a family all sending the same heart is one heart — see the note at the top of
     * this file about what is deliberately not counted here.
     */
    fun othersOf(stored: String?): List<String> =
        marksOf(stored).filterNot { it.sender == SELF }.map { it.emoji }.distinct()
}
