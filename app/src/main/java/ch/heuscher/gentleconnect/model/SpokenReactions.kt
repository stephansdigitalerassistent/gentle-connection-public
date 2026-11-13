package ch.heuscher.gentleconnect.model

/**
 * What the reading-aloud voice says about the marks on a message.
 *
 * The voice used to read the words and stop there, so everything anybody did about the message
 * was invisible to the one way she can take a message in without reading it. For somebody losing
 * her sight that is not a detail at the edge of the bubble: a heart from her son is the whole of
 * what he said, and read-aloud was leaving it out.
 *
 * **Exactly the marks the bubble is showing.** They are handed in already chosen rather than
 * worked out again from the column — hers picked out, the rest capped at [Reactions.MAX_KEPT] —
 * so what she hears and what she is looking at cannot come apart later.
 *
 * **Hers last, though, where the screen puts it first.** The bubble puts it first because that is
 * where the eye starts; a voice has no such place, and the last thing said is the thing that
 * stays. What she is listening for at the end of a message is whether she already answered it.
 *
 * **Words, not the pictures.** The marks are turned into their names here rather than left as
 * emoji for [Emoji.spell] to handle downstream, because an emoji this app has no word for is
 * left alone by that — correct on a screen, silence in a voice. A sentence that announced a
 * reaction and then said nothing at all would be worse than not mentioning it: she would be
 * waiting for something that is never coming. So a mark with no word is left out of the sentence,
 * and if none of them have words there is no sentence.
 */
object SpokenReactions {

    /**
     * The marks on one message, as sentences to append to whatever is being read.
     *
     * Empty when the message carries none, and empty when none of them has a word — the caller
     * appends this, so empty simply means the reading is the message alone.
     *
     * @param own hers, as [Reactions.ownOf] gives it, or null when she has not marked this one.
     * @param others everybody else's, already distinct and already capped for the screen.
     * @param name what the voice calls one emoji, normally [ch.heuscher.gentleconnect.speech.EmojiNames].
     * @param conjunction "and" / "und", from the app's own strings, for the last of several.
     * @param othersFormat one `%1$s`, filled with everybody else's marks: "Reaction: %1$s."
     * @param ownFormat one `%1$s`, filled with hers: "You answered with %1$s."
     */
    fun sentences(
        own: String?,
        others: List<String>,
        name: (String) -> String?,
        conjunction: String,
        othersFormat: String,
        ownFormat: String
    ): String {
        val otherWords = others.mapNotNull { Emoji.wordFor(it, name) }
        val ownWord = own?.let { Emoji.wordFor(it, name) }

        val sentences = mutableListOf<String>()
        if (otherWords.isNotEmpty()) {
            sentences += othersFormat.format(joined(otherWords, conjunction))
        }
        if (ownWord != null) {
            sentences += ownFormat.format(ownWord)
        }
        return sentences.joinToString(" ")
    }

    /**
     * "Herz", "Herz und Daumen hoch", "Herz, Danke und Daumen hoch".
     *
     * The same shape [ch.heuscher.gentleconnect.data.GroupDescription] uses for several people,
     * and for the same reason: a list read out as "Herz Daumen hoch Danke" is one long word to
     * somebody who is only listening.
     */
    private fun joined(words: List<String>, conjunction: String): String = when (words.size) {
        1 -> words.first()
        else -> "${words.dropLast(1).joinToString(", ")} $conjunction ${words.last()}"
    }
}
