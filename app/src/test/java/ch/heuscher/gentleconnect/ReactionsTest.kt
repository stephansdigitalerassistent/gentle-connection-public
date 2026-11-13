package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Reactions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What is on a message when one person reacts more than once, and how per-sender reactions
 * preserve truthfulness without turning the screen into a counted tally.
 */
class ReactionsTest {

    @Test
    fun `legacy bare-emoji rows still read`() {
        val legacySingle = "❤️"
        assertEquals(listOf("❤️"), Reactions.of(legacySingle))
        assertEquals(listOf(Reactions.Mark(Reactions.UNKNOWN, "❤️")), Reactions.marksOf(legacySingle))
        assertNull(Reactions.ownOf(legacySingle))
        assertEquals(listOf("❤️"), Reactions.othersOf(legacySingle))

        val legacyMulti = "❤️\u001F😂\u001F👍"
        assertEquals(listOf("❤️", "😂", "👍"), Reactions.of(legacyMulti))
        assertEquals(
            listOf(
                Reactions.Mark(Reactions.UNKNOWN, "❤️"),
                Reactions.Mark(Reactions.UNKNOWN, "😂"),
                Reactions.Mark(Reactions.UNKNOWN, "👍")
            ),
            Reactions.marksOf(legacyMulti)
        )
        assertNull(Reactions.ownOf(legacyMulti))
        assertEquals(listOf("❤️", "😂", "👍"), Reactions.othersOf(legacyMulti))
    }

    @Test
    fun `nothing at all is an empty list, not a blank one`() {
        assertEquals(emptyList<String>(), Reactions.of(null))
        assertEquals(emptyList<String>(), Reactions.of(""))
        assertEquals(emptyList<String>(), Reactions.of("   "))
        assertEquals(emptyList<Reactions.Mark>(), Reactions.marksOf(null))
        assertEquals(emptyList<Reactions.Mark>(), Reactions.marksOf(""))
        assertEquals(emptyList<Reactions.Mark>(), Reactions.marksOf("   "))
        assertNull(Reactions.ownOf(null))
        assertEquals(emptyList<String>(), Reactions.othersOf(null))
        assertNull(Reactions.storeMarks(emptyList()))
    }

    @Test
    fun `two people with the same emoji show as one mark`() {
        var stored: String? = null
        stored = Reactions.appliedBy(stored, "alice", "❤️")
        stored = Reactions.appliedBy(stored, "bob", "❤️")

        assertEquals(listOf("❤️"), Reactions.of(stored))
        assertEquals(2, Reactions.marksOf(stored).size)
        assertEquals(
            listOf(
                Reactions.Mark("alice", "❤️"),
                Reactions.Mark("bob", "❤️")
            ),
            Reactions.marksOf(stored)
        )
    }

    @Test
    fun `one person changing their emoji replaces only theirs`() {
        var stored: String? = null
        stored = Reactions.replacedBy(stored, "alice", "❤️")
        stored = Reactions.replacedBy(stored, "bob", "👍")

        // Alice changes her reaction to 😂
        stored = Reactions.replacedBy(stored, "alice", "😂")

        assertEquals(listOf("👍", "😂"), Reactions.of(stored))
        assertEquals(
            listOf(
                Reactions.Mark("bob", "👍"),
                Reactions.Mark("alice", "😂")
            ),
            Reactions.marksOf(stored)
        )
    }

    @Test
    fun `a withdrawal naming no emoji removes only that sender's`() {
        var stored: String? = null
        stored = Reactions.appliedBy(stored, "alice", "❤️")
        stored = Reactions.appliedBy(stored, "alice", "😂")
        stored = Reactions.appliedBy(stored, "bob", "👍")

        // WhatsApp-style withdrawal: names no emoji (null or blank)
        val leftNull = Reactions.withdrawnBy(stored, "alice", null)
        assertEquals(listOf("👍"), Reactions.of(leftNull))
        assertEquals(listOf(Reactions.Mark("bob", "👍")), Reactions.marksOf(leftNull))

        val leftBlank = Reactions.withdrawnBy(stored, "alice", "")
        assertEquals(listOf("👍"), Reactions.of(leftBlank))
        assertEquals(listOf(Reactions.Mark("bob", "👍")), Reactions.marksOf(leftBlank))
    }

    @Test
    fun `a Threema-style withdrawal naming an emoji leaves that person's other mark`() {
        var stored: String? = null
        stored = Reactions.appliedBy(stored, "alice", "❤️")
        stored = Reactions.appliedBy(stored, "alice", "😂")
        stored = Reactions.appliedBy(stored, "bob", "👍")

        val left = Reactions.withdrawnBy(stored, "alice", "❤️")

        assertEquals(listOf("😂", "👍"), Reactions.of(left))
        assertEquals(
            listOf(
                Reactions.Mark("alice", "😂"),
                Reactions.Mark("bob", "👍")
            ),
            Reactions.marksOf(left)
        )
    }

    @Test
    fun `hers is separable via ownOf and othersOf`() {
        var stored: String? = null
        stored = Reactions.appliedBy(stored, "alice", "👍")
        stored = Reactions.appliedBy(stored, Reactions.SELF, "❤️")
        stored = Reactions.appliedBy(stored, "bob", "🙏")

        assertEquals("❤️", Reactions.ownOf(stored))
        assertEquals(listOf("👍", "🙏"), Reactions.othersOf(stored))
        assertEquals(listOf("👍", "❤️", "🙏"), Reactions.of(stored))
    }

    @Test
    fun `ownOf defensively returns the last mark under SELF if multiple exist`() {
        var stored: String? = null
        stored = Reactions.appliedBy(stored, Reactions.SELF, "❤️")
        stored = Reactions.appliedBy(stored, Reactions.SELF, "😂")

        assertEquals("😂", Reactions.ownOf(stored))
    }

    @Test
    fun `the 24-mark storage cap drops oldest first`() {
        var stored: String? = null
        for (i in 1..26) {
            val sender = "user%02d".format(i)
            val emoji = "e%02d".format(i)
            stored = Reactions.appliedBy(stored, sender, emoji)
        }

        val marks = Reactions.marksOf(stored)
        assertEquals(Reactions.MAX_STORED_MARKS, marks.size)
        assertEquals(24, marks.size)

        // The first two (user01, user02) must have been dropped
        assertEquals("user03", marks.first().sender)
        assertEquals("e03", marks.first().emoji)
        assertEquals("user26", marks.last().sender)
        assertEquals("e26", marks.last().emoji)
    }

    @Test
    fun `the same sender applying the same reaction twice is idempotent`() {
        val stored = Reactions.appliedBy(Reactions.appliedBy(null, "alice", "❤️"), "alice", "❤️")

        assertEquals(listOf(Reactions.Mark("alice", "❤️")), Reactions.marksOf(stored))
        assertEquals(listOf("❤️"), Reactions.of(stored))
    }

    @Test
    fun `taking back the only reaction leaves the message unmarked`() {
        val one = Reactions.appliedBy(null, "alice", "❤️")

        assertNull(Reactions.withdrawnBy(one, "alice", "❤️"))
    }

    @Test
    fun `taking back something that was never there changes nothing`() {
        val one = Reactions.appliedBy(null, "alice", "❤️")

        assertEquals(listOf("❤️"), Reactions.of(Reactions.withdrawnBy(one, "alice", "😂")))
        assertEquals(listOf("❤️"), Reactions.of(Reactions.withdrawnBy(one, "bob", "❤️")))
    }

    @Test
    fun `an empty emoji does not disturb existing reactions`() {
        val one = Reactions.appliedBy(null, "alice", "❤️")

        assertEquals(listOf("❤️"), Reactions.of(Reactions.appliedBy(one, "alice", "")))
        assertEquals(listOf("❤️"), Reactions.of(Reactions.appliedBy(one, "alice", "   ")))
    }

    @Test
    fun `replacedBy with blank emoji removes that sender's marks`() {
        var stored: String? = null
        stored = Reactions.appliedBy(stored, "alice", "❤️")
        stored = Reactions.appliedBy(stored, "bob", "👍")

        stored = Reactions.replacedBy(stored, "alice", "")

        assertEquals(listOf("👍"), Reactions.of(stored))
        assertEquals(listOf(Reactions.Mark("bob", "👍")), Reactions.marksOf(stored))
    }
}

