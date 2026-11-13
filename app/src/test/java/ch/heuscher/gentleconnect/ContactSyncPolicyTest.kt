package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.ContactSyncPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSyncPolicyTest {

    private val now = 10_000_000L

    @Test
    fun `the first match is always allowed`() {
        assertTrue(ContactSyncPolicy.mayRun(lastAttemptAt = null, now = now))
    }

    @Test
    fun `pressing twice in a row does not send twice`() {
        // The exact thing that produced 429 after 429: nothing visibly happens, so it gets
        // pressed again, which is the one action guaranteed not to work.
        assertFalse(ContactSyncPolicy.mayRun(lastAttemptAt = now - 1000, now = now))
    }

    @Test
    fun `an hour later it is allowed again`() {
        assertTrue(ContactSyncPolicy.mayRun(lastAttemptAt = now - ContactSyncPolicy.MIN_GAP_MS, now = now))
        assertTrue(ContactSyncPolicy.mayRun(lastAttemptAt = now - ContactSyncPolicy.MIN_GAP_MS - 1, now = now))
    }

    @Test
    fun `a minute before the hour it is still refused`() {
        val justShort = now - ContactSyncPolicy.MIN_GAP_MS + 60_000
        assertFalse(ContactSyncPolicy.mayRun(lastAttemptAt = justShort, now = now))
    }

    @Test
    fun `a rejected attempt still counts`() {
        // Counted from the attempt rather than the success, because a rejection is what the rate
        // limit is counting.
        assertFalse(ContactSyncPolicy.mayRun(lastAttemptAt = now - 5_000, now = now))
    }

    @Test
    fun `a clock that moved backwards does not lock matching out`() {
        // A timezone change or a reboot must not leave the caregiver waiting for the future.
        assertTrue(ContactSyncPolicy.mayRun(lastAttemptAt = now + 5 * ContactSyncPolicy.MIN_GAP_MS, now = now))
    }

    @Test
    fun `the caregiver can be told when to come back`() {
        assertEquals(now + ContactSyncPolicy.MIN_GAP_MS, ContactSyncPolicy.nextAllowedAt(now))
        assertNull(ContactSyncPolicy.nextAllowedAt(null))
    }

    @Test
    fun `an outcome says one thing at a time`() {
        val matched: ContactSyncPolicy.Outcome = ContactSyncPolicy.Outcome.Matched(onThreema = 4, submitted = 1186)
        assertTrue(matched is ContactSyncPolicy.Outcome.Matched)
        assertEquals(4, (matched as ContactSyncPolicy.Outcome.Matched).onThreema)
        assertEquals(1186, matched.submitted)
    }

    @Test
    fun `waiting for an answer ends`() {
        // The dialog sat on "Checking..." for ever when the bridge died mid-request. A caregiver
        // watching a button that never resolves learns nothing and cannot press it again.
        assertTrue(ContactSyncPolicy.ANSWER_TIMEOUT_MS in 5_000L..120_000L)
        assertTrue(ContactSyncPolicy.ANSWER_TIMEOUT_MS < ContactSyncPolicy.MIN_GAP_MS)
    }

    @Test
    fun `the gap is long enough to matter and short enough to be usable`() {
        assertTrue(ContactSyncPolicy.MIN_GAP_MS >= 30 * 60 * 1000L)
        assertTrue(ContactSyncPolicy.MIN_GAP_MS <= 24 * 60 * 60 * 1000L)
    }
}
