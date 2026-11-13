package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.SendStatePolicy
import ch.heuscher.gentleconnect.model.SendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendStatePolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a transport accepting a message is all we may record`() {
        assertEquals(SendState.SENT, SendStatePolicy.afterHandoff(true))
        assertEquals(SendState.FAILED, SendStatePolicy.afterHandoff(false))
    }

    @Test
    fun `a message that did not send stays visibly unfinished`() {
        // The documented exception to "her screen carries no error copy". Drawn like anything
        // else, it would look exactly like a message that arrived, and she would believe she had
        // answered her daughter when she had not.
        assertTrue(SendStatePolicy.isUnfinished(SendState.FAILED, sentAt = now, now = now))
    }

    @Test
    fun `a message on its way is not accused of having failed`() {
        assertFalse(SendStatePolicy.isUnfinished(SendState.PENDING, sentAt = now, now = now + 500))
    }

    @Test
    fun `a message nobody ever came back to is unfinished`() {
        // A PENDING row is only ever updated by the code that wrote it, so if the launcher is
        // killed between handing a message over and hearing back, that row stays PENDING for ever.
        // Drawn as an ordinary message it is a lie with no expiry date.
        assertTrue(
            SendStatePolicy.isUnfinished(
                SendState.PENDING,
                sentAt = now,
                now = now + SendStatePolicy.STALE_PENDING_AFTER_MS
            )
        )
    }

    @Test
    fun `nothing that arrived is ever unfinished`() {
        assertFalse(SendStatePolicy.isUnfinished(null, sentAt = 0L, now = now))
        assertFalse(SendStatePolicy.isUnfinished(SendState.SENT, sentAt = 0L, now = now))
    }
}
