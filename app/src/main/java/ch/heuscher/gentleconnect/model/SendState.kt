package ch.heuscher.gentleconnect.model

/**
 * How far one of her own messages got. Incoming messages have none of this.
 *
 * The names are deliberately modest. [SENT] means the transport accepted it — for the embedded
 * Threema client, that a line was written to the subprocess — and it says **nothing whatsoever
 * about the other person having received it**. Threema delivery receipts (CSP `0x80`) would let us
 * say more, and are deferred to their own stage: a tick she cannot trust is worse than no tick.
 */
enum class SendState { PENDING, SENT, FAILED }
