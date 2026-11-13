package ch.heuscher.gentleconnect.model

/**
 * Turns emoji into spoken words for text-to-speech.
 *
 * Today the read-aloud voice says nothing at all where an emoji is. A message that is only a
 * thumbs-up is read as silence, which is indistinguishable from the button being broken — and a
 * thumbs-up from her daughter is a whole message.
 *
 * An emoji the app has a word for is replaced by that word with space around it; an emoji it has
 * no word for is left untouched so nothing is silently deleted. Variation selectors and zero-width
 * joiners are skipped so modified glyphs resolve to their base emoji word.
 */
object Emoji {
    private const val VARIATION_SELECTOR_16 = 0xFE0F
    private const val ZERO_WIDTH_JOINER = 0x200D

    /** Above here a code point is an emoji in its own right; below, it may be a symbol. */
    private const val FIRST_PICTURE_CODE_POINT = 0x1F000

    /**
     * The one word for a single emoji, or null when there is not one.
     *
     * [spell] leaves an emoji it has no word for standing in the text, which is right when the
     * result is going to be read alongside the sender's own words — nothing is silently deleted.
     * It is wrong when the emoji is the whole of what is being said, as a reaction is: the voice
     * would announce a mark and then say nothing. This answers the other question — "is there a
     * word for this at all" — so a caller can leave out what it cannot name. See [SpokenReactions].
     *
     * Modifiers are skipped as in [spell], so "❤️" and "❤" are the same heart. A compound
     * sequence — a family, a flag, an emoji carrying a skin tone — has no word and is not guessed
     * at, for the reason set out in [ch.heuscher.gentleconnect.speech.EmojiNames].
     */
    /**
     * The same emoji, drawn as a picture rather than as a printer's symbol.
     *
     * A heart, a sun and an envelope are older characters than emoji are, and a font is entitled
     * to draw them as thin black glyphs unless the variation selector says otherwise. On a row of
     * faces she is choosing from, one of them coming out as line art is the one she will not press.
     *
     * Everything above [FIRST_PICTURE_CODE_POINT] was born an emoji and is left alone. The
     * selector on something that is already a picture changes nothing, so the rule is allowed to
     * be this blunt.
     */
    fun asPicture(emoji: String): String {
        if (emoji.isEmpty()) return emoji
        val codePoint = emoji.codePointAt(0)
        if (Character.charCount(codePoint) != emoji.length) return emoji
        if (codePoint >= FIRST_PICTURE_CODE_POINT) return emoji
        return emoji + String(Character.toChars(VARIATION_SELECTOR_16))
    }

    fun wordFor(emoji: String, name: (String) -> String?): String? {
        var found: String? = null
        var i = 0
        while (i < emoji.length) {
            val codePoint = emoji.codePointAt(i)
            i += Character.charCount(codePoint)
            if (codePoint == VARIATION_SELECTOR_16 || codePoint == ZERO_WIDTH_JOINER) continue
            // A second code point with anything to say means this is a sequence, not an emoji
            // the table knows.
            if (found != null) return null
            found = name(String(Character.toChars(codePoint))) ?: return null
        }
        return found
    }

    fun spell(text: String, name: (String) -> String?): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        val len = text.length
        while (i < len) {
            val codePoint = text.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            if (codePoint == VARIATION_SELECTOR_16 || codePoint == ZERO_WIDTH_JOINER) {
                i += charCount
                continue
            }
            val cpString = String(Character.toChars(codePoint))
            val word = name(cpString)
            if (word != null) {
                sb.append(' ').append(word).append(' ')
            } else {
                sb.append(cpString)
            }
            i += charCount
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
