package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.ForgivingTap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [ForgivingTap] decision logic for turning tremor-wobbled touches into clicks
 * while preserving genuine list scrolling.
 */
class ForgivingTapTest {

    private val slop = 20f

    @Test
    fun `touch within slop without list scroll clicks`() {
        assertTrue(
            ForgivingTap.shouldClick(
                durationMs = 200L,
                travelPx = 10f,
                slopPx = slop,
                didListScroll = false
            )
        )
        // Not a "forgiving" tap since it never exceeded slop
        assertFalse(
            ForgivingTap.isForgivingClick(
                durationMs = 200L,
                travelPx = 10f,
                slopPx = slop,
                didListScroll = false
            )
        )
    }

    @Test
    fun `touch beyond slop without list scroll is forgiven and clicks`() {
        // Her tremor wobble: 229 ms, travelled 34 px (beyond 20 px slop), list never moved.
        assertTrue(
            ForgivingTap.shouldClick(
                durationMs = 229L,
                travelPx = 34f,
                slopPx = slop,
                didListScroll = false
            )
        )
        assertTrue(
            ForgivingTap.isForgivingClick(
                durationMs = 229L,
                travelPx = 34f,
                slopPx = slop,
                didListScroll = false
            )
        )
    }

    @Test
    fun `touch beyond slop when list moved does NOT click`() {
        // Genuine scroll: the finger moved and the list scrolled with it.
        assertFalse(
            ForgivingTap.shouldClick(
                durationMs = 300L,
                travelPx = 50f,
                slopPx = slop,
                didListScroll = true
            )
        )
        assertFalse(
            ForgivingTap.isForgivingClick(
                durationMs = 300L,
                travelPx = 50f,
                slopPx = slop,
                didListScroll = true
            )
        )
    }

    @Test
    fun `list scroll always prevents click even within slop`() {
        assertFalse(
            ForgivingTap.shouldClick(
                durationMs = 150L,
                travelPx = 12f,
                slopPx = slop,
                didListScroll = true
            )
        )
    }

    @Test
    fun `touch held longer than 800 ms does NOT click`() {
        // Slow drag or intentional hold: not a tap.
        assertFalse(
            ForgivingTap.shouldClick(
                durationMs = 850L,
                travelPx = 30f,
                slopPx = slop,
                didListScroll = false
            )
        )
    }

    @Test
    fun `boundary at 800 ms clicks when list did not scroll`() {
        assertTrue(
            ForgivingTap.shouldClick(
                durationMs = 800L,
                travelPx = 25f,
                slopPx = slop,
                didListScroll = false
            )
        )
        assertTrue(
            ForgivingTap.isForgivingClick(
                durationMs = 800L,
                travelPx = 25f,
                slopPx = slop,
                didListScroll = false
            )
        )
    }

    @Test
    fun `negative duration or negative travel does NOT click`() {
        assertFalse(
            ForgivingTap.shouldClick(
                durationMs = -50L,
                travelPx = 10f,
                slopPx = slop,
                didListScroll = false
            )
        )
        assertFalse(
            ForgivingTap.shouldClick(
                durationMs = 200L,
                travelPx = -5f,
                slopPx = slop,
                didListScroll = false
            )
        )
    }

    @Test
    fun `zero duration and zero travel clicks when list did not scroll`() {
        assertTrue(
            ForgivingTap.shouldClick(
                durationMs = 0L,
                travelPx = 0f,
                slopPx = slop,
                didListScroll = false
            )
        )
    }

    @Test
    fun `travel beyond 3x slop is a swipe and does NOT click`() {
        // At slop = 20 px, 3x slop is 60 px. 61 px is a swipe, never a tap.
        assertFalse(
            ForgivingTap.shouldClick(
                durationMs = 200L,
                travelPx = 61f,
                slopPx = slop,
                didListScroll = false
            )
        )
        assertFalse(
            ForgivingTap.isForgivingClick(
                durationMs = 200L,
                travelPx = 61f,
                slopPx = slop,
                didListScroll = false
            )
        )
        assertFalse(
            ForgivingTap.shouldClick(
                durationMs = 200L,
                travelPx = 100f,
                slopPx = slop,
                didListScroll = false
            )
        )
    }

    @Test
    fun `boundary at exactly 3x slop clicks when list did not scroll`() {
        // At slop = 20 px, exactly 60 px is at most 3x slop: allowed.
        assertTrue(
            ForgivingTap.shouldClick(
                durationMs = 200L,
                travelPx = 60f,
                slopPx = slop,
                didListScroll = false
            )
        )
        assertTrue(
            ForgivingTap.isForgivingClick(
                durationMs = 200L,
                travelPx = 60f,
                slopPx = slop,
                didListScroll = false
            )
        )
    }

    // Her three presses on "▶ Ansehen" at the bottom of a conversation, 2026-11-01 16:40:
    // 123 ms / 103 dp, 177 ms / 253 dp, 168 ms / 305 dp, and the list never moved.
    @Test
    fun `long slide that ends on the key it began on clicks`() {
        listOf(123L to 103f, 177L to 253f, 168L to 305f).forEach { (ms, travel) ->
            assertTrue(
                ForgivingTap.isForgivingClick(
                    durationMs = ms,
                    travelPx = travel,
                    slopPx = slop,
                    didListScroll = false,
                    endedInside = true
                )
            )
        }
    }

    @Test
    fun `long slide that ends off the key stays a swipe`() {
        assertFalse(
            ForgivingTap.isForgivingClick(
                durationMs = 177L,
                travelPx = 253f,
                slopPx = slop,
                didListScroll = false,
                endedInside = false
            )
        )
    }

    @Test
    fun `ending inside never overrides a scroll or a long hold`() {
        assertFalse(
            ForgivingTap.isForgivingClick(
                durationMs = 177L,
                travelPx = 253f,
                slopPx = slop,
                didListScroll = true,
                endedInside = true
            )
        )
        assertFalse(
            ForgivingTap.isForgivingClick(
                durationMs = 900L,
                travelPx = 253f,
                slopPx = slop,
                didListScroll = false,
                endedInside = true
            )
        )
    }
}
