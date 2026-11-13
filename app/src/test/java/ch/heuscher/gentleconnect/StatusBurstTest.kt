package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.LookFeed
import ch.heuscher.gentleconnect.model.LookItem
import ch.heuscher.gentleconnect.model.Mail
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.StatusBroadcast
import ch.heuscher.gentleconnect.model.StatusBurst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pictures put up in one go, counted as one thing.
 *
 * Every number here was read out of her phone: 575 statuses, of which Martin Nauer posted 35 in
 * nine seconds and Alex Sommer 19 in a minute, while Katrin Brunner posted three times the same
 * evening — 17:36, 18:13, 18:33 — about three different things.
 */
class StatusBurstTest {

    private val minute = 60_000L
    private val noon = 1_700_000_000_000L

    private fun status(
        id: String,
        at: Long,
        from: String = "Martin Nauer",
        text: String = "[Foto]",
        photo: String? = null
    ) =
        Message(
            photoPath = photo,
            id = id,
            senderName = from,
            // Per poster, because that is what tells two of them apart — see posterOf.
            senderAddress = "+4179" + from.hashCode().toString().takeLast(6),
            contactId = "c-$from",
            text = text,
            timestamp = at,
            sourcePackage = "",
            groupIdentity = GroupIdentity.of("whatsapp-group", StatusBroadcast.JID)
        )

    private fun pictures(feed: List<LookItem>) = feed.filterIsInstance<LookItem.Picture>()

    @Test
    fun `a camera roll emptied in nine seconds is one thing`() {
        val roll = (0 until 35).map { status("m$it", noon - it * 250L) }

        val feed = LookFeed.build(mails = emptyList(), statuses = roll)

        assertEquals(1, feed.size)
        assertEquals(35, pictures(feed).single().count)
    }

    @Test
    fun `the burst is dated by its newest picture and stands for it`() {
        val roll = (0 until 5).map { status("m$it", noon - it * 1000L) }

        val burst = pictures(LookFeed.build(emptyList(), roll)).single()

        assertEquals(noon, burst.timestamp)
        assertEquals("m0", burst.message.id)
        assertEquals("picture:m0", burst.id)
    }

    @Test
    fun `three posts across an evening with nobody in between are one album`() {
        // Katrin Brunner, 30 August: 20 and 37 minutes apart, each with its own caption.
        val evening = listOf(
            status("k3", noon, from = "Katrin Brunner", text = "[Foto] Sommer am See"),
            status("k2", noon - 20 * minute, from = "Katrin Brunner"),
            status("k1", noon - 57 * minute, from = "Katrin Brunner", text = "[Foto] Abendspaziergang")
        )

        val feed = LookFeed.build(emptyList(), evening)
        assertEquals(1, feed.size)
        assertEquals(3, pictures(feed).single().count)
    }

    @Test
    fun `two people posting at the same moment are two things`() {
        val both = listOf(
            status("a", noon, from = "Martin Nauer"),
            status("b", noon - 1000L, from = "Alex Sommer")
        )

        assertEquals(2, LookFeed.build(emptyList(), both).size)
    }

    @Test
    fun `a letter between two pictures keeps her post in order`() {
        // Folding these would have to move the letter, and the one ordering she never has to be
        // taught is when things arrived.
        val statuses = listOf(status("p2", noon), status("p1", noon - 2 * minute))
        val letter = Mail(
            id = "l1",
            senderName = "Ihr Sohn",
            senderAddress = "sohn@example.ch",
            subject = "Sonntag",
            snippet = "",
            timestamp = noon - minute
        )

        val feed = LookFeed.build(mails = listOf(letter), statuses = statuses)

        assertEquals(3, feed.size)
        assertTrue(feed[1] is LookItem.Letter)
        assertEquals(1, pictures(feed)[0].count)
        assertEquals(1, pictures(feed)[1].count)
    }

    @Test
    fun `the run grows at its older end, however long it is`() {
        // 30 pictures over two hours are one posting, which is what emptying a camera roll looks like.
        val slow = (0 until 30).map { status("m$it", noon - it * (4 * minute)) }

        assertEquals(1, LookFeed.build(emptyList(), slow).size)
    }

    @Test
    fun `somebody else's status ends the burst`() {
        val items = listOf(
            status("a3", noon, from = "Martin Nauer"),
            status("a2", noon - minute, from = "Martin Nauer"),
            status("b1", noon - 2 * minute, from = "Alex Sommer"),
            status("a1", noon - 3 * minute, from = "Martin Nauer")
        )

        val feed = LookFeed.build(emptyList(), items)
        assertEquals(3, feed.size)
        val pics = pictures(feed)
        assertEquals(3, pics.size)
        assertEquals(2, pics[0].count)
        assertEquals(1, pics[1].count)
        assertEquals(1, pics[2].count)
    }

    @Test
    fun `there is no time limit`() {
        val items = listOf(
            status("a2", noon, from = "Martin Nauer"),
            status("a1", noon - 24 * 60 * minute, from = "Martin Nauer")
        )

        val feed = LookFeed.build(emptyList(), items)
        assertEquals(1, feed.size)
        assertEquals(2, pictures(feed).single().count)
    }

    @Test
    fun `a words-only status from the same person stays in the burst`() {
        val items = listOf(
            status("a2", noon, from = "Martin Nauer", text = "Just words here", photo = null),
            status("a1", noon - minute, from = "Martin Nauer", text = "[Foto]", photo = "A#1")
        )

        val feed = LookFeed.build(emptyList(), items)
        assertEquals(1, feed.size)
        assertEquals(2, pictures(feed).single().count)
    }

    @Test
    fun `two people who chose the same name are told apart by their address`() {
        val one = status("a", noon).copy(senderAddress = "+41790000001")
        val two = status("b", noon - 1000L).copy(senderAddress = "+41790000002")

        assertFalse(StatusBurst.sameBurst(one, two))
    }

    @Test
    fun `a conversation is never a burst`() {
        val written = status("w", noon).copy(groupIdentity = null)

        assertFalse(StatusBurst.sameBurst(written, written.copy(id = "w2")))
    }

    /** The contents of a downloaded picture, as [ch.heuscher.gentleconnect.data.MediaFingerprint]
     * would answer for it — here the file name stands in for the bytes. */
    private val byContents: (String) -> String? = { path -> path.substringBefore('#') }

    @Test
    fun `the same photograph posted three times in one go is one photograph`() {
        // Hans Muller, 16 August, 08:15 to 08:23: nine statuses, three pictures, each posted
        // three times, every copy in its own file under its own WhatsApp id.
        val hans = listOf(
            status("h9", noon, from = "Hans", photo = "C#3"),
            status("h8", noon - 1, from = "Hans", photo = "B#3"),
            status("h7", noon - 2, from = "Hans", photo = "A#3"),
            status("h6", noon - 3 * minute, from = "Hans", photo = "C#2"),
            status("h5", noon - 3 * minute - 4000, from = "Hans", photo = "B#2"),
            status("h4", noon - 3 * minute - 4001, from = "Hans", photo = "A#2"),
            status("h3", noon - 6 * minute - 16000, from = "Hans", photo = "C#1"),
            status("h2", noon - 6 * minute - 25000, from = "Hans", photo = "B#1"),
            status("h1", noon - 7 * minute - 4000, from = "Hans", photo = "A#1")
        )

        val feed = LookFeed.build(emptyList(), hans, mediaKeyOf = byContents)

        // One posting, and the three photographs that were actually in it.
        assertEquals(1, feed.size)
        val burst = pictures(feed).single()
        assertEquals(3, burst.count)
        assertEquals(listOf("h9", "h8", "h7"), burst.messages.map { it.id })
    }

    @Test
    fun `dropping the copies must not break the run in two`() {
        // The gap that chains a burst is between one picture and the next, including the copies.
        // Folded with the repeats taken out first, Hans's nine broke at the hole and showed her
        // the same three photographs twice.
        val hans = listOf(
            status("h3", noon, from = "Hans", photo = "A#3"),
            status("h2", noon - 3 * minute, from = "Hans", photo = "A#2"),
            status("h1", noon - 6 * minute, from = "Hans", photo = "A#1")
        )

        val feed = LookFeed.build(emptyList(), hans, mediaKeyOf = byContents)

        assertEquals(1, feed.size)
        assertEquals(1, pictures(feed).single().count)
    }

    @Test
    fun `two photographs of the same vase are two photographs`() {
        // Stefanie Raisch posted two shots of one bouquet in the same second. Different bytes,
        // different sizes: resemblance is not identity and must never be treated as it.
        val two = listOf(
            status("s2", noon, from = "Stefanie", photo = "vase-second"),
            status("s1", noon, from = "Stefanie", photo = "vase-first")
        )

        assertEquals(2, pictures(LookFeed.build(emptyList(), two, mediaKeyOf = byContents)).single().count)
    }

    @Test
    fun `the same picture posted again next week is not hidden`() {
        val twice = listOf(
            status("later", noon, from = "Hans", photo = "A#2"),
            status("middle", noon - 3L * 24 * 60 * minute, from = "Anna", photo = "B#1"),
            status("earlier", noon - 7L * 24 * 60 * minute, from = "Hans", photo = "A#1")
        )

        // Two postings by Hans, separated by Anna, and she is told about both.
        val feed = LookFeed.build(emptyList(), twice, mediaKeyOf = byContents)
        assertEquals(3, feed.size)
    }

    @Test
    fun `a status with nothing downloaded is never a repeat`() {
        val undownloaded = listOf(
            status("u2", noon, from = "Hans"),
            status("u1", noon - 1000, from = "Hans")
        )

        assertEquals(2, pictures(LookFeed.build(emptyList(), undownloaded, mediaKeyOf = byContents)).single().count)
    }

    @Test
    fun `with no way to read the files, nothing is taken for a repeat`() {
        val hans = listOf(
            status("h2", noon, from = "Hans", photo = "A#2"),
            status("h1", noon - 1000, from = "Hans", photo = "A#1")
        )

        assertEquals(2, pictures(LookFeed.build(emptyList(), hans)).single().count)
    }

    @Test
    fun `one picture is still one picture`() {
        val alone = pictures(LookFeed.build(emptyList(), listOf(status("only", noon)))).single()

        assertEquals(1, alone.count)
        assertEquals("only", alone.message.id)
    }
}
