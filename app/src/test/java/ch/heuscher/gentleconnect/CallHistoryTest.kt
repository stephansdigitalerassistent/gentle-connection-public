package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.CallDirection
import ch.heuscher.gentleconnect.model.CallHistory
import ch.heuscher.gentleconnect.model.CallRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that turn the telephone's record of calls into a list for her.
 *
 * Both of them are about the same failure: her call list saying that four things happened this
 * afternoon when one did.
 */
class CallHistoryTest {

    private var clock = 1_700_000_000_000L

    private fun call(
        number: String,
        direction: CallDirection = CallDirection.OUTGOING,
        name: String? = null
    ): CallRecord {
        clock -= 60_000
        return CallRecord(
            id = "id-$clock",
            name = name,
            number = number,
            direction = direction,
            timestamp = clock
        )
    }

    @Test
    fun `a run of calls to the same number becomes one row`() {
        val shown = CallHistory.forHer(
            listOf(
                call("+41791234567"),
                call("+41791234567"),
                call("+41791234567")
            )
        )

        assertEquals(1, shown.size)
        // The newest of the run, so the time on the row is the last time she tried.
        assertEquals(1_699_999_940_000L, shown.first().timestamp)
    }

    @Test
    fun `the same number written two ways is still one telephone`() {
        val shown = CallHistory.forHer(
            listOf(
                call("+41 79 123 45 67"),
                call("079 123 45 67")
            )
        )

        assertEquals(1, shown.size)
    }

    @Test
    fun `a missed call does not disappear into the call back`() {
        val shown = CallHistory.forHer(
            listOf(
                call("+41791234567", CallDirection.OUTGOING),
                call("+41791234567", CallDirection.MISSED)
            )
        )

        // "She rang me and I rang her back" is two things that happened, and collapsing them
        // would leave only the half she already knows about.
        assertEquals(2, shown.size)
        assertEquals(CallDirection.OUTGOING, shown[0].direction)
        assertEquals(CallDirection.MISSED, shown[1].direction)
    }

    @Test
    fun `the same person on two different days stays two rows`() {
        val shown = CallHistory.forHer(
            listOf(
                call("+41791234567"),
                call("+41319876543"),
                call("+41791234567")
            )
        )

        // Only consecutive calls collapse. Her daughter this morning and her daughter last
        // Tuesday are two events however few numbers lie between them.
        assertEquals(3, shown.size)
    }

    @Test
    fun `the list is cut to the length a screen is worth scrolling`() {
        val many = (1..200).map { call("+4179000%04d".format(it)) }

        assertEquals(CallHistory.SHOWN, CallHistory.forHer(many).size)
    }

    @Test
    fun `two different numbers sharing a country code are not the same telephone`() {
        assertFalse(CallHistory.sameNumber("+41791234567", "+41797654321"))
    }

    @Test
    fun `a withheld number matches only itself`() {
        assertTrue(CallHistory.sameNumber("", ""))
        assertFalse(CallHistory.sameNumber("", "+41791234567"))
    }

    @Test
    fun `a short code is not confused with a long number ending in the same digits`() {
        // The five digits it has do agree with the end of the long number. Too short to be
        // compared on ends at all, so it has to match exactly — and does not.
        assertFalse(CallHistory.sameNumber("34567", "+41791234567"))
        assertTrue(CallHistory.sameNumber("34567", "34567"))
    }

    @Test
    fun `a name is what the row shows when there is one`() {
        assertTrue(call("+41791234567", name = "Karolina").isKnown)
        assertFalse(call("+41791234567").isKnown)
        assertFalse(call("+41791234567", name = "  ").isKnown)
    }
}
