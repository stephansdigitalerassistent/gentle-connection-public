package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.PictureShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * What is remembered about a picture that is no longer on the screen.
 *
 * Found on the phone: opening the Google letter on her post screen and pressing back landed two
 * pictures further down the list, with the letter she had just read gone off the top. The list is
 * put back at the card she left and at how far into it she had scrolled — and the pictures are
 * read off the disk again, so for a moment every card holding one was as tall as its title alone
 * and could not hold that scroll.
 */
class PictureShapesTest {

    @Before
    fun forgetWhatEarlierTestsLookedAt() {
        PictureShapes.forget()
    }

    @Test
    fun `a picture already seen is given the same room next time`() {
        PictureShapes.note("/pictures/anna.jpg", 3f / 4f)

        assertEquals(0.75, PictureShapes.of("/pictures/anna.jpg")!!.toDouble(), 0.001)
    }

    @Test
    fun `a picture never seen reserves nothing`() {
        // She is scrolling forward into new post here. There is no place to lose, and guessing a
        // shape would open a gap the picture then does not fill.
        assertNull(PictureShapes.of("/pictures/never-opened.jpg"))
    }

    @Test
    fun `a film and the still that stood in for it are one picture`() {
        // Two files arrive for one card: the thumbnail packed into the message, and the film
        // behind it. The space held open for the first has to be the space the second wants.
        val key = PictureShapes.keyFor(path = "/pictures/still.jpg", videoPath = "/films/walk.mp4")

        assertEquals("/films/walk.mp4", key)
        assertEquals(key, PictureShapes.keyFor(path = "/pictures/other.jpg", videoPath = "/films/walk.mp4"))
    }

    @Test
    fun `a picture with no film is named by itself`() {
        assertEquals("/pictures/anna.jpg", PictureShapes.keyFor("/pictures/anna.jpg", null))
        assertEquals("/pictures/anna.jpg", PictureShapes.keyFor("/pictures/anna.jpg", ""))
        assertEquals("", PictureShapes.keyFor(null, null))
    }

    @Test
    fun `nothing is remembered about nothing`() {
        // A shape that is not a shape must never reach aspectRatio, which throws on it — and a
        // card that throws while she scrolls is the whole screen gone, not one picture.
        PictureShapes.note("", 0.75f)
        PictureShapes.note("/pictures/broken.jpg", 0f)
        PictureShapes.note("/pictures/worse.jpg", -1f)
        PictureShapes.note("/pictures/worst.jpg", Float.NaN)

        assertNull(PictureShapes.of(""))
        assertNull(PictureShapes.of("/pictures/broken.jpg"))
        assertNull(PictureShapes.of("/pictures/worse.jpg"))
        assertNull(PictureShapes.of("/pictures/worst.jpg"))
    }

    @Test
    fun `four years of history does not grow without end`() {
        repeat(PictureShapes.KEEP + 50) { PictureShapes.note("/pictures/$it.jpg", 3f / 4f) }

        // The oldest are gone and the newest are kept — which is the right way round, because
        // what she is about to scroll back over is what she has just been looking at.
        assertNull(PictureShapes.of("/pictures/0.jpg"))
        assertEquals(
            0.75,
            PictureShapes.of("/pictures/${PictureShapes.KEEP + 49}.jpg")!!.toDouble(),
            0.001
        )
    }
}
