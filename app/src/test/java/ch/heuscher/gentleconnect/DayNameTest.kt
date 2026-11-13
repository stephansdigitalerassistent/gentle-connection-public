package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.ui.DAY_MS
import ch.heuscher.gentleconnect.ui.DayName
import ch.heuscher.gentleconnect.ui.dayNameFor
import ch.heuscher.gentleconnect.ui.startOfDay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which day something is said to have happened on.
 *
 * This had a bug in it that reached the phone: everything older than yesterday was being called
 * "Yesterday", so a message from last month read as though it had come in while she slept. The
 * rule was untestable then because it was tangled up with reading string resources; it is a plain
 * function now, and this is the test it should always have had.
 */
class DayNameTest {

    /** Half past two in the afternoon, so that "today" has hours on either side of it. */
    private val now = startOfDay(1_700_000_000_000L) + 14 * 60 * 60 * 1000L

    @Test
    fun `something from this afternoon is today`() {
        assertEquals(DayName.TODAY, dayNameFor(now, now))
        assertEquals(DayName.TODAY, dayNameFor(startOfDay(now), now))
    }

    @Test
    fun `something from last night is yesterday`() {
        assertEquals(DayName.YESTERDAY, dayNameFor(now - DAY_MS, now))
    }

    @Test
    fun `something from last month is a date, not yesterday`() {
        // The bug. Everything that was not today read as "Yesterday", so a message from weeks ago
        // looked new — to the person least able to work out that it was not.
        assertEquals(DayName.DATE, dayNameFor(now - 30 * DAY_MS, now))
        assertEquals(DayName.DATE, dayNameFor(now - 2 * DAY_MS, now))
    }

    @Test
    fun `the day turns at midnight, not at the twenty-four hour mark`() {
        // A minute before midnight yesterday is yesterday, even though it is under 24 hours ago;
        // a minute after midnight today is today, for the same reason.
        val midnightToday = startOfDay(now)
        assertEquals(DayName.YESTERDAY, dayNameFor(midnightToday - 60_000L, now))
        assertEquals(DayName.TODAY, dayNameFor(midnightToday + 60_000L, now))
    }

    @Test
    fun `a fault reported in the small hours is still today`() {
        // The caregiver's log dates things the same way, and it is read long after the fact.
        val threeInTheMorning = startOfDay(now) + 3 * 60 * 60 * 1000L
        assertEquals(DayName.TODAY, dayNameFor(threeInTheMorning, now))
    }
}
