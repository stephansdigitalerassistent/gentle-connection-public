package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.PhotoShape
import ch.heuscher.gentleconnect.model.PhotoShape.isCropped
import ch.heuscher.gentleconnect.model.PhotoShape.ratioFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape a photograph is drawn in.
 *
 * Worth its own tests because the failure is silent: a picture cropped to the wrong shape still
 * looks like a photograph, and the only sign that her daughter's face was cut off the top of it
 * is that she says so on the telephone.
 */
class PhotoShapeTest {

    private fun assertRatio(expected: Float, actual: Float) {
        assertEquals(expected.toDouble(), actual.toDouble(), 0.001)
    }

    @Test
    fun `an upright photograph from a telephone keeps its own shape`() {
        // 3:4 held upright — most of what her family sends, and the case the fixed-height box got
        // worst: it used to be cropped to a band across the middle.
        assertRatio(3f / 4f, ratioFor(3024, 4032))
        assertFalse(isCropped(3024, 4032))
    }

    @Test
    fun `an ordinary landscape photograph keeps its own shape`() {
        assertRatio(4f / 3f, ratioFor(4032, 3024))
        assertRatio(16f / 9f, ratioFor(1920, 1080))
        assertFalse(isCropped(4032, 3024))
        assertFalse(isCropped(1920, 1080))
    }

    @Test
    fun `a square photograph is left square`() {
        assertRatio(1f, ratioFor(1000, 1000))
        assertFalse(isCropped(1000, 1000))
    }

    @Test
    fun `a very tall picture is held to the tallest shape that still leaves room for the words`() {
        // 9:16, which is a screen recording or a picture of another screen. Whole, it would be
        // the entire display: at a bubble 300dp wide it is 533dp tall, and what was written under
        // it — and the message after that — would be somewhere below the bottom of the screen.
        assertRatio(PhotoShape.TALLEST, ratioFor(1080, 1920))
        assertTrue(isCropped(1080, 1920))

        // And nothing is ever taller than that limit, however extreme the picture.
        assertRatio(PhotoShape.TALLEST, ratioFor(200, 4000))
    }

    @Test
    fun `a panorama is held to the widest shape that can still be made out`() {
        // 15:1. Drawn whole in a bubble 300dp wide it is a stripe twenty points high, which is a
        // photograph she cannot see rather than one she can see part of.
        assertRatio(PhotoShape.WIDEST, ratioFor(12000, 800))
        assertTrue(isCropped(12000, 800))
    }

    @Test
    fun `the limits themselves are drawn whole`() {
        // Exactly at a limit is not past it: nothing is cropped for being precisely 2:3 or 2:1.
        assertRatio(PhotoShape.TALLEST, ratioFor(2000, 3000))
        assertRatio(PhotoShape.WIDEST, ratioFor(4000, 2000))
        assertFalse(isCropped(2000, 3000))
        assertFalse(isCropped(4000, 2000))
    }

    @Test
    fun `dimensions that could not be read do not divide by zero`() {
        // BitmapFactory reports 0 for a file whose header it could not read, which for a
        // photograph that is still arriving is an ordinary thing to happen.
        assertRatio(PhotoShape.UNKNOWN, ratioFor(0, 0))
        assertRatio(PhotoShape.UNKNOWN, ratioFor(-1, 100))
        assertFalse(isCropped(0, 0))
    }
}
