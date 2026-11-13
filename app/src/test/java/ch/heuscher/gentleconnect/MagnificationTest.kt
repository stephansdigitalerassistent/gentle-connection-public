package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Magnification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind making her screen bigger.
 *
 * Tested closely because the failure mode is not a wrong pixel, it is a trap: a clamp that lets
 * the content slide away leaves her looking at blank space with no idea what she did, and at that
 * point the telephone is broken until somebody comes round.
 */
class MagnificationTest {

    @Test
    fun `pinching outwards cannot make the screen smaller than it is`() {
        // The floor is the thing that keeps everything else honest: at scale 1 there is no slack,
        // so there is nothing to pan and nothing to get lost in.
        assertEquals(1f, Magnification.scaleBy(1f, 0.5f), 0.0001f)
        assertEquals(1f, Magnification.scaleBy(1.2f, 0.1f), 0.0001f)
    }

    @Test
    fun `zooming stops at the ceiling rather than running away`() {
        assertEquals(Magnification.MAX_SCALE, Magnification.scaleBy(3.9f, 4f), 0.0001f)
        assertEquals(Magnification.MAX_SCALE, Magnification.scaleBy(Magnification.MAX_SCALE, 2f), 0.0001f)
    }

    @Test
    fun `a nonsensical pinch changes nothing`() {
        // Gesture streams produce these when a finger is lost mid-pinch — a centroid of zero
        // distance divides by nothing. NaN reaching the scale would blank the screen entirely.
        assertEquals(2f, Magnification.scaleBy(2f, Float.NaN), 0.0001f)
        assertEquals(2f, Magnification.scaleBy(2f, 0f), 0.0001f)
        assertEquals(2f, Magnification.scaleBy(2f, -1f), 0.0001f)
        assertEquals(2f, Magnification.scaleBy(2f, Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun `the point under her fingers stays under her fingers`() {
        // The whole reason offsetAfterZoom exists. Doubling about a centroid 300 into the screen:
        // the content point that was at 300 has to still appear at 300 afterwards.
        val offset = Magnification.offsetAfterZoom(offset = 0f, centroid = 300f, fromScale = 1f, toScale = 2f)

        // Content point p appears at offset + p * scale. Before: p = 300. After: -300 + 300*2 = 300.
        assertEquals(-300f, offset, 0.0001f)
        assertEquals(300f, offset + 300f * 2f, 0.0001f)
    }

    @Test
    fun `zooming back out returns to where it started`() {
        // Not decoration: she pinches in and out repeatedly, and drift would walk the screen away
        // from her a little on every pinch until the way back is the only thing that helps.
        val zoomedIn = Magnification.offsetAfterZoom(0f, 250f, 1f, 3f)
        val backOut = Magnification.offsetAfterZoom(zoomedIn, 250f, 3f, 1f)

        assertEquals(0f, backOut, 0.0001f)
    }

    @Test
    fun `an unmagnified screen cannot be dragged at all`() {
        // So a two-finger swipe on a screen she never zoomed leaves everything exactly where it
        // was, rather than nudging it and looking like a fault.
        assertEquals(0f, Magnification.clampOffset(-500f, 1f, 1080f), 0.0001f)
        assertEquals(0f, Magnification.clampOffset(500f, 1f, 1080f), 0.0001f)
    }

    @Test
    fun `the magnified screen always covers the screen`() {
        val viewport = 1080f
        val scale = 2f
        // At 2x there are 1080 pixels of slack, and the legal offsets run from -1080 to 0.
        assertEquals(-1080f, Magnification.clampOffset(-5000f, scale, viewport), 0.0001f)
        assertEquals(0f, Magnification.clampOffset(400f, scale, viewport), 0.0001f)
        assertEquals(-500f, Magnification.clampOffset(-500f, scale, viewport), 0.0001f)

        // Restated as the property that matters: no legal offset leaves a gap at either edge.
        for (candidate in listOf(-5000f, -1080f, -700f, -1f, 0f, 900f)) {
            val clamped = Magnification.clampOffset(candidate, scale, viewport)
            assertTrue("left edge showed a gap", clamped <= 0f)
            assertTrue("right edge showed a gap", clamped + viewport * scale >= viewport)
        }
    }

    @Test
    fun `a screen with no size yet is not divided by`() {
        // onSizeChanged has not fired on the first frame, so this really does happen.
        assertEquals(0f, Magnification.clampOffset(-100f, 2f, 0f), 0.0001f)
    }

    @Test
    fun `the way back appears only when there is something to undo`() {
        assertFalse(Magnification.isZoomed(1f))
        // A pinch almost never lands on a round number; 1.004 is not a magnification she can see,
        // and offering to undo it would put a button on her screen for no reason.
        assertFalse(Magnification.isZoomed(1.004f))
        assertTrue(Magnification.isZoomed(1.5f))
        assertTrue(Magnification.isZoomed(Magnification.MAX_SCALE))
    }

    @Test
    fun `double tap zoom factor is two and a half times`() {
        assertEquals(2.5f, Magnification.DOUBLE_TAP_SCALE, 0.0001f)
    }

    @Test
    fun `double tap zoom keeps the tapped point under her finger`() {
        // Tapping the centre of a 1000x2000 screen: point 500, 1000 must stay at 500, 1000.
        val zoom = Magnification.zoomInAt(pointX = 500f, pointY = 1000f, width = 1000f, height = 2000f)

        assertEquals(Magnification.DOUBLE_TAP_SCALE, zoom.scale, 0.0001f)
        // Content point p appears at offset + p * scale.
        // For x: offset + 500 * 2.5 = 500 -> offset = -750
        assertEquals(-750f, zoom.offsetX, 0.0001f)
        assertEquals(500f, zoom.offsetX + 500f * zoom.scale, 0.0001f)

        // For y: offset + 1000 * 2.5 = 1000 -> offset = -1500
        assertEquals(-1500f, zoom.offsetY, 0.0001f)
        assertEquals(1000f, zoom.offsetY + 1000f * zoom.scale, 0.0001f)
    }

    @Test
    fun `double tap zoom at top-left edge clamps to zero offsets`() {
        val zoom = Magnification.zoomInAt(pointX = 0f, pointY = 0f, width = 1000f, height = 2000f)
        assertEquals(0f, zoom.offsetX, 0.0001f)
        assertEquals(0f, zoom.offsetY, 0.0001f)
    }

    @Test
    fun `double tap zoom at bottom-right edge clamps without gaps`() {
        val width = 1000f
        val height = 2000f
        val zoom = Magnification.zoomInAt(pointX = width, pointY = height, width = width, height = height)

        val slackX = width * (Magnification.DOUBLE_TAP_SCALE - 1f)
        val slackY = height * (Magnification.DOUBLE_TAP_SCALE - 1f)
        assertEquals(-slackX, zoom.offsetX, 0.0001f)
        assertEquals(-slackY, zoom.offsetY, 0.0001f)
        assertTrue("right edge showed a gap", zoom.offsetX + width * zoom.scale >= width)
        assertTrue("bottom edge showed a gap", zoom.offsetY + height * zoom.scale >= height)
    }

    @Test
    fun `double tap zoom on a zero-size viewport returns zero offsets`() {
        val zoom = Magnification.zoomInAt(pointX = 100f, pointY = 100f, width = 0f, height = 0f)
        assertEquals(0f, zoom.offsetX, 0.0001f)
        assertEquals(0f, zoom.offsetY, 0.0001f)
    }
}
