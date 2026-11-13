package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.CallCourtesy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to a WhatsApp call nobody here can answer.
 *
 * Both decisions in here act on their own with nobody watching: one puts a missed call on her
 * home screen, the other writes to a member of her family under her name.
 */
class CallCourtesyTest {

    private val ring = 30_000L

    @Test
    fun `a call that rang out and stopped was missed`() {
        assertTrue(CallCourtesy.missed("timeout", answeredElsewhere = false, rangForMs = ring))
    }

    @Test
    fun `a reason nobody has seen before still counts as missed`() {
        // The list is of what is known to mean answered; anything else is missed. A reason
        // WhatsApp renames must not turn this feature off silently.
        assertTrue(CallCourtesy.missed("some_new_word", answeredElsewhere = false, rangForMs = ring))
    }

    @Test
    fun `a call this phone turned away counts as missed`() {
        // So that turning WhatsApp calls away still puts the call on her screen and still tells a
        // caller she knows how to reach her.
        assertTrue(CallCourtesy.missed(CallCourtesy.REJECTED_HERE, answeredElsewhere = false, rangForMs = 800L))
    }

    @Test
    fun `a call WhatsApp says was picked up was not missed`() {
        assertFalse(CallCourtesy.missed("timeout", answeredElsewhere = true, rangForMs = ring))
    }

    @Test
    fun `a call that ended because it was accepted was not missed`() {
        assertFalse(CallCourtesy.missed("accept", answeredElsewhere = false, rangForMs = ring))
        assertFalse(CallCourtesy.missed("ACCEPT", answeredElsewhere = false, rangForMs = ring))
        assertFalse(CallCourtesy.missed("accept_elsewhere", answeredElsewhere = false, rangForMs = ring))
    }

    @Test
    fun `a call that lasted ten minutes was a conversation, whatever it says it ended for`() {
        // She does pick up occasionally — by accident, or because somebody was with her. Writing
        // "calls do not arrive here" to a person who has just spoken to her would be the one
        // actively confusing failure this can have.
        val tenMinutes = 10 * 60 * 1000L

        assertFalse(CallCourtesy.missed("", answeredElsewhere = false, rangForMs = tenMinutes))
    }

    @Test
    fun `a call ringing for the whole of its ring is still missed`() {
        assertTrue(
            CallCourtesy.missed("", answeredElsewhere = false, rangForMs = CallCourtesy.LONGEST_RING_MS)
        )
    }

    @Test
    fun `somebody in her address book is told`() {
        assertTrue(
            CallCourtesy.worthTelling(
                knownCaller = true,
                group = false,
                toldLastAt = 0L,
                now = 1_786_200_000_000L
            )
        )
    }

    @Test
    fun `a stranger is told nothing at all`() {
        // A reply tells whoever dialled that this number is live and answered by somebody.
        assertFalse(
            CallCourtesy.worthTelling(
                knownCaller = false,
                group = false,
                toldLastAt = 0L,
                now = 1_786_200_000_000L
            )
        )
    }

    @Test
    fun `a call to several people at once is not answered with a notice to all of them`() {
        assertFalse(
            CallCourtesy.worthTelling(
                knownCaller = true,
                group = true,
                toldLastAt = 0L,
                now = 1_786_200_000_000L
            )
        )
    }

    @Test
    fun `one person trying four times in an evening is told once`() {
        val first = 1_786_200_000_000L

        assertTrue(CallCourtesy.worthTelling(true, group = false, toldLastAt = 0L, now = first))
        assertFalse(
            CallCourtesy.worthTelling(true, group = false, toldLastAt = first, now = first + 90_000L)
        )
        assertFalse(
            CallCourtesy.worthTelling(
                true,
                group = false,
                toldLastAt = first,
                now = first + CallCourtesy.QUIET_MS
            )
        )
    }

    @Test
    fun `somebody who falls back into the habit next week is reminded`() {
        val told = 1_786_200_000_000L
        val nextWeek = told + 7 * 24 * 60 * 60 * 1000L

        assertTrue(CallCourtesy.worthTelling(true, group = false, toldLastAt = told, now = nextWeek))
    }
}
