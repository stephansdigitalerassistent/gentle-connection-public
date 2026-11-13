package ch.heuscher.gentleconnect.model

/** Which way a call went, as far as she needs to know about it. */
enum class CallDirection {
    /** Somebody rang her and she answered. */
    INCOMING,

    /** She rang somebody. */
    OUTGOING,

    /** Somebody rang and nobody picked up. The only one that is ever news. */
    MISSED,

    /**
     * A stranger's call that the caregiver's switch ended before it rang — see
     * `telecom.UnknownCallers`. Only listed when a caregiver asked for these, and never as missed:
     * nobody is waiting for her to ring back.
     */
    BLOCKED
}

/**
 * One call, out of the telephone's own record of them.
 *
 * [name] is null when the number is in nobody's address book. Null rather than "Unknown caller":
 * the row composes its own sentence, and a screen that says "Unknown caller" four times over is
 * a screen that has made four strangers out of four gaps.
 *
 * [contactId] is Android's id for the person, when there is one — the handle needed to open them
 * in the telephone's own contact editor.
 */
data class CallRecord(
    val id: String,
    val name: String?,
    val number: String,
    val direction: CallDirection,
    val timestamp: Long,
    val contactId: String? = null,
    /**
     * How long the call lasted, in seconds. Zero for a missed one, and zero whenever the
     * telephone's log did not say.
     *
     * Read but never drawn on her screen: "4 Min. 12 Sek." beside a name is a fact about a call
     * she remembers having, and her list of calls is there to answer "did I speak to him", which
     * the row already answers by existing. It is here for the caregiver sheet, where the question
     * being asked is a different one — whether the call anybody is worried about actually
     * connected, or rang out.
     */
    val durationSeconds: Int = 0
) {
    /** Whether there is a person behind this number, or only the number. */
    val isKnown: Boolean get() = !name.isNullOrBlank()
}

/**
 * What the list of calls looks like once it is for her rather than for a record-keeper.
 *
 * Pure, so it is testable and so the rule does not live inside a screen — the same reason
 * [PhoneBook] and `CallRouter` are separate from the things that draw and place calls.
 */
object CallHistory {

    /**
     * Runs of calls to or from the same number, collapsed into the newest of them.
     *
     * The telephone records every attempt. Ringing her son and getting no answer three times in
     * a row is three entries, and her call list then reads as three separate events involving
     * her son this afternoon — she cannot tell from it whether she spoke to him. One row per run
     * says the true thing: she tried him, at that time.
     *
     * Only *consecutive* calls collapse. Her daughter this morning and her daughter last Tuesday
     * are two different things that happened and must stay two rows, however few numbers are in
     * between.
     *
     * @param records newest first, as the telephone's own log hands them over.
     */
    fun forHer(records: List<CallRecord>, limit: Int = SHOWN): List<CallRecord> {
        val collapsed = mutableListOf<CallRecord>()
        for (record in records) {
            val previous = collapsed.lastOrNull()
            // A missed call never disappears into an answered one. "She rang me and I called her
            // back" is the whole story of those two rows, and collapsing them tells her only the
            // half she already knows.
            if (previous != null &&
                previous.direction == record.direction &&
                sameNumber(previous.number, record.number)
            ) {
                continue
            }
            collapsed += record
            if (collapsed.size >= limit) break
        }
        return collapsed
    }

    /**
     * Whether two written numbers are the same telephone.
     *
     * Compared on the last [SIGNIFICANT_DIGITS] digits, because the log holds the number in
     * whatever shape it arrived in: `+41 79 123 45 67` when her daughter rang from abroad and
     * `079 123 45 67` when she rang back from the address book. Ends rather than beginnings —
     * the beginning is exactly the part that differs.
     *
     * Anything too short to supply those digits has to match exactly instead. Comparing on
     * "as many digits as the shorter one has" would make the short code `34567` the same
     * telephone as `+41 79 123 45 67`, and her call list would then offer to ring her daughter
     * on a row that was the bank's automated line.
     */
    fun sameNumber(a: String, b: String): Boolean {
        val left = a.filter { it.isDigit() }
        val right = b.filter { it.isDigit() }
        if (left.length < SIGNIFICANT_DIGITS || right.length < SIGNIFICANT_DIGITS) {
            return left == right
        }
        return left.takeLast(SIGNIFICANT_DIGITS) == right.takeLast(SIGNIFICANT_DIGITS)
    }

    /**
     * How many calls the screen holds.
     *
     * Not all of them. The telephone keeps five hundred, and a list she can scroll for ten
     * minutes is not a record of who she has spoken to — it is a place to get lost. Everything
     * beyond about a fortnight is answered better by the address book.
     */
    const val SHOWN = 40

    /**
     * Enough of the end of a number to identify a telephone, and not so much that two numbers
     * sharing a national prefix are mistaken for one.
     */
    private const val SIGNIFICANT_DIGITS = 7
}
