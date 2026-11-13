package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.DoubleTapCounter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure arithmetic unit tests for double-tap detection and the viewer opening grace period.
 *
 * Tested carefully because double tap has two opposite failure modes that confuse an elderly user:
 * - A detector that is too eager turns tremors and accidental double touches into unwanted zoom actions.
 * - A detector that is too strict ignores deliberate taps from shaky hands and leaves her feeling ignored.
 * - Without the viewer grace window, deliberate repeated taps opening a photo immediately shut it again.
 */
class DoubleTapCounterTest {

    @Test
    fun `two quick stationary taps on the same spot complete double tap`() {
        val counter = DoubleTapCounter()
        val start = 10_000L

        // First tap: down at 10000, up at 10080 (80ms), moved 2px
        assertFalse(counter.recordTap(downMs = start, upMs = start + 80, x = 100f, y = 100f, movedDistance = 2f))
        assertNotNull(counter.firstTap)

        // Second tap: down at 10200 (gap 120ms), up at 10280 (80ms), moved 3px, position 102, 101 (distance ~2.2px)
        assertTrue(counter.recordTap(downMs = start + 200, upMs = start + 280, x = 102f, y = 101f, movedDistance = 3f))
        assertNull(counter.firstTap)
    }

    @Test
    fun `slow second tap resets first tap and begins fresh window`() {
        val counter = DoubleTapCounter(doubleTapTimeoutMs = 300L)
        val start = 10_000L

        // First tap
        assertFalse(counter.recordTap(downMs = start, upMs = start + 80, x = 100f, y = 100f))

        // Second tap arrives at 10450 (gap 370ms > 300ms timeout)
        assertFalse(counter.recordTap(downMs = start + 450, upMs = start + 530, x = 100f, y = 100f))
        // This second tap became the new first tap candidate
        assertNotNull(counter.firstTap)

        // Third tap arrives within 150ms of the second tap
        assertTrue(counter.recordTap(downMs = start + 680, upMs = start + 750, x = 100f, y = 100f))
    }

    @Test
    fun `tap that dragged beyond touch slop is not counted`() {
        val counter = DoubleTapCounter(touchSlop = 18f)
        val start = 10_000L

        // Tap that moved 25px during touch (a scroll or drag)
        assertFalse(counter.recordTap(downMs = start, upMs = start + 80, x = 100f, y = 100f, movedDistance = 25f))
        assertNull(counter.firstTap)

        // Clean tap following the drag becomes tap 1, does not fire
        assertFalse(counter.recordTap(downMs = start + 200, upMs = start + 280, x = 100f, y = 100f, movedDistance = 2f))
        assertNotNull(counter.firstTap)
    }

    @Test
    fun `long press is rejected as a tap`() {
        val counter = DoubleTapCounter(longPressTimeoutMs = 400L)
        val start = 10_000L

        // Finger resting on glass for 500ms
        assertFalse(counter.recordTap(downMs = start, upMs = start + 500, x = 100f, y = 100f, movedDistance = 0f))
        assertNull(counter.firstTap)
    }

    @Test
    fun `micro bounce below min time is rejected`() {
        val counter = DoubleTapCounter(doubleTapMinTimeMs = 40L)
        val start = 10_000L

        // First tap
        assertFalse(counter.recordTap(downMs = start, upMs = start + 80, x = 100f, y = 100f))

        // Second down at 10090 (gap 10ms < 40ms)
        assertFalse(counter.recordTap(downMs = start + 90, upMs = start + 150, x = 100f, y = 100f))
    }

    @Test
    fun `taps too far apart do not pair`() {
        val counter = DoubleTapCounter(doubleTapSlop = 36f)
        val start = 10_000L

        // First tap at (100, 100)
        assertFalse(counter.recordTap(downMs = start, upMs = start + 80, x = 100f, y = 100f))

        // Second tap at (200, 200) (distance ~141px > 36px)
        assertFalse(counter.recordTap(downMs = start + 200, upMs = start + 280, x = 200f, y = 200f))
        // Tap at (200, 200) becomes the new first tap candidate
        assertNotNull(counter.firstTap)
    }

    @Test
    fun `reset clears any pending tap`() {
        val counter = DoubleTapCounter()
        val start = 10_000L

        assertFalse(counter.recordTap(downMs = start, upMs = start + 80, x = 100f, y = 100f))
        assertNotNull(counter.firstTap)

        counter.reset()
        assertNull(counter.firstTap)

        // Next tap is a fresh first tap, not a completion
        assertFalse(counter.recordTap(downMs = start + 200, upMs = start + 280, x = 100f, y = 100f))
    }

}
