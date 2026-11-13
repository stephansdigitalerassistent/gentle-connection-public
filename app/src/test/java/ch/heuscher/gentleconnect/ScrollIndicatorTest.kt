package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.ScrollIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollIndicatorTest {

    @Test
    fun `text that fits shows no bar`() {
        assertNull(ScrollIndicator.forText(scrollValue = 0, maxValue = 0, viewportSize = 800))
    }

    @Test
    fun `a list shorter than its screen shows no bar`() {
        assertNull(ScrollIndicator.forList(firstVisibleIndex = 0, visibleCount = 6, totalCount = 6))
        assertNull(ScrollIndicator.forList(firstVisibleIndex = 0, visibleCount = 6, totalCount = 3))
    }

    @Test
    fun `an empty list shows no bar`() {
        assertNull(ScrollIndicator.forList(firstVisibleIndex = 0, visibleCount = 0, totalCount = 0))
    }

    @Test
    fun `at the top the bar starts at the top`() {
        val bar = ScrollIndicator.forText(scrollValue = 0, maxValue = 1200, viewportSize = 400)
        assertNotNull(bar)
        assertEquals(0f, bar!!.startFraction, 0.001f)
    }

    @Test
    fun `at the bottom the bar ends at the bottom`() {
        val bar = ScrollIndicator.forText(scrollValue = 1200, maxValue = 1200, viewportSize = 400)!!
        assertEquals(1f, bar.startFraction + bar.thumbFraction, 0.001f)
    }

    @Test
    fun `the bar is as long as the share of the text she can see`() {
        // 400 visible of 1600 total.
        val bar = ScrollIndicator.forText(scrollValue = 0, maxValue = 1200, viewportSize = 400)!!
        assertEquals(0.25f, bar.thumbFraction, 0.001f)
    }

    @Test
    fun `halfway down the bar is halfway down the remaining track`() {
        val bar = ScrollIndicator.forText(scrollValue = 600, maxValue = 1200, viewportSize = 400)!!
        assertEquals(0.5f * (1f - 0.25f), bar.startFraction, 0.001f)
    }

    @Test
    fun `her whole address book still shows a bar she can see`() {
        // Five rows visible of 1424 is a thumb of 0.35% — invisible without a floor.
        val bar = ScrollIndicator.forList(firstVisibleIndex = 0, visibleCount = 5, totalCount = 1424)!!
        assertTrue("thumb was ${bar.thumbFraction}", bar.thumbFraction >= 0.08f)
    }

    @Test
    fun `the last row of a long list still puts the bar at the bottom`() {
        val bar = ScrollIndicator.forList(
            firstVisibleIndex = 1424 - 5,
            visibleCount = 5,
            totalCount = 1424
        )!!
        assertEquals(1f, bar.startFraction + bar.thumbFraction, 0.001f)
    }

    @Test
    fun `scrolled past the last possible row the bar does not run off the end`() {
        // A lazy list can report a first-visible index beyond total minus visible while it is
        // still settling. The bar must stay inside its track.
        val bar = ScrollIndicator.forList(
            firstVisibleIndex = 1500,
            visibleCount = 5,
            totalCount = 1424
        )!!
        assertTrue(bar.startFraction + bar.thumbFraction <= 1.0001f)
        assertTrue(bar.startFraction >= 0f)
    }

    @Test
    fun `the bar never leaves the track anywhere in a long list`() {
        val total = 1424
        val visible = 5
        for (first in 0..(total - visible)) {
            val bar = ScrollIndicator.forList(first, visible, total)!!
            assertTrue(bar.startFraction >= 0f)
            assertTrue(bar.startFraction + bar.thumbFraction <= 1.0001f)
        }
    }

    @Test
    fun `a list two rows longer than the screen puts the bar near the middle at the top`() {
        val bar = ScrollIndicator.forList(firstVisibleIndex = 1, visibleCount = 5, totalCount = 7)!!
        assertEquals(5f / 7f, bar.thumbFraction, 0.001f)
        assertEquals(0.5f * (1f - 5f / 7f), bar.startFraction, 0.001f)
    }

    @Test
    fun `zoom scroll indicator at 1x shows no bar`() {
        assertNull(ScrollIndicator.forZoom(offset = 0f, scale = 1f, viewportSize = 1000f))
        assertNull(ScrollIndicator.forZoom(offset = 0f, scale = 0.5f, viewportSize = 1000f))
    }

    @Test
    fun `zoom scroll indicator at 2x starts at start and spans half track`() {
        val bar = ScrollIndicator.forZoom(offset = 0f, scale = 2f, viewportSize = 1000f)!!
        assertEquals(0.5f, bar.thumbFraction, 0.001f)
        assertEquals(0f, bar.startFraction, 0.001f)
    }

    @Test
    fun `zoom scroll indicator at 2x at end spans to the end`() {
        val bar = ScrollIndicator.forZoom(offset = -1000f, scale = 2f, viewportSize = 1000f)!!
        assertEquals(0.5f, bar.thumbFraction, 0.001f)
        assertEquals(0.5f, bar.startFraction, 0.001f)
        assertEquals(1f, bar.startFraction + bar.thumbFraction, 0.001f)
    }

    @Test
    fun `zoom scroll indicator halfway across sits halfway`() {
        val bar = ScrollIndicator.forZoom(offset = -500f, scale = 2f, viewportSize = 1000f)!!
        assertEquals(0.5f, bar.thumbFraction, 0.001f)
        assertEquals(0.25f, bar.startFraction, 0.001f)
    }
}
