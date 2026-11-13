package ch.heuscher.gentleconnect.data

/**
 * When her address book may be matched against Threema.
 *
 * Found on the phone, in the bridge's own log: `POST /identity/match` answered **429 Too Many
 * Requests**, over and over. The app was submitting 1186 hashed numbers — a 56 KB request — every
 * single time the messenger connected, and it connects again after every restart of the app or
 * the phone. Eight restarts in an afternoon is eight full address-book matches, and Threema's
 * directory rate-limits exactly that. The caregiver log filled up with CONTACT_SYNC_FAILED, and
 * the real cost was invisible: nothing on her screen breaks when matching fails, people simply
 * never become reachable.
 *
 * So matching is no longer something that happens. It is something a caregiver does, from the
 * caregiver dialog, and this decides whether the request may go out at all.
 *
 * Pure, because CI here runs JVM unit tests only.
 */
object ContactSyncPolicy {

    /**
     * The shortest gap between two matches of the whole address book.
     *
     * An hour is not about being polite to a server. It is what keeps a caregiver pressing the
     * button twice — because nothing visibly happened the first time — from spending the day's
     * allowance and leaving the next real attempt rejected. Matching is also not urgent: it
     * changes who *can* be reached, and the answer is the same an hour from now.
     */
    const val MIN_GAP_MS = 60 * 60 * 1000L

    /**
     * How long to wait for the directory's answer before calling it a failure.
     *
     * Found on the phone: the bridge subprocess exited in the same 94 milliseconds the request
     * was written to it, the write landed in a pipe nobody was reading, and the dialog sat on
     * "Checking…" for ever. A caregiver watching a button that never resolves learns nothing and
     * cannot even press it again. Saying "it did not work" after half a minute is both true and
     * useful; saying nothing is neither.
     */
    const val ANSWER_TIMEOUT_MS = 30_000L

    /**
     * Whether a match may be sent now.
     *
     * Counted from the last **attempt**, not the last success. A rejected request is exactly what
     * the rate limit counts, so retrying it immediately is the one thing guaranteed not to work.
     *
     * @param lastAttemptAt when a match was last sent, or null if never.
     */
    fun mayRun(lastAttemptAt: Long?, now: Long): Boolean {
        if (lastAttemptAt == null) return true
        // A clock that has moved backwards — a timezone change, a reboot — must not lock matching
        // out until the future catches up.
        if (now < lastAttemptAt) return true
        return now - lastAttemptAt >= MIN_GAP_MS
    }

    /** When the next match may be sent, for telling the caregiver rather than refusing silently. */
    fun nextAllowedAt(lastAttemptAt: Long?): Long? =
        lastAttemptAt?.plus(MIN_GAP_MS)

    /**
     * What the caregiver is told about a match, and the app's own state while one is in flight.
     *
     * Named states rather than a pair of booleans, because "running" and "failed" and "too soon"
     * are three different things to say and the dialog has to say exactly one of them.
     */
    sealed interface Outcome {
        /** Nothing has been asked for yet in this session. */
        data object Idle : Outcome

        /** The request is out and the directory has not answered. */
        data object Running : Outcome

        /** @param onThreema how many of [submitted] addresses came back with an identity. */
        data class Matched(val onThreema: Int, val submitted: Int) : Outcome

        /** The directory refused or could not be reached. */
        data object Failed : Outcome

        /** @param nextAllowedAt when pressing again will be allowed. */
        data class TooSoon(val nextAllowedAt: Long) : Outcome
    }
}
