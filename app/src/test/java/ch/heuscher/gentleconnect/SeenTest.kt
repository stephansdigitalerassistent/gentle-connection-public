package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.Seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What opening a conversation clears.
 *
 * Written after the card at the top of her home screen would not go away: it announced a
 * neighbour who had written the day before, and it kept announcing her after every one of those
 * messages had been read on screen. Opening had marked only the newest.
 */
class SeenTest {

    private var clock = 1_700_000_000_000L

    private fun wrote(text: String, unread: Boolean = true) = Message(
        id = "m${clock--}",
        senderName = "Elizabeth",
        senderAddress = "+41791234567",
        text = text,
        timestamp = clock,
        sourcePackage = "",
        isRead = !unread,
        channel = MessageChannel.ON_DEVICE
    )

    @Test
    fun `opening clears everything unread, not only the message she tapped`() {
        val yesterday = wrote("Buenas tardes")
        val newest = wrote("disculpeme")

        val cleared = Seen.whatOpeningClears(newest, listOf(yesterday, newest))

        assertEquals(setOf(yesterday.id, newest.id), cleared.map { it.id }.toSet())
    }

    @Test
    fun `what she has already read is left alone`() {
        // Not an optimisation: marking sends a read receipt to whoever wrote, so re-marking an
        // old message every time she reopens the conversation would misreport when she looked.
        val old = wrote("from last week", unread = false)
        val newest = wrote("today")

        val cleared = Seen.whatOpeningClears(newest, listOf(old, newest))

        assertEquals(listOf(newest.id), cleared.map { it.id })
    }

    @Test
    fun `the tapped message is cleared even before the list knows about it`() {
        // The first open can run before the flow the conversation is drawn from has caught up.
        val newest = wrote("just arrived")

        val cleared = Seen.whatOpeningClears(newest, emptyList())

        assertEquals(listOf(newest.id), cleared.map { it.id })
    }

    @Test
    fun `nothing is marked twice`() {
        // It appears both as the tapped message and in the list; a receipt must go out once.
        val newest = wrote("hello")

        val cleared = Seen.whatOpeningClears(newest, listOf(newest))

        assertEquals(1, cleared.size)
    }

    @Test
    fun `a conversation she has read through gives nothing to do`() {
        val first = wrote("one", unread = false)
        val second = wrote("two", unread = false)

        assertTrue(Seen.whatOpeningClears(second, listOf(first, second)).isEmpty())
    }
}
