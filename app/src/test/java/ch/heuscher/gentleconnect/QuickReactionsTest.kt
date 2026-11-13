package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Emoji
import ch.heuscher.gentleconnect.model.QuickReactions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The row under a message, and what turning the chooser on costs.
 */
class QuickReactionsTest {

    @Test
    fun `off is the five faces`() {
        assertEquals(QuickReactions.FACES, QuickReactions.faces(canChooseEmoji = false))
        assertEquals(QuickReactions.SLOTS, QuickReactions.faces(canChooseEmoji = false).size)
    }

    /** Four faces and the door: the row is the same width either way. */
    @Test
    fun `on leaves room for the door`() {
        assertEquals(QuickReactions.SLOTS - 1, QuickReactions.faces(canChooseEmoji = true).size)
    }

    /**
     * The heart she reaches for without looking has to stay under the same thumb. Only the last
     * face goes; nothing before it moves.
     */
    @Test
    fun `the first four never move`() {
        val chosen = QuickReactions.faces(canChooseEmoji = true)
        assertEquals(QuickReactions.FACES.take(chosen.size), chosen)
    }

    @Test
    fun `a face that is a printer's symbol is asked to be a picture`() {
        // ❤ U+2764 is a dingbat older than emoji, and is drawn as line art without the selector.
        assertEquals("❤️", Emoji.asPicture("❤"))
        assertEquals("☀️", Emoji.asPicture("☀"))
    }

    @Test
    fun `a face that was always a picture is left alone`() {
        assertEquals("👍", Emoji.asPicture("👍"))
        assertEquals("🎉", Emoji.asPicture("🎉"))
    }

    @Test
    fun `something already carrying the selector is not given a second one`() {
        assertEquals("❤️", Emoji.asPicture("❤️"))
    }

    @Test
    fun `nothing is nothing`() {
        assertEquals("", Emoji.asPicture(""))
    }

    /** Whatever the selector does to it, the voice still has to be able to name it. */
    @Test
    fun `a picture-form face keeps its word`() {
        val names = mapOf("❤" to "heart")
        assertEquals("heart", Emoji.wordFor(Emoji.asPicture("❤"), names::get))
    }

    @Test
    fun `every face in the row is one the voice can name`() {
        val names = mapOf(
            "❤" to "heart",
            "👍" to "thumbs up",
            "😊" to "smiling face",
            "🙏" to "thank you",
            "😂" to "laughing"
        )
        QuickReactions.FACES.forEach { face ->
            assertTrue(face, Emoji.wordFor(face, names::get) != null)
        }
    }
}
