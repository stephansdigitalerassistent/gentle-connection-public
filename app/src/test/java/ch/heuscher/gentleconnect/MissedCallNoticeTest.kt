package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.MissedCallNotice
import ch.heuscher.gentleconnect.model.Nudge
import ch.heuscher.gentleconnect.model.MissedCallNotice.dealtWith
import ch.heuscher.gentleconnect.model.MissedCallNotice.worthShowing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a missed call stops being news.
 *
 * The behaviour this replaces was on the phone: press "Call back", have the conversation, and the
 * card still says the call was missed — because nothing anywhere recorded that she had answered
 * it.
 */
class MissedCallNoticeTest {

    private val tenOClock = 1_700_000_000_000L
    private val elevenOClock = tenOClock + 3_600_000L

    /** An hour after the call, so that nothing below is near a boundary by accident. */
    private val soonAfter = tenOClock + 60_000L

    private fun hours(n: Int) = n * 3_600_000L

    @Test
    fun `a call nobody has done anything about is shown`() {
        assertTrue(worthShowing(callAt = tenOClock, dealtWithUpTo = 0L, now = soonAfter))
    }

    @Test
    fun `calling back takes the card away`() {
        val after = dealtWith(callAt = tenOClock, dealtWithUpTo = 0L)
        assertFalse(worthShowing(callAt = tenOClock, dealtWithUpTo = after, now = soonAfter))
    }

    @Test
    fun `a call that comes in while she is dealing with the last one survives`() {
        // The reason the moment recorded is the call's, not the button's: her daughter rings again
        // while she is pressing "Call back" on the first one. That second call is news.
        val after = dealtWith(callAt = tenOClock, dealtWithUpTo = 0L)
        assertTrue(worthShowing(callAt = elevenOClock, dealtWithUpTo = after, now = elevenOClock + 60_000L))
    }

    @Test
    fun `dealing with a newer call does not bring an older one back`() {
        // She calls back the eleven o'clock one first. The ten o'clock card must stay gone.
        var seen = dealtWith(callAt = elevenOClock, dealtWithUpTo = 0L)
        seen = dealtWith(callAt = tenOClock, dealtWithUpTo = seen)

        assertEquals(elevenOClock, seen)
        assertFalse(worthShowing(callAt = tenOClock, dealtWithUpTo = seen, now = soonAfter))
        assertFalse(worthShowing(callAt = elevenOClock, dealtWithUpTo = seen, now = soonAfter))
    }

    // How long the card stays, which a caregiver chooses on the website. Before this it was a day
    // for everybody, written into the repository that read the call log.

    @Test
    fun `it goes when the chosen time is up`() {
        val twoHours = 2

        assertTrue(
            worthShowing(tenOClock, 0L, now = tenOClock + hours(1), forHours = twoHours)
        )
        assertFalse(
            worthShowing(tenOClock, 0L, now = tenOClock + hours(3), forHours = twoHours)
        )
    }

    @Test
    fun `the moment it is up, it is up`() {
        // Exactly two hours later is no longer within two hours. Written down because a boundary
        // nobody has decided is a boundary somebody changes by accident later.
        assertFalse(worthShowing(tenOClock, 0L, now = tenOClock + hours(2), forHours = 2))
    }

    @Test
    fun `never means the card does not appear at all`() {
        assertFalse(
            worthShowing(tenOClock, 0L, now = soonAfter, forHours = MissedCallNotice.NEVER)
        )
    }

    @Test
    fun `forever means only she takes it away`() {
        // A fortnight later, still there.
        assertTrue(
            worthShowing(tenOClock, 0L, now = tenOClock + hours(24 * 14), forHours = MissedCallNotice.FOREVER)
        )
    }

    @Test
    fun `calling back still clears it, whatever the length`() {
        // The two rules are separate and both have to hold. Forever is about time, not about her.
        val after = dealtWith(callAt = tenOClock, dealtWithUpTo = 0L)

        assertFalse(
            worthShowing(tenOClock, after, now = soonAfter, forHours = MissedCallNotice.FOREVER)
        )
    }

    @Test
    fun `a length this phone does not know is read as a day`() {
        // A number from a newer website, or a typo. Silently permanent and silently absent are
        // both worse than the answer the phone gave before any of this was configurable.
        assertEquals(MissedCallNotice.DEFAULT_HOURS, MissedCallNotice.sane(3))
        assertEquals(MissedCallNotice.DEFAULT_HOURS, MissedCallNotice.sane(-2))
        assertEquals(MissedCallNotice.DEFAULT_HOURS, MissedCallNotice.sane(1000))

        assertTrue(worthShowing(tenOClock, 0L, now = tenOClock + hours(23), forHours = 3))
        assertFalse(worthShowing(tenOClock, 0L, now = tenOClock + hours(25), forHours = 3))
    }

    @Test
    fun `every choice the website offers is one the phone acts on`() {
        // The guard against the list on the website and the list here drifting apart.
        MissedCallNotice.CHOICES.forEach { choice ->
            assertEquals("choice $choice", choice, MissedCallNotice.sane(choice))
        }
    }

    private fun missed(number: String, at: Long) =
        Nudge.MissedCallNudge(contactName = "", timestamp = at, number = number)

    private val anna = Contact(id = "1", name = "Anna", phoneNumber = "079 123 45 67")
    private val beat = Contact(id = "2", name = "Beat", phoneNumber = "+41 78 555 01 01")
    private val noNumber = Contact(id = "3", name = "Carla", phoneNumber = "")

    @Test
    fun `every call still open is marked, not only the newest`() {
        val calls = listOf(missed("+41791234567", elevenOClock), missed("+41785550101", tenOClock))
        val open = MissedCallNotice.stillOpen(calls, dealtWithUpTo = 0L, now = elevenOClock + 60_000L)
        assertEquals(2, open.size)
        assertTrue(MissedCallNotice.rang(anna, open))
        assertTrue(MissedCallNotice.rang(beat, open))
        assertFalse(MissedCallNotice.rang(noNumber, open))
    }

    @Test
    fun `what she has dealt with or what is too old is not marked`() {
        val calls = listOf(missed("+41791234567", elevenOClock), missed("+41785550101", tenOClock))
        val open = MissedCallNotice.stillOpen(calls, dealtWithUpTo = tenOClock, now = elevenOClock + 60_000L)
        assertEquals(listOf(calls[0]), open)
        assertTrue(MissedCallNotice.stillOpen(calls, 0L, now = elevenOClock + hours(25)).isEmpty())
        assertTrue(MissedCallNotice.stillOpen(calls, 0L, now = soonAfter, forHours = MissedCallNotice.NEVER).isEmpty())
    }

    @Test
    fun `leaving the calls clears up to the newest call she was shown and no further`() {
        val open = listOf(missed("+41791234567", elevenOClock), missed("+41785550101", tenOClock))
        val after = MissedCallNotice.allSeen(open, dealtWithUpTo = 0L)
        assertEquals(elevenOClock, after)
        // A call ringing in as she leaves is still news when she comes back.
        assertTrue(worthShowing(callAt = elevenOClock + 1, dealtWithUpTo = after, now = elevenOClock + 60_000L))
        // Nothing shown, nothing moved; and never backwards.
        assertEquals(tenOClock, MissedCallNotice.allSeen(emptyList(), dealtWithUpTo = tenOClock))
        assertEquals(elevenOClock + 5, MissedCallNotice.allSeen(open, dealtWithUpTo = elevenOClock + 5))
    }

    @Test
    fun `a caller nobody has is found only in the list of calls`() {
        assertFalse(MissedCallNotice.anyFromStrangers(listOf(missed("+41791234567", tenOClock)), listOf(anna, beat)))
        assertTrue(MissedCallNotice.anyFromStrangers(listOf(missed("+41315550110", tenOClock)), listOf(anna, beat)))
        // Withheld: no row can be marked for it.
        assertTrue(MissedCallNotice.anyFromStrangers(listOf(missed("", tenOClock)), listOf(anna, beat)))
    }
}
