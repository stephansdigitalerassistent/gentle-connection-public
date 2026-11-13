package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Arrivals
import ch.heuscher.gentleconnect.model.Home
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.PictureFlood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One person's camera roll, and the three things it must not be allowed to take: her home screen,
 * her quiet, and the memory this launcher lives in.
 */
class PictureFloodTest {

    private val minute = 60_000L
    private val noon = 1_700_000_000_000L

    private fun picture(
        id: String,
        from: String = "Martin Nauer",
        at: Long = noon,
        mine: Boolean = false,
        unread: Boolean = true
    ) = Message(
        id = id,
        senderName = from,
        senderAddress = "+4179" + from.hashCode().toString().takeLast(6),
        contactId = "c-$from",
        text = "[Foto]",
        timestamp = at,
        sourcePackage = "",
        photoPath = "/files/$id.jpg",
        isRead = !unread,
        direction = if (mine) MessageDirection.OUTGOING else MessageDirection.INCOMING
    )

    private fun words(id: String, from: String = "Martin Nauer", at: Long = noon) =
        picture(id, from, at).copy(text = "Guten Morgen", photoPath = null)

    @Test
    fun `a run past the tenth picture is drawn small`() {
        val roll = (1..35).map { picture("m$it") }

        val small = PictureFlood.shrunken(roll)

        assertEquals(35 - PictureFlood.FULL_SIZE_IN_A_ROW, small.size)
        assertFalse("m10" in small)
        assertTrue("m11" in small)
    }

    private fun film(id: String, landed: Boolean = true) =
        picture(id).copy(text = "[Video]", videoPath = if (landed) "/files/$id.mp4" else null)

    @Test
    fun `a film deep in a run is drawn full size`() {
        val roll = (1..11).map { picture("m$it") } + film("f") + (12..25).map { picture("m$it") }

        val small = PictureFlood.shrunken(roll)

        assertFalse("f" in small)
        assertEquals(25 - PictureFlood.FULL_SIZE_IN_A_ROW, small.size)
    }

    @Test
    fun `a film is drawn full size before it has landed`() {
        val roll = (1..11).map { picture("m$it") } + film("f", landed = false)

        assertFalse("f" in PictureFlood.shrunken(roll))
    }

    @Test
    fun `films do not break a run of photographs`() {
        val roll = (1..10).map { picture("m$it") } + film("f") + picture("m11")

        assertTrue("m11" in PictureFlood.shrunken(roll))
    }

    @Test
    fun `nine photos from one outing are all drawn full size`() {
        val outing = (1..9).map { picture("o$it") }

        assertTrue(PictureFlood.shrunken(outing).isEmpty())
    }

    @Test
    fun `a word between them starts the run again`() {
        val talk = listOf(
            picture("a1"), picture("a2"), picture("a3"),
            words("w"),
            picture("a4"), picture("a5")
        )

        assertTrue(PictureFlood.shrunken(talk).isEmpty())
    }

    @Test
    fun `her own pictures are a run of their own`() {
        val both = listOf(
            picture("h1"), picture("h2"), picture("h3"),
            picture("mine", mine = true)
        )

        assertFalse("mine" in PictureFlood.shrunken(both))
    }

    @Test
    fun `two people taking turns are never a flood`() {
        val turns = (1..12).map { picture("p$it", from = if (it % 2 == 0) "Markus" else "Andi") }

        assertTrue(PictureFlood.shrunken(turns).isEmpty())
    }

    @Test
    fun `three is still three`() {
        assertTrue(PictureFlood.shrunken(listOf(picture("a"), picture("b"), picture("c"))).isEmpty())
    }

    @Test
    fun `her home screen keeps one row per person`() {
        // Twelve from Markus and one from her daughter, which is the row that must survive.
        val flood = (1..12).map { picture("m$it", at = noon - it * 1000L) }
        val daughter = picture("d", from = "Katrin Brunner", at = noon - 20 * minute)

        val home = Home.messages(flood + daughter)

        assertEquals(2, home.size)
        assertEquals(listOf("m1", "d"), home.map { it.id })
    }

    @Test
    fun `one notice for one run, and a later message is news again`() {
        assertTrue(Arrivals.worthAnnouncingAgain(sameSenderAsLast = false, sinceLastAnnouncement = 0L))
        assertFalse(Arrivals.worthAnnouncingAgain(sameSenderAsLast = true, sinceLastAnnouncement = 9_000L))
        assertTrue(
            Arrivals.worthAnnouncingAgain(
                sameSenderAsLast = true,
                sinceLastAnnouncement = Arrivals.SAME_SENDER_QUIET_MS + 1
            )
        )
    }
}
