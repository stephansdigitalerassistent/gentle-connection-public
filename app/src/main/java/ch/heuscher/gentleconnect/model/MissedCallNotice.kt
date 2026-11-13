package ch.heuscher.gentleconnect.model

/**
 * Whether a missed call is still worth the card at the top of her home screen.
 *
 * A message can be read, and reading it takes the card away. A missed call has nothing
 * equivalent: the call log has no "she has dealt with this" — so the card used to sit there for
 * the rest of the day whatever she did about it. She would press "Call back", speak to her
 * daughter for ten minutes, hang up, and find the phone still saying that her daughter had rung
 * and she had missed it. On a screen whose whole job is to say what needs her attention, the
 * loudest thing on it was a job she had just finished.
 *
 * So the phone remembers. One number, the moment up to which missed calls have been dealt with,
 * and anything older than it is over.
 *
 * Pure and tiny on purpose: this is the kind of rule that stops holding silently, and the phone
 * cannot be asked to demonstrate it.
 */
object MissedCallNotice {

    /** [forHours] for a card that stays until she does something about it. */
    const val FOREVER = -1

    /** [forHours] for a phone that never shows the card at all. */
    const val NEVER = 0

    /** A day: long enough to catch a call missed overnight, short enough to clear. */
    const val DEFAULT_HOURS = 24

    /**
     * The lengths of time a caregiver can choose between, shortest first.
     *
     * A short list of round numbers rather than a free number, because it is set by someone
     * typing into a website on her behalf, and the difference between seven hours and eight is
     * not a difference anybody can actually judge from a distance. [NEVER] and [FOREVER] are the
     * two ends of it and are the reason the list is not just hours.
     */
    val CHOICES = listOf(NEVER, 1, 2, 4, 8, 24, FOREVER)

    /**
     * Whether a call missed at [callAt] should still be shown.
     *
     * Two separate ways for a card to be over, and they answer different questions. She has dealt
     * with it — everything up to [dealtWithUpTo] is handled — or it has simply been too long,
     * which is [forHours].
     *
     * Strictly newer, so that acting on a call clears exactly that call: recording the moment of
     * the call itself, rather than the moment she pressed the button, means a second call that
     * came in while she was pressing is not swept away with it.
     *
     * @param forHours how long a missed call stays on her screen, from [CHOICES]. [NEVER] means
     *   the card never appears; [FOREVER] means only she can take it away. Anything else is read
     *   as [DEFAULT_HOURS] — see [sane].
     */
    fun worthShowing(
        callAt: Long,
        dealtWithUpTo: Long,
        now: Long,
        forHours: Int = DEFAULT_HOURS
    ): Boolean {
        val hours = sane(forHours)
        if (hours == NEVER) return false
        if (callAt <= dealtWithUpTo) return false
        if (hours == FOREVER) return true
        return now - callAt < hours * AN_HOUR
    }

    /**
     * A length of time this phone is willing to act on.
     *
     * The value arrives from a website, through a document nobody on this phone controls, and
     * the failure it guards against is not somebody choosing badly — it is a `3` from a version
     * of the site this phone has not been updated for, or a `-2` from a typo, becoming a card
     * that is either permanent or never shown. Both of those are silent. Falling back to a day
     * is the same answer the phone gave before any of this was configurable.
     */
    fun sane(forHours: Int): Int = if (forHours in CHOICES) forHours else DEFAULT_HOURS

    private const val AN_HOUR = 60L * 60 * 1000

    /**
     * The moment to remember when she has dealt with a call missed at [callAt].
     *
     * Never moves backwards. Two missed calls can be dealt with out of order — she calls back the
     * newer one first — and the older card must not come back afterwards.
     */
    fun dealtWith(callAt: Long, dealtWithUpTo: Long): Long = maxOf(callAt, dealtWithUpTo)

    /**
     * The calls in [calls] that are still news — the same rule as [worthShowing], applied to
     * every missed call rather than only the newest.
     *
     * The card shows one call; the calls tab marks every person who rang, so that a morning with
     * three missed calls is three faces lit up rather than the latest one and two she never hears
     * about.
     */
    fun stillOpen(
        calls: List<Nudge.MissedCallNudge>,
        dealtWithUpTo: Long,
        now: Long,
        forHours: Int = DEFAULT_HOURS
    ): List<Nudge.MissedCallNudge> =
        calls.filter { worthShowing(it.timestamp, dealtWithUpTo, now, forHours) }

    /**
     * The moment to remember when she leaves the calls tab, having been shown every one of [open].
     *
     * The newest call she was shown, not the moment she left: a call that rings in while she is
     * walking away from the list has not been seen, and must still be there when she comes back.
     */
    fun allSeen(open: List<Nudge.MissedCallNudge>, dealtWithUpTo: Long): Long =
        open.maxOfOrNull { it.timestamp }?.let { dealtWith(it, dealtWithUpTo) } ?: dealtWithUpTo

    /**
     * Whether [contact] is somebody who rang and has not been rung back — the mark on their row.
     *
     * By [CallHistory.sameNumber], because the log and the address book never write one telephone
     * the same way. A contact without a number is never marked, and neither is a withheld call.
     */
    fun rang(contact: Contact, open: List<Nudge.MissedCallNudge>): Boolean {
        if (contact.phoneNumber.isBlank()) return false
        return open.any { it.number.isNotBlank() && CallHistory.sameNumber(contact.phoneNumber, it.number) }
    }

    /**
     * Whether one of [open] came from a number nobody in [contacts] has — a call that can only be
     * found in her list of calls, which is then the button that is marked.
     */
    fun anyFromStrangers(open: List<Nudge.MissedCallNudge>, contacts: List<Contact>): Boolean =
        open.any { call ->
            call.number.isBlank() || contacts.none {
                it.phoneNumber.isNotBlank() && CallHistory.sameNumber(it.phoneNumber, call.number)
            }
        }
}
