package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.StepDays
import ch.heuscher.gentleconnect.model.StepState
import ch.heuscher.gentleconnect.model.StepsShown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Step counting logic and number formatting tests.
 *
 * Verifies accumulation across reboots, day rollovers, gaps, display thresholds, and
 * locale-independent formatting.
 */
class StepDaysTest {

    private val d0 = 1_000_000L
    private val d1 = d0 + 86_400_000L
    private val d2 = d1 + 86_400_000L
    private val d3 = d2 + 86_400_000L

    @Test
    fun `first reading is baseline with today zero and yesterday null`() {
        val state = StepDays.advance(state = null, raw = 5000L, today = d1, previousDay = d0)
        assertEquals(StepState(day = d1, today = 0L, yesterday = null, lastRaw = 5000L), state)
    }

    @Test
    fun `accumulation within a day adds raw delta`() {
        val s0 = StepDays.advance(state = null, raw = 1000L, today = d1, previousDay = d0)
        val s1 = StepDays.advance(state = s0, raw = 1450L, today = d1, previousDay = d0)
        assertEquals(StepState(day = d1, today = 450L, yesterday = null, lastRaw = 1450L), s1)

        val s2 = StepDays.advance(state = s1, raw = 2000L, today = d1, previousDay = d0)
        assertEquals(StepState(day = d1, today = 1000L, yesterday = null, lastRaw = 2000L), s2)
    }

    @Test
    fun `reboot resets raw counter and adds full reading as delta`() {
        // Raw counter dropped from 10_000 to 50 due to device reboot.
        val s0 = StepState(day = d1, today = 2000L, yesterday = 4000L, lastRaw = 10_000L)
        val s1 = StepDays.advance(state = s0, raw = 50L, today = d1, previousDay = d0)
        assertEquals(StepState(day = d1, today = 2050L, yesterday = 4000L, lastRaw = 50L), s1)
    }

    @Test
    fun `day rollover moves today to yesterday and starts new today with delta`() {
        val s0 = StepState(day = d1, today = 4512L, yesterday = 3200L, lastRaw = 5000L)
        // Advance into d2 (where previousDay is d1)
        val s1 = StepDays.advance(state = s0, raw = 5120L, today = d2, previousDay = d1)
        assertEquals(StepState(day = d2, today = 120L, yesterday = 4512L, lastRaw = 5120L), s1)
    }

    @Test
    fun `gap of two or more days clears yesterday and zeroes today`() {
        val s0 = StepState(day = d0, today = 6000L, yesterday = 4000L, lastRaw = 5000L)
        // Advance straight to d2 or d3 (gap > 1 day)
        val s1 = StepDays.advance(state = s0, raw = 8000L, today = d2, previousDay = d1)
        assertEquals(StepState(day = d2, today = 0L, yesterday = null, lastRaw = 8000L), s1)
    }

    @Test
    fun `future stored day clears yesterday and zeroes today`() {
        // Clock moved back: state is in d2 while today is d1
        val s0 = StepState(day = d2, today = 6000L, yesterday = 4000L, lastRaw = 5000L)
        val s1 = StepDays.advance(state = s0, raw = 8000L, today = d1, previousDay = d0)
        assertEquals(StepState(day = d1, today = 0L, yesterday = null, lastRaw = 8000L), s1)
    }

    @Test
    fun `shown threshold hides today until three thousand steps`() {
        val s2999 = StepState(day = d1, today = 2999L, yesterday = 5000L, lastRaw = 3000L)
        val shown2999 = StepDays.shown(s2999, today = d1, previousDay = d0)
        assertNull(shown2999.today)
        assertEquals(5000L, shown2999.yesterday)

        val s3000 = StepState(day = d1, today = 3000L, yesterday = 5000L, lastRaw = 3001L)
        val shown3000 = StepDays.shown(s3000, today = d1, previousDay = d0)
        assertEquals(3000L, shown3000.today)
        assertEquals(5000L, shown3000.yesterday)

        val s4512 = StepState(day = d1, today = 4512L, yesterday = 0L, lastRaw = 4600L)
        val shown4512 = StepDays.shown(s4512, today = d1, previousDay = d0)
        assertEquals(4512L, shown4512.today)
        assertEquals(0L, shown4512.yesterday)
    }

    @Test
    fun `shown rollover without reading rolls today to yesterday and leaves today null`() {
        // Stored state is from d1, but clock is now at d2 without a new sensor event
        val s = StepState(day = d1, today = 6230L, yesterday = 4000L, lastRaw = 7000L)
        val shown = StepDays.shown(s, today = d2, previousDay = d1)
        assertNull(shown.today)
        assertEquals(6230L, shown.yesterday)
    }

    @Test
    fun `shown with older state returns both null`() {
        // State is from d0, but clock is at d2
        val s = StepState(day = d0, today = 6230L, yesterday = 4000L, lastRaw = 7000L)
        val shown = StepDays.shown(s, today = d2, previousDay = d1)
        assertEquals(StepsShown(today = null, yesterday = null), shown)
    }

    @Test
    fun `shown with null state returns both null`() {
        val shown = StepDays.shown(null, today = d1, previousDay = d0)
        assertEquals(StepsShown(today = null, yesterday = null), shown)
    }

    @Test
    fun `formatting uses Swiss apostrophe thousands separator without locale dependency`() {
        assertEquals("0", StepDays.format(0L))
        assertEquals("812", StepDays.format(812L))
        assertEquals("3'000", StepDays.format(3000L))
        assertEquals("4'512", StepDays.format(4512L))
        assertEquals("12'030", StepDays.format(12030L))
        assertEquals("1'234'567", StepDays.format(1234567L))
    }
}
