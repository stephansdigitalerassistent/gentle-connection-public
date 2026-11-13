package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Home
import ch.heuscher.gentleconnect.model.LookFeed
import ch.heuscher.gentleconnect.model.LookItem
import ch.heuscher.gentleconnect.model.Mail
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.StatusBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What arrives for her to look at, and what must never be mistaken for a message.
 *
 * Found on the phone: 54 statuses against 23 real messages in one week, all of them filed as if
 * somebody had written to her, and the card at the top of her home screen announcing one of them
 * as "David Adler wrote a message".
 */
class LookFeedTest {

    private fun status(id: String, at: Long, from: String = "Annette") = Message(
        id = id,
        senderName = from,
        text = "[Foto]",
        timestamp = at,
        sourcePackage = "",
        // A real contact, so that these tests prove the status rule rather than accidentally
        // passing because SenderKind had already written the sender off as an organisation.
        contactId = "c-$from",
        groupIdentity = GroupIdentity.of("whatsapp-group", StatusBroadcast.JID)
    )

    private fun message(id: String, at: Long) = Message(
        id = id,
        senderName = "Julia",
        text = "Bin um vier da",
        timestamp = at,
        sourcePackage = "",
        contactId = "c-julia"
    )

    private fun letter(id: String, at: Long) = Mail(
        id = id,
        senderName = "Dr. Meier",
        senderAddress = "praxis@example.ch",
        subject = "Termin",
        snippet = "…",
        timestamp = at
    )

    @Test
    fun `a status is recognised however it is spelled`() {
        assertTrue(StatusBroadcast.isStatus(GroupIdentity.of("whatsapp-group", "status@broadcast")))
        assertTrue(StatusBroadcast.isStatus(GroupIdentity.of("whatsapp-group", "STATUS@BROADCAST")))
        assertFalse(StatusBroadcast.isStatus(GroupIdentity.of("whatsapp-group", "123-456@g.us")))
        assertFalse(StatusBroadcast.isStatus(null))
    }

    @Test
    fun `a status is not a group message`() {
        // Everything that asks "is this a group?" is asking "does a reply go to several people?".
        // For a status there is no reply at all.
        assertFalse(status("s1", 1_000).isGroupMessage)
        assertTrue(status("s1", 1_000).isStatus)
    }

    @Test
    fun `a status never reaches her messages or the card at the top`() {
        val mixed = listOf(status("s1", 3_000), message("m1", 2_000), status("s2", 1_000))

        assertEquals(listOf("m1"), Home.messages(mixed).map { it.id })
        assertEquals("m1", Home.worthANudge(mixed)?.id)
    }

    @Test
    fun `nothing but statuses means nothing to announce`() {
        // The common case on her phone, and the quiet one: an empty top of screen.
        assertNull(Home.worthANudge(listOf(status("s1", 3_000), status("s2", 1_000))))
        assertTrue(Home.messages(listOf(status("s1", 3_000))).isEmpty())
    }

    @Test
    fun `pictures on the home screen are newest first, not unread first`() {
        val posts = listOf(status("old", 1_000), status("new", 9_000), status("mid", 5_000))
        // Newest first, and cut to what home holds — so the oldest is the one that falls off,
        // never the newest.
        assertEquals(listOf("new", "mid"), Home.statuses(posts).map { it.id })
    }

    @Test
    fun `letters and pictures share one shelf, newest first`() {
        val feed = LookFeed.build(
            mails = listOf(letter("l1", 2_000), letter("l2", 8_000)),
            statuses = listOf(status("s1", 5_000))
        )

        assertEquals(3, feed.size)
        assertEquals(listOf(8_000L, 5_000L, 2_000L), feed.map { it.timestamp })
        assertTrue(feed[0] is LookItem.Letter)
        assertTrue(feed[1] is LookItem.Picture)
    }

    @Test
    fun `a conversation handed to the shelf by mistake does not land on it`() {
        val feed = LookFeed.build(mails = emptyList(), statuses = listOf(message("m1", 1_000)))
        assertTrue(feed.isEmpty())
    }

    @Test
    fun `every thing on the shelf is named, and no two share a name`() {
        // The list keeps her place by these. A letter and a picture that answered to the same
        // name would put her back on the wrong one — and the two come from a mailbox and a
        // messenger, which have never agreed on how to number anything.
        val feed = LookFeed.build(
            mails = listOf(letter("7", 2_000)),
            statuses = listOf(status("7", 5_000))
        )

        assertEquals(2, feed.map { it.id }.toSet().size)
        assertEquals("picture:7", feed[0].id)
        assertEquals("letter:7", feed[1].id)
    }

    @Test
    fun `a thing keeps its name when post arrives above it`() {
        // What she was looking at must still answer to the same name after something new lands,
        // because that name is the only reason the list can put her back on it.
        val before = LookFeed.build(mails = listOf(letter("l1", 2_000)), statuses = emptyList())
        val after = LookFeed.build(
            mails = listOf(letter("l1", 2_000), letter("l2", 9_000)),
            statuses = listOf(status("s1", 8_000))
        )

        assertEquals(before[0].id, after.last().id)
    }
}
