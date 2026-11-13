package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Message
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word for a thing, next to the thing.
 *
 * Found on the phone: a picture from her daughter arrived as a bubble reading "Foto" with the
 * photograph itself directly underneath; a film reading "Video" above its own still and a play
 * button; a voice message reading "Sprachnachricht" above the button that plays the voice. In each
 * case the app naming what she was already looking at, in the only line of text the message had.
 * See [Message.showsWords].
 */
class MediaWordsTest {

    private fun message(
        text: String,
        photoPath: String? = null,
        videoPath: String? = null,
        audioPath: String? = null
    ) = Message(
        id = "1",
        senderName = "Anna",
        text = text,
        timestamp = 1_700_000_000_000L,
        sourcePackage = "ch.threema.app",
        photoPath = photoPath,
        videoPath = videoPath,
        audioPath = audioPath
    )

    @Test
    fun `the thing itself is the whole message once it is on the screen`() {
        assertFalse(message("[Foto]", photoPath = "/photos/1.jpg").showsWords)
        assertFalse(message("[Video]", videoPath = "/films/1.mp4").showsWords)
        assertFalse(message("[Sprachnachricht]", audioPath = "/voices/1.ogg").showsWords)
    }

    @Test
    fun `what never arrived keeps its word`() {
        // An empty bubble with a name and a time on it would be her being told somebody wrote and
        // shown nothing at all.
        assertTrue(message("[Foto]").showsWords)
        assertTrue(message("[Video]").showsWords)
        assertTrue(message("[Sprachnachricht]").showsWords)
        assertTrue(message("[Foto]", photoPath = " ").showsWords)
    }

    @Test
    fun `a film that never came keeps its word even though its still did`() {
        // The still arrives with the message and the film is downloaded behind it, so this is the
        // ordinary state of every film on a bad connection and the permanent state of every one
        // WhatsApp has expired. A picture with no control under it is a photograph; the word is
        // the only thing left saying there was a film.
        assertTrue(message("[Video]", photoPath = "/photos/still.jpg").showsWords)
    }

    @Test
    fun `only the thing the token names counts`() {
        // A photograph does not stand in for a film, and a film does not stand in for a voice.
        assertTrue(message("[Sprachnachricht]", photoPath = "/photos/1.jpg").showsWords)
        assertTrue(message("[Foto]", videoPath = "/films/1.mp4").showsWords)
    }

    @Test
    fun `a place, a document, a poll and a contact card always keep their word`() {
        // Nothing is ever drawn for these, so the word is all there is.
        assertTrue(message("[Standort]").showsWords)
        assertTrue(message("[Dokument]").showsWords)
        assertTrue(message("[Umfrage]").showsWords)
        assertTrue(message("[Kontakt]").showsWords)
    }

    @Test
    fun `a caption is words, whatever came with it`() {
        assertTrue(message("[Foto] Grüsse aus dem Garten", photoPath = "/photos/1.jpg").showsWords)
        assertTrue(message("[Video] Schee war's!", videoPath = "/films/1.mp4").showsWords)
        assertTrue(message("Grüsse aus dem Garten", photoPath = "/photos/1.jpg").showsWords)
    }
}
