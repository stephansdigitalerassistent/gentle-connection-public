package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.ui.components.CaregiverTapCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure arithmetic unit tests for the caregiver 5-tap gesture detection window.
 *
 * The gesture exists so a caregiver standing beside the user can unlock diagnostic tools and
 * destructive actions without navigating through menus or typing a password. It requires five
 * deliberate taps within 1500ms of the first tap.
 */
class CaregiverTapCounterTest {

    @Test
    fun `five taps within 1500ms completes successfully`() {
        val counter = CaregiverTapCounter(targetCount = 5, windowMs = 1500L)
        val startTime = 10_000L

        // Taps 1 to 4 should not fire
        assertFalse(counter.recordTap(startTime))
        assertFalse(counter.recordTap(startTime + 200))
        assertFalse(counter.recordTap(startTime + 500))
        assertFalse(counter.recordTap(startTime + 800))

        // 5th tap at 1200ms (within 1500ms window) fires
        assertTrue(counter.recordTap(startTime + 1200))
    }

    @Test
    fun `slow taps reset the window and do not fire`() {
        val counter = CaregiverTapCounter(targetCount = 5, windowMs = 1500L)
        val startTime = 10_000L

        // Four fast taps
        assertFalse(counter.recordTap(startTime))
        assertFalse(counter.recordTap(startTime + 200))
        assertFalse(counter.recordTap(startTime + 400))
        assertFalse(counter.recordTap(startTime + 600))

        // Fifth tap arrives past the 1500ms window (at 1600ms)
        // Window resets with this tap as tap #1 of the new window
        assertFalse(counter.recordTap(startTime + 1600))

        // Four more fast taps from the new start time complete the new window
        val newStart = startTime + 1600
        assertFalse(counter.recordTap(newStart + 200))
        assertFalse(counter.recordTap(newStart + 400))
        assertFalse(counter.recordTap(newStart + 600))
        assertTrue(counter.recordTap(newStart + 800))
    }

    @Test
    fun `taps after completion start a fresh window`() {
        val counter = CaregiverTapCounter(targetCount = 5, windowMs = 1500L)
        val startTime = 10_000L

        // Complete first window
        assertFalse(counter.recordTap(startTime))
        assertFalse(counter.recordTap(startTime + 100))
        assertFalse(counter.recordTap(startTime + 200))
        assertFalse(counter.recordTap(startTime + 300))
        assertTrue(counter.recordTap(startTime + 400))

        // Immediate next tap must not fire; it begins count 1 of a new cycle
        assertFalse(counter.recordTap(startTime + 500))
    }

    @Test
    fun `reset clears all accumulated taps`() {
        val counter = CaregiverTapCounter(targetCount = 5, windowMs = 1500L)
        val startTime = 10_000L

        counter.recordTap(startTime)
        counter.recordTap(startTime + 100)
        counter.recordTap(startTime + 200)
        counter.recordTap(startTime + 300)

        counter.reset()

        // After reset, 1 tap should not fire
        assertFalse(counter.recordTap(startTime + 400))
    }
}
