package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.SendState

/**
 * What happens to a message of hers that did not go.
 *
 * Found on the phone: two emoji to her family were recorded as failed and stayed that way for
 * ever. The transport was asked once, thirty seconds passed, and that was the end of it — the
 * bridge had not finished its handshake yet, which is the ordinary state of the phone for the
 * first minute after it starts. Nothing retried, and there was no way for her to ask again. What
 * she saw was a message she had sent that never arrived, with nothing to do about it.
 *
 * Two answers here. The transport is waited for rather than asked once. And the unfinished
 * message can be sent again by holding it.
 *
 * Pure, because CI here runs JVM unit tests only.
 */
object SendRetryPolicy {

    /**
     * How long a send waits for the messenger to come up before giving up.
     *
     * The subprocess has to start, connect and complete a handshake, and it does all of that
     * again after every restart of the phone. Long enough to cover that; short enough that a
     * genuinely dead transport does not leave her holding a phone that appears to be thinking.
     */
    const val WAIT_FOR_TRANSPORT_MS = 20_000L

    /**
     * How long she holds an unfinished message to send it again.
     *
     * Five seconds is a very long press, and deliberately so. This is the one gesture in the app
     * that acts on a message rather than opening it, it has no visible control of its own, and
     * the cost of triggering it by resting a thumb on the screen is her family receiving the same
     * words twice with no explanation. A press this long cannot be an accident — and because the
     * bar fills while she holds, a press that was an accident shows her what it is about to do
     * with four seconds left to lift her finger.
     */
    const val HOLD_TO_RESEND_MS = 5_000L

    /**
     * Whether holding this message may send it again.
     *
     * Only her own, and only when it is visibly unfinished — a message that arrived is not
     * something to send twice.
     *
     * The channel used to decide this too, because the only resend that existed went over
     * Threema. That was the implementation showing through the rule: a text message that did not
     * go is the case most in need of a second attempt, since unlike the messenger there is no
     * handshake to wait for and no reason to think the next attempt will fail the same way. The
     * dispatch now covers every channel, so nothing here has to.
     */
    fun mayResend(
        isOutgoing: Boolean,
        state: SendState?,
        sentAt: Long,
        now: Long
    ): Boolean =
        isOutgoing && SendStatePolicy.isUnfinished(state, sentAt, now)

    /**
     * How full the bar is after holding for this long, 0..1.
     *
     * Clamped at both ends: a negative elapsed time is a clock that moved, not a reason to draw
     * a bar backwards.
     */
    fun holdProgress(elapsedMs: Long): Float =
        (elapsedMs.toFloat() / HOLD_TO_RESEND_MS).coerceIn(0f, 1f)
}
