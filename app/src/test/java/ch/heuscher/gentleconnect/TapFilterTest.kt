package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.TapFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapFilterTest {

    @Test
    fun `a finger that lifts where it landed is a tap`() {
        val filter = TapFilter(slopPx = 20f)
        filter.onDown(100f, 100f)
        filter.onMove(105f, 103f)
        assertTrue(filter.onUp(106f, 104f))
    }

    @Test
    fun `a scroll is not a tap`() {
        val filter = TapFilter(slopPx = 20f)
        filter.onDown(100f, 500f)
        filter.onMove(100f, 400f)
        assertFalse(filter.onUp(100f, 300f))
    }

    @Test
    fun `a drag that comes back to where it started is still not a tap`() {
        val filter = TapFilter(slopPx = 20f)
        filter.onDown(100f, 500f)
        filter.onMove(100f, 300f)
        assertFalse(filter.onUp(100f, 500f))
    }

    @Test
    fun `a lift far from the landing with no move in between is not a tap`() {
        val filter = TapFilter(slopPx = 20f)
        filter.onDown(100f, 500f)
        assertFalse(filter.onUp(100f, 200f))
    }

    @Test
    fun `a pinch or a cancelled touch is not a tap`() {
        val filter = TapFilter(slopPx = 20f)
        filter.onDown(100f, 100f)
        filter.onCancel()
        assertFalse(filter.onUp(100f, 100f))
    }

    @Test
    fun `a lift without a landing is not a tap`() {
        assertFalse(TapFilter(slopPx = 20f).onUp(100f, 100f))
    }

    @Test
    fun `each touch is judged on its own`() {
        val filter = TapFilter(slopPx = 20f)
        filter.onDown(100f, 500f)
        assertFalse(filter.onUp(100f, 200f))
        filter.onDown(100f, 100f)
        assertTrue(filter.onUp(100f, 100f))
    }
}
