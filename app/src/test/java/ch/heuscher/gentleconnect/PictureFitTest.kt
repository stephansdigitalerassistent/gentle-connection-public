package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.PictureFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A photograph is never wider than the width at which an upright one still fits the height.
 *
 * The numbers are the two windows this app is actually held in, measured on two target form factors: a compact phone
 * and the open inner screen of a foldable display.
 */
class PictureFitTest {

    // The floor, below which a picture stops shrinking and is allowed to be tall again.
    private val narrowest = 240f

    private fun heightOf(width: Float) = width / PictureFit.UPRIGHT

    @Test
    fun `her phone is not touched by this at all`() {
        // 600 points of list asks for 450, and the widest bubble on that screen is about 320. A
        // cap above the width there is changes nothing.
        val widest = PictureFit.widestPicture(height = 600f, narrowest = narrowest)
        assertTrue("wider than any bubble on her phone", widest > 330f)
    }

    @Test
    fun `the open Fold holds the picture to the height there is`() {
        val widest = PictureFit.widestPicture(height = 500f, narrowest = narrowest)
        assertEquals(375f, widest, 0.5f)
        assertTrue("an upright photograph fits", heightOf(widest) <= 500f + 0.5f)
    }

    @Test
    fun `a window too short for one stops at the floor`() {
        // A picture she cannot make anybody out in is worse than one she has to scroll.
        assertEquals(narrowest, PictureFit.widestPicture(height = 200f, narrowest = narrowest), 0.5f)
    }

    @Test
    fun `nonsense gives back the floor rather than nothing`() {
        assertEquals(narrowest, PictureFit.widestPicture(height = 0f, narrowest = narrowest), 0.5f)
        assertEquals(narrowest, PictureFit.widestPicture(height = -10f, narrowest = narrowest), 0.5f)
    }
}
