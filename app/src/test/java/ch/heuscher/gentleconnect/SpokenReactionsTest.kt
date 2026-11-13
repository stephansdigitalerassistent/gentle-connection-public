package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Reactions
import ch.heuscher.gentleconnect.model.SpokenReactions
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the voice says about the marks on a message.
 *
 * The words here are the English ones from `strings.xml`; the formats are passed in, so these
 * tests are about which marks are spoken and in what order, not about the wording.
 */
class SpokenReactionsTest {

    private val heart = "❤"
    private val thumbsUp = "👍"
    private val laughing = "😂"
    private val hooray = "🎉"
    private val flower = "🌸"

    private val words = mapOf(
        heart to "heart",
        thumbsUp to "thumbs up",
        laughing to "laughing",
        hooray to "hooray",
        flower to "flower"
    )

    /**
     * The same two lines the bubble runs before it draws — hers picked out, the rest capped —
     * so that what these tests hand the voice is what is on the screen beside it.
     */
    private fun spoken(stored: String?): String {
        val own = Reactions.ownOf(stored)
        val othersCap = if (own != null) Reactions.MAX_KEPT - 1 else Reactions.MAX_KEPT
        return SpokenReactions.sentences(
            own = own,
            others = Reactions.othersOf(stored).take(othersCap),
            name = { words[it] },
            conjunction = "and",
            othersFormat = "Reaction: %1\$s.",
            ownFormat = "You answered with %1\$s."
        )
    }

    @Test
    fun `nothing on the message is nothing to say`() {
        assertEquals("", spoken(null))
        assertEquals("", spoken(""))
    }

    @Test
    fun `one mark from somebody else`() {
        val stored = Reactions.appliedBy(null, "daughter", heart)
        assertEquals("Reaction: heart.", spoken(stored))
    }

    @Test
    fun `several marks are a list, not a run of words`() {
        var stored = Reactions.appliedBy(null, "daughter", heart)
        stored = Reactions.appliedBy(stored, "son", thumbsUp)
        stored = Reactions.appliedBy(stored, "sister", laughing)
        assertEquals("Reaction: heart, thumbs up and laughing.", spoken(stored))
    }

    @Test
    fun `the same emoji from several people is said once`() {
        var stored = Reactions.appliedBy(null, "daughter", heart)
        stored = Reactions.appliedBy(stored, "son", heart)
        assertEquals("Reaction: heart.", spoken(stored))
    }

    @Test
    fun `hers is said last, and said as hers`() {
        var stored = Reactions.appliedBy(null, "daughter", heart)
        stored = Reactions.appliedBy(stored, Reactions.SELF, thumbsUp)
        assertEquals("Reaction: heart. You answered with thumbs up.", spoken(stored))
    }

    @Test
    fun `hers alone needs no sentence about anybody else`() {
        val stored = Reactions.appliedBy(null, Reactions.SELF, heart)
        assertEquals("You answered with heart.", spoken(stored))
    }

    @Test
    fun `no more marks are spoken than the bubble shows`() {
        var stored = Reactions.appliedBy(null, "a", heart)
        stored = Reactions.appliedBy(stored, "b", thumbsUp)
        stored = Reactions.appliedBy(stored, "c", laughing)
        stored = Reactions.appliedBy(stored, "d", hooray)
        stored = Reactions.appliedBy(stored, "e", flower)
        assertEquals("Reaction: heart, thumbs up, laughing and hooray.", spoken(stored))
    }

    @Test
    fun `hers takes one of the four, the same way the bubble gives it one`() {
        var stored = Reactions.appliedBy(null, "a", heart)
        stored = Reactions.appliedBy(stored, "b", thumbsUp)
        stored = Reactions.appliedBy(stored, "c", laughing)
        stored = Reactions.appliedBy(stored, "d", hooray)
        stored = Reactions.appliedBy(stored, Reactions.SELF, flower)
        assertEquals(
            "Reaction: heart, thumbs up and laughing. You answered with flower.",
            spoken(stored)
        )
    }

    /** A heart with a variation selector is the same heart, not an emoji with no word. */
    @Test
    fun `a decorated emoji keeps its word`() {
        val stored = Reactions.appliedBy(null, "daughter", "$heart️")
        assertEquals("Reaction: heart.", spoken(stored))
    }

    /**
     * The voice says nothing rather than announcing a mark and then falling silent — an emoji
     * with no word is left on the screen and left out of the reading.
     */
    @Test
    fun `an emoji with no word is left out`() {
        val stored = Reactions.appliedBy(null, "daughter", "🫠")
        assertEquals("", spoken(stored))
    }

    @Test
    fun `an emoji with no word does not take the others down with it`() {
        var stored = Reactions.appliedBy(null, "daughter", "🫠")
        stored = Reactions.appliedBy(stored, "son", heart)
        assertEquals("Reaction: heart.", spoken(stored))
    }

    /** A sequence — a family, a flag — is not one of the emoji the table names. */
    @Test
    fun `a compound sequence is not guessed at`() {
        val family = "👨‍👩‍👧"
        val stored = Reactions.appliedBy(null, "daughter", family)
        assertEquals("", spoken(stored))
    }

    /** Reactions stored before the column kept whose they were are still somebody's. */
    @Test
    fun `a mark from before the keys existed is still read`() {
        assertEquals("Reaction: heart.", spoken(heart))
    }
}
