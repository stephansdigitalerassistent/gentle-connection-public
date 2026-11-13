package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.SendRetryPolicy
import ch.heuscher.gentleconnect.data.SendStatePolicy
import ch.heuscher.gentleconnect.model.SendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendRetryPolicyTest {

    private val now = 1_000_000L
    private val justNow = now - 1000L
    private val longAgo = now - SendStatePolicy.STALE_PENDING_AFTER_MS - 1

    @Test
    fun `her own message that failed can be sent again, on any channel`() {
        // The channel used to decide this, because the only resend that existed went over
        // Threema — so a text message that failed, the case most worth a second attempt, was the
        // one she could do nothing about. Found the hard way on a message too long to send in
        // one piece.
        assertTrue(
            SendRetryPolicy.mayResend(
                isOutgoing = true,
                state = SendState.FAILED,
                sentAt = justNow,
                now = now
            )
        )
    }

    @Test
    fun `a message that arrived is never resendable`() {
        // Holding somebody else's message must not send anything anywhere.
        assertFalse(
            SendRetryPolicy.mayResend(
                isOutgoing = false,
                state = null,
                sentAt = justNow,
                now = now
            )
        )
    }

    @Test
    fun `a message that went is not offered again`() {
        assertFalse(
            SendRetryPolicy.mayResend(
                isOutgoing = true,
                state = SendState.SENT,
                sentAt = justNow,
                now = now
            )
        )
    }

    @Test
    fun `a send still in flight is left alone`() {
        // It has not failed yet. Sending it again here is how she ends up saying it twice.
        assertFalse(
            SendRetryPolicy.mayResend(
                isOutgoing = true,
                state = SendState.PENDING,
                sentAt = justNow,
                now = now
            )
        )
    }

    @Test
    fun `a send that never came back becomes resendable once it is stale`() {
        assertTrue(
            SendRetryPolicy.mayResend(
                isOutgoing = true,
                state = SendState.PENDING,
                sentAt = longAgo,
                now = now
            )
        )
    }

    @Test
    fun `the bar starts empty and ends full`() {
        assertEquals(0f, SendRetryPolicy.holdProgress(0), 0.001f)
        assertEquals(1f, SendRetryPolicy.holdProgress(SendRetryPolicy.HOLD_TO_RESEND_MS), 0.001f)
    }

    @Test
    fun `the bar is half full halfway through`() {
        assertEquals(0.5f, SendRetryPolicy.holdProgress(SendRetryPolicy.HOLD_TO_RESEND_MS / 2), 0.001f)
    }

    @Test
    fun `the bar never overflows or runs backwards`() {
        assertEquals(1f, SendRetryPolicy.holdProgress(SendRetryPolicy.HOLD_TO_RESEND_MS * 10), 0.001f)
        assertEquals(0f, SendRetryPolicy.holdProgress(-5000), 0.001f)
    }

    @Test
    fun `holding is long enough to be deliberate`() {
        // If this ever drops near a normal long-press, resting a thumb on the screen sends her
        // family the same words twice.
        assertTrue(SendRetryPolicy.HOLD_TO_RESEND_MS >= 3_000L)
    }

    @Test
    fun `waiting for the transport outlasts a handshake`() {
        // The whole point: the failure being fixed here happened in the first seconds after a
        // restart, while the bridge was still connecting.
        assertTrue(SendRetryPolicy.WAIT_FOR_TRANSPORT_MS >= 10_000L)
    }
}
