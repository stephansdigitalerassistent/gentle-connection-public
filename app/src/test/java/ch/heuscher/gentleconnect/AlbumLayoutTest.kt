package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.AlbumLayout
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.StatusBroadcast
import ch.heuscher.gentleconnect.model.StatusContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One picture on top, the second on a pile of the rest, and words drawn as words.
 */
class AlbumLayoutTest {

    private fun status(
        id: String,
        text: String = "[Foto]",
        photo: String? = "/files/$id.jpg",
        video: String? = null
    ) = Message(
        photoPath = photo,
        videoPath = video,
        id = id,
        senderName = "Martin Nauer",
        senderAddress = "+41790000001",
        contactId = "c-markus",
        text = text,
        timestamp = 0L,
        sourcePackage = "",
        groupIdentity = GroupIdentity.of("whatsapp-group", StatusBroadcast.JID)
    )

    private fun pictures(count: Int) = (0 until count).map { status("p$it") }

    @Test
    fun `one picture is the picture and nothing under it`() {
        val layout = AlbumLayout.of(pictures(1))

        assertNull(layout.second)
        assertEquals(0, layout.edges)
        assertEquals(0, layout.more)
    }

    @Test
    fun `two pictures are one on top of the other, with no pile`() {
        val layout = AlbumLayout.of(pictures(2))

        assertEquals(1, layout.second)
        assertEquals(0, layout.edges)
        assertEquals(0, layout.more)
    }

    @Test
    fun `a third picture is one edge behind the second`() {
        val layout = AlbumLayout.of(pictures(3))

        assertEquals(1, layout.edges)
        assertEquals(1, layout.more)
    }

    @Test
    fun `thirty five pictures are two edges deep and say thirty three more`() {
        val layout = AlbumLayout.of(pictures(35))

        assertEquals(AlbumLayout.MAX_EDGES, layout.edges)
        assertEquals(33, layout.more)
    }

    @Test
    fun `a burst of words is words and hides nothing`() {
        val words = listOf(status("w2", "Gute Nacht", photo = null), status("w1", "Was für ein Tag", photo = null))

        val layout = AlbumLayout.of(words)

        assertTrue(layout.onlyWords)
        assertEquals(0, layout.hiddenTexts)
    }

    @Test
    fun `a picture still on its way does not make a burst of words`() {
        val layout = AlbumLayout.of(listOf(status("w", "Hallo", photo = null), status("p", photo = null)))

        assertFalse(layout.onlyWords)
    }

    @Test
    fun `texts the card cannot show are counted, a second of words is shown`() {
        val burst = listOf(
            status("lead", "[Foto] Unser Garten"),  // under the lead picture: shown
            status("second", "Was für ein Tag", photo = null),  // on the pile: shown
            status("third", "[Foto] Die Rosen"),  // inside: hidden
            status("fourth", "Bis bald", photo = null),  // inside: hidden
            status("fifth")  // no words at all
        )

        assertEquals(2, AlbumLayout.of(burst).hiddenTexts)
    }

    @Test
    fun `a caption on the second picture is hidden, the pile shows only the picture`() {
        val burst = listOf(status("lead"), status("second", "[Foto] Die Rosen"))

        assertEquals(1, AlbumLayout.of(burst).hiddenTexts)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an album of nothing is refused`() {
        AlbumLayout.of(emptyList())
    }

    @Test
    fun `what a status has in it`() {
        assertEquals(StatusContent.PICTURE, StatusContent.of(status("p")))
        assertEquals(StatusContent.FILM, StatusContent.of(status("f", "[Video]", photo = null, video = "/files/f.mp4")))
        assertEquals(StatusContent.PENDING, StatusContent.of(status("pending", "[Foto]", photo = null)))
        assertEquals(StatusContent.PENDING, StatusContent.of(status("pending", "[Foto] Grüsse", photo = null)))
        assertEquals(StatusContent.WORDS, StatusContent.of(status("w", "Grüsse aus dem Garten", photo = null)))
        assertEquals(StatusContent.PENDING, StatusContent.of(status("empty", "", photo = null)))
    }
}
