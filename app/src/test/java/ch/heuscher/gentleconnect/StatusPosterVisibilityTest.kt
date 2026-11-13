package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.StatusBroadcast
import ch.heuscher.gentleconnect.model.StatusPosterVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPosterVisibilityTest {

    private val noon = 1_700_000_000_000L
    private val DAY = 24L * 60 * 60 * 1000

    private fun status(id: String, from: String, at: Long, digits: String = "4179" + from.length + "000") =
        Message(
            id = id,
            senderName = from,
            senderId = digits,
            contactId = "c-$from",
            text = "[Foto]",
            timestamp = at,
            sourcePackage = "",
            groupIdentity = GroupIdentity.of("whatsapp-group", StatusBroadcast.JID)
        )

    private fun letter(id: String, from: String, at: Long) =
        Message(
            id = id,
            senderName = from,
            senderId = "41790000000",
            contactId = "c-$from",
            text = "Hallo",
            timestamp = at,
            sourcePackage = ""
        )

    @Test
    fun `offers the five most recent and the five most frequent posters`() {
        val posts = mutableListOf<Message>()
        // Ten posters, one picture each, newest first: P0 is the newest.
        (0 until 10).forEach { i -> posts += status("r$i", "P$i", noon - i * 1000, digits = "1$i") }
        // An old poster who posts a lot.
        (0 until 30).forEach { i -> posts += status("m$i", "Markus", noon - 1_000_000 - i, digits = "99") }

        // Long after all of it, so the 30-day window adds nobody beyond the five most recent.
        val known = StatusPosterVisibility.known(posts, now = noon + 365 * DAY)
        val names = known.map { it.name }

        assertEquals(listOf("P0", "P1", "P2", "P3", "P4"), names.take(5))
        assertTrue("Markus" in names)
        assertEquals(30, known.first { it.name == "Markus" }.count)
        // The four other "most frequent" are single-post ties, which go to the most recent — the
        // same five already offered. So six switches, not ten, and nobody older than P4.
        assertEquals(6, known.size)
        assertFalse("P5" in names)
    }

    @Test
    fun `everybody who posted in the last thirty days is offered, not only the five newest`() {
        // Eight posters in the last week, newest first, and one who stopped forty days ago.
        val posts = (0 until 8).map { i -> status("r$i", "P$i", noon - i * DAY / 2, digits = "1$i") } +
            status("old", "Gone", noon - 40 * DAY, digits = "77")

        val names = StatusPosterVisibility.known(posts, now = noon).map { it.name }

        // All eight, newest first. The old one-picture poster is neither recent nor, on a tie,
        // frequent, so has no switch.
        assertEquals((0 until 8).map { "P$it" }, names)
    }

    @Test
    fun `a switched-off poster keeps a switch even when no longer recent or frequent`() {
        val posts = (0 until 12).map { i -> status("r$i", "P$i", noon - i * 1000, digits = "1$i") }
        val oldKey = StatusPosterVisibility.keyOf(posts.last())

        val known = StatusPosterVisibility.known(posts, hidden = setOf(oldKey), now = noon + 365 * DAY)

        assertTrue(known.any { it.key == oldKey })
    }

    @Test
    fun `hiding takes away only that poster's statuses and never a letter`() {
        val markus = status("s1", "Markus", noon, digits = "41791234567")
        val anna = status("s2", "Anna", noon - 1, digits = "41797654321")
        val fromMarkus = letter("l1", "Markus", noon).copy(senderId = "41791234567")

        val hidden = StatusPosterVisibility.hiddenKeys(listOf(" 41791234567 ", null, ""))

        assertEquals(listOf(anna), StatusPosterVisibility.visible(listOf(markus, anna), hidden))
        assertFalse(StatusPosterVisibility.isHidden(fromMarkus, hidden))
    }

    @Test
    fun `nothing hidden is everything shown`() {
        val posts = listOf(status("s1", "A", noon), status("s2", "B", noon - 1))
        assertEquals(posts, StatusPosterVisibility.visible(posts, emptySet()))
    }
}
