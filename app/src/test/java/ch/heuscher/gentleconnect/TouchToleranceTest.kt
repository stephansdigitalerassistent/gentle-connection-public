package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.TouchTolerance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for expanding touch slop to accommodate tremor and unsteady fingers.
 *
 * Tested carefully because a touch slop that is too small cancels clicks whenever her finger
 * wobbles on the glass, turning an intended tap into a drag and leaving the phone feeling broken
 * and unresponsive.
 */
class TouchToleranceTest {

    @Test
    fun `slop factor is two and a half times`() {
        assertEquals(2.5f, TouchTolerance.SLOP_FACTOR, 0.0001f)
    }

    @Test
    fun `slop scales platform value by slop factor`() {
        // Platform touch slop of 8 dp (e.g. 8 px at 1x density) becomes 20 px
        assertEquals(20f, TouchTolerance.slopFor(8f), 0.0001f)
        // At 2x density (16 px), becomes 40 px (~20 dp)
        assertEquals(40f, TouchTolerance.slopFor(16f), 0.0001f)
        // At 3x density (24 px), becomes 60 px (~20 dp)
        assertEquals(60f, TouchTolerance.slopFor(24f), 0.0001f)
    }

    @Test
    fun `slop is never smaller than the platform value`() {
        for (candidate in listOf(1f, 8f, 16f, 24f, 48f, 100f)) {
            val slop = TouchTolerance.slopFor(candidate)
            assertTrue("slop $slop was smaller than platform value $candidate", slop >= candidate)
        }
    }

    @Test
    fun `zero or non-positive platform slop is preserved`() {
        assertEquals(0f, TouchTolerance.slopFor(0f), 0.0001f)
        assertEquals(-5f, TouchTolerance.slopFor(-5f), 0.0001f)
    }

    @Test
    fun `non-finite values do not produce wild results`() {
        assertTrue(TouchTolerance.slopFor(Float.NaN).isNaN())
        assertEquals(Float.POSITIVE_INFINITY, TouchTolerance.slopFor(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(Float.NEGATIVE_INFINITY, TouchTolerance.slopFor(Float.NEGATIVE_INFINITY), 0.0001f)
    }
}
