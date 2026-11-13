package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import ch.heuscher.gentleconnect.model.PersonName

/**
 * The rules the caregiver's error log keeps itself by.
 *
 * Pure, because CI runs JVM unit tests only and every rule here is one that fails silently: a log
 * that floods shows nothing useful, a log that grows without bound is a leak on a phone nobody
 * administers, and a log that writes down identities turns a support screen into a copy of her
 * social graph.
 */
object CaregiverLog {

    /**
     * How many lines are kept.
     *
     * Enough to cover the weeks between one visit and the next, small enough that nobody has to
     * scroll for a quarter of an hour to reach the fault they came for. Raised from 100: one bad
     * afternoon (a messenger reconnecting over and over) could use up a hundred on its own, so
     * the fault somebody came to ask about was already gone. A few hundred short rows are
     * nothing to the database.
     */
    const val MAX_ENTRIES = 300

    /**
     * How many caregiver actions are kept, counted separately from [MAX_ENTRIES].
     *
     * Separate because these two lists compete for the same screen and only one of them is why
     * anybody opens it. A caregiver tidying up deletes a dozen messages in a sitting; if those
     * shared one budget with the faults, an afternoon of tidying would quietly push out the
     * failed send from three weeks ago that somebody is going to come looking for. A deletion is
     * worth writing down — "where did that message go" must have an answer — but it is never
     * worth a fault's place.
     *
     * Smaller than the fault budget on purpose: these are read backwards over a single visit,
     * not over the months between two of them. Was 30, which one thorough tidy-up could fill.
     */
    const val MAX_ACTION_ENTRIES = 100

    /**
     * Whether this kind is somebody's deliberate doing rather than something going wrong.
     *
     * A `when` with no `else`, like [ch.heuscher.gentleconnect.model.CaregiverAlerts.worthNotifying]:
     * a kind added later fails to compile until somebody decides which of the two budgets it is
     * spending, which is the moment to think about it.
     */
    fun isCaregiverAction(kind: CaregiverEventKind): Boolean = when (kind) {
        CaregiverEventKind.MESSAGE_NOT_SENT,
        CaregiverEventKind.GROUP_MESSAGE_PART_SENT,
        CaregiverEventKind.GROUP_MEMBERSHIP_UNKNOWN,
        CaregiverEventKind.GROUP_NO_LONGER_MEMBER,
        CaregiverEventKind.GROUP_NOBODY_LEFT,
        CaregiverEventKind.MESSENGER_CONNECTION_FAILED,
        CaregiverEventKind.CONTACT_SYNC_FAILED,
        CaregiverEventKind.CONTACTS_NOT_UNDERSTOOD,
        CaregiverEventKind.CALL_FROM_STRANGER_ENDED,
        CaregiverEventKind.MMS_DROPPED_UNSUPPORTED,
        CaregiverEventKind.MESSAGES_LOST_ON_ARRIVAL,
        CaregiverEventKind.SMS_NOT_STORED,
        CaregiverEventKind.WHATSAPP_CALL_UNANSWERED,
        CaregiverEventKind.CALL_FAILED_NOT_FROM_ABROAD,
        CaregiverEventKind.CALL_FAILED_NO_CONNECTION,
        CaregiverEventKind.THREEMA_CALL_FROM_STRANGER_ENDED,
        CaregiverEventKind.SUPPORT_OPENED_NOT_CONFIRMED,
        CaregiverEventKind.RESTLESSNESS_DETECTED,
        CaregiverEventKind.MAIL_ACCESS_LOST -> false

        CaregiverEventKind.MESSAGE_DELETED_ON_PHONE,
        CaregiverEventKind.CONVERSATION_DELETED_ON_PHONE,
        CaregiverEventKind.NUMBER_BLOCKED,
        CaregiverEventKind.CONVERSATION_MUTED -> true
    }

    /** The kinds [isCaregiverAction] answers true for, by name, for the database to trim by. */
    val CAREGIVER_ACTION_KINDS: List<String> =
        CaregiverEventKind.entries.filter(::isCaregiverAction).map { it.name }

    /**
     * Whether an incoming event is the same thing happening again rather than something new.
     *
     * Same kind, same subject, and nothing else — deliberately ignoring the counts, so that a
     * fan-out failing at two of five and then at three of five still collapses. The alternative
     * floods: a messenger that cannot connect retries on a timer, and one bad evening would push
     * every other fault off the end of the log.
     */
    fun collapses(newest: CaregiverEvent?, incoming: CaregiverEvent): Boolean {
        if (newest == null) return false
        return newest.kind == incoming.kind &&
            newest.subject.equals(incoming.subject, ignoreCase = true)
    }

    /**
     * Adds an event to a newest-first list, collapsing repeats and dropping the oldest.
     *
     * Used where there is no database to write to — before it is attached, and in tests.
     */
    fun add(existing: List<CaregiverEvent>, incoming: CaregiverEvent): List<CaregiverEvent> {
        val newest = existing.firstOrNull()
        val merged = if (collapses(newest, incoming)) {
            listOf(
                incoming.copy(occurrences = newest!!.occurrences + 1)
            ) + existing.drop(1)
        } else {
            listOf(incoming) + existing
        }
        // Trimmed per budget rather than by taking the newest few hundred of everything — see
        // [MAX_ACTION_ENTRIES]. Re-merged newest-first, which is the order it was already in and
        // the order it is read in.
        val (actions, faults) = merged.partition { isCaregiverAction(it.kind) }
        return (faults.take(MAX_ENTRIES) + actions.take(MAX_ACTION_ENTRIES))
            .sortedByDescending { it.at }
    }

    /**
     * Keeps a name and refuses an identity.
     *
     * The caregiver needs to read "a message to Karolina was not sent" — without the name the log
     * says nothing anybody can act on. But a message from a Threema identity nobody has a contact
     * for is displayed under that identity, so the name handed in here is sometimes the identity
     * itself, and writing those down would slowly build a list of everyone she is in touch with on
     * a screen that exists to report faults.
     *
     * The same rule her own screen uses — see [PersonName.forHer]. One rule, because the caregiver
     * reads this log in order to talk to her about what happened, and a name that appears one way
     * on her phone and another way in the log is a conversation at cross purposes.
     */
    fun anonymise(rawName: String?): String? = PersonName.forHer(rawName)
}
