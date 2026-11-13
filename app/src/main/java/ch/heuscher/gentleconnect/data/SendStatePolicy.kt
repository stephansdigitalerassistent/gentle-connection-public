package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.SendState

/**
 * What may be claimed about a message we handed to a transport.
 *
 * `ThreemaClient.sendTextMessage` reports whether a line was written to the bridge subprocess.
 * **That is not delivery, and must never be drawn as delivery.** This object exists so that the
 * one place the distinction could be lost is a place that can be tested.
 */
object SendStatePolicy {

    /**
     * How long a message may sit unresolved before it is treated as unfinished.
     *
     * A `PENDING` row is only ever updated by the code that wrote it, so if the process dies
     * between handing a message over and hearing back, that row stays `PENDING` for ever — and a
     * `PENDING` message drawn as an ordinary one is precisely the false statement this whole
     * section exists to prevent. Generous enough that a slow send is not called a failure, short
     * enough that she is not looking at a lie by the time she reads the screen again.
     */
    const val STALE_PENDING_AFTER_MS = 2 * 60 * 1000L

    /** The only thing a transport's acceptance entitles us to say. */
    fun afterHandoff(acceptedForSending: Boolean): SendState =
        if (acceptedForSending) SendState.SENT else SendState.FAILED

    /**
     * Whether this message of hers must be drawn as visibly unfinished.
     *
     * The documented exception to "her screen carries no error copy". Everywhere else, hiding a
     * fault costs her an explanation she would not have wanted. Here, hiding it means her message
     * sits in the conversation looking exactly like one that arrived, and she believes she has
     * answered her daughter when she has not. That silence would not be discretion; it would be a
     * false statement about something she did.
     *
     * It is still not an error message. The message simply stays visibly unfinished, the way a
     * half-written note is obviously half-written.
     */
    fun isUnfinished(state: SendState?, sentAt: Long, now: Long): Boolean = when (state) {
        null, SendState.SENT -> false
        SendState.FAILED -> true
        SendState.PENDING -> now - sentAt >= STALE_PENDING_AFTER_MS
    }
}
