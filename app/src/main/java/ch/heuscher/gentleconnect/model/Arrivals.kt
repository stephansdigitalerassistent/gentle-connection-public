package ch.heuscher.gentleconnect.model

/**
 * Whether something that has just arrived is worth taking her phone off the table for.
 *
 * The home screen has always been able to say that her daughter wrote. What it could not do is
 * be seen: the phone lies face down on the table with the screen off, and nothing in this app
 * ever made a sound or lit anything up. A message arrived, the card appeared, and it waited until
 * she next happened to pick the phone up — which for somebody who is not expecting anything can
 * be the following day.
 *
 * So the phone now wakes for it. That is a loud thing to give an app, and the whole of this file
 * is about not abusing it: the rules below are what stands between one message from her son and a
 * telephone that lights up in the night, wakes her for a four-year-old photograph being imported,
 * or announces the same message twice.
 *
 * Pure, and therefore the part of this feature that CI can actually check.
 */
object Arrivals {

    /**
     * How recently something must have arrived to be announced at all.
     *
     * The messengers deliver in bursts, not in real time. A phone that has been off, a session
     * that reconnects, a history import: all three hand the app a pile of messages at once, every
     * one of them "new" to the database and some of them years old. Waking her for those would
     * make the sound mean "the app reconnected", which is not a thing she can act on and not a
     * thing she should be woken for.
     */
    const val STILL_FRESH_MS = 5 * 60 * 1000L

    /** How long one person's run of messages counts as the one arrival it is. */
    const val SAME_SENDER_QUIET_MS = 2 * 60 * 1000L

    /**
     * From this hour in the evening, the phone stays dark and quiet, unless a caregiver has said
     * otherwise on the website.
     *
     * A default rather than a rule: bedtimes differ, and the person who knows hers is the one
     * looking after her. See [quietAt] and `RemoteSettings.quietFromHour`.
     */
    const val QUIET_FROM_HOUR = 21

    /** Until this hour in the morning. Same story — a default the website can move. */
    const val QUIET_UNTIL_HOUR = 7

    /**
     * Whether to make a noise about something that arrived at [arrivedAt].
     *
     * Callers must pass the receipt clock (when this phone actually got the message, falling back
     * to when it was written) rather than the sender's timestamp. A queued message delivered on
     * reconnect is fresh to her the moment it lands, however old the sender's clock says it is.
     *
     * @param arrivedAt when this phone received the message, or send time if receipt is unknown.
     * @param announcedUpTo the newest thing already announced. Nothing is ever announced twice —
     *   the flow this is driven from re-emits on every change to the list, and without this a
     *   photograph downloading against an existing message would announce it a second time.
     *
     * There used to be a third guard here, `listeningSince`: nothing from before the app started
     * was announced, because a launcher restarts constantly and a restart must not sound like her
     * family. It has been removed, and deliberately. The collectors now run under
     * [ch.heuscher.gentleconnect.service.MessengerService], so a message can arrive before any
     * screen exists — and that guard would have suppressed exactly the chime that matters. What
     * replaced it is stronger and does not depend on when a process happened to start:
     * [announcedUpTo] is kept on disk across restarts, and freshness is judged on the receipt
     * clock, which is zero — and therefore never fresh — for every row that predates it.
     */
    /**
     * Whether to make a sound for this one, given who the last notice was about and when.
     *
     * A camera roll emptied into a conversation arrives as a dozen separate messages, and the
     * phone announced every one of them. The first notice already says what the others would —
     * somebody has written — and eleven more say only that the telephone should be put away.
     *
     * Two minutes, and per person, exactly as [ch.heuscher.gentleconnect.data.ArrivalPipeline]'s
     * reaction notices are collapsed: a run is over in seconds, while her son writing again after
     * a quarter of an hour is news and sounds like it.
     */
    fun worthAnnouncingAgain(sameSenderAsLast: Boolean, sinceLastAnnouncement: Long): Boolean =
        !sameSenderAsLast || sinceLastAnnouncement > SAME_SENDER_QUIET_MS

    fun worthAnnouncing(
        arrivedAt: Long,
        announcedUpTo: Long,
        now: Long
    ): Boolean =
        arrivedAt > announcedUpTo &&
            now - arrivedAt <= STILL_FRESH_MS

    /**
     * Whether the phone should stay dark and silent at this hour.
     *
     * Nothing that arrives at night is worth waking an 84-year-old for, and a phone that does it
     * once is a phone that gets switched off at bedtime — which costs her every message of the
     * following morning. The card is still there when she gets up; this is only about the noise.
     *
     * The hours come from the caregiver's website, because 21:00 is a guess and the person who
     * knows when she actually goes to bed is the one looking after her. Somebody who is up until
     * midnight loses three hours of her family every evening to a default; somebody who sleeps
     * from eight is woken by one.
     *
     * Three things this deliberately does, all of them about the same fear — a telephone that has
     * silently stopped making any sound at all, which is discovered weeks later and only because
     * nobody has heard from her:
     *
     *  - **Hours outside a clock fall back to the defaults.** A stray value on the website, or a
     *    field some future version writes differently, must not be read as a window.
     *  - **[fromHour] equal to [untilHour] means never quiet, not always quiet.** The two readings
     *    are equally arguable from the numbers alone and only one of them is survivable, so the
     *    ambiguous case resolves towards her hearing her family.
     *  - **A window that does not wrap midnight is honoured as written.** An afternoon rest from
     *    13:00 to 15:00 is a real thing to want, and reading it as "quiet from one o'clock until
     *    three the next afternoon" would be a phone that is silent for twenty-six hours.
     */
    fun quietAt(
        hourOfDay: Int,
        fromHour: Int = QUIET_FROM_HOUR,
        untilHour: Int = QUIET_UNTIL_HOUR
    ): Boolean {
        val from = if (fromHour in 0..23) fromHour else QUIET_FROM_HOUR
        val until = if (untilHour in 0..23) untilHour else QUIET_UNTIL_HOUR

        if (from == until) return false

        return if (from > until) {
            // Wraps midnight, which is the ordinary case: quiet from the evening until the morning.
            hourOfDay >= from || hourOfDay < until
        } else {
            hourOfDay >= from && hourOfDay < until
        }
    }
}
