package ch.heuscher.gentleconnect.model

/**
 * One appointment, as her screen needs it.
 *
 * Deliberately holds almost nothing of Android's calendar: no event id to open, no organiser,
 * no attendees, and no description. Everything on this phone that shows an appointment shows
 * these fields, and the one place that talks to the calendar provider keeps the rest to itself.
 * The one exception is [location], which is tidied down to a recognizable place name or address
 * so she knows where to go.
 *
 * [begin] and [end] are real instants in her own time zone. An all-day event is not stored that
 * way — the provider keeps it at midnight UTC, which on a Swiss phone is two o'clock in the
 * morning on the right day and, on a phone west of Greenwich, the evening of the day before. It
 * is turned into local midnight where it is read, so that nothing downstream of here has to know
 * that, and so the rules below can be tested without a time zone.
 */
data class Appointment(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val isAllDay: Boolean,
    val location: String? = null
)

/** Which of the three ways a day ahead of her gets named. */
enum class AgendaDay { TODAY, TOMORROW, LATER }

/**
 * What her calendar says, cut down to what a home screen can hold.
 *
 * The question this answers is "what is coming", not "what is in my calendar". So it looks
 * forward only: an appointment that is over is not news, and one three weeks out is not news
 * either — it would take the place of the one this afternoon and be there every day until it
 * arrived. Three days and two lines, which is about one glance.
 *
 * There is no month grid anywhere in this app and there should not be one. A grid is a
 * navigation problem before it is an answer: thirty cells to look through for the one fact she
 * wanted, and a way to get lost in a month that is not this one.
 *
 * Pure on purpose, like [Home] and [Arrivals]: CI here runs JVM unit tests only, and a rule
 * about time that is not in an object like this one is a rule nobody can check.
 */
object Agenda {

    /** How far ahead she is shown, counting today. Today, tomorrow, and the day after. */
    const val DAYS = 3

    /**
     * How many lines the home screen gives it — the same two every other section there gets, see
     * [Home.LIST_LENGTH].
     *
     * Three days ahead is still three days ahead: [DAYS] is how far it looks, this is only how
     * much of what it finds fits under the clock. A day with more on it than this says so, and
     * says where the rest is.
     */
    const val MOST = 2

    /**
     * How far ahead the phone asks the calendar, in days — which is not how far she is shown.
     * [DAYS] is that.
     *
     * It only has to be far enough that [next] finds something on a calendar as sparse as hers.
     * Three months rather than a year because a daily recurring event is one row per day, and a
     * year of them is a lot to carry around on a home screen for a question nobody asked. A
     * calendar with nothing at all in the next three months says "Nothing on today" and stops,
     * which at that distance is the honest answer.
     */
    const val LOOK_AHEAD = 90

    /**
     * How far ahead the list screen asks the calendar, in days. A year, because "is anything
     * planned this year" is a question she can ask, and because the query is cheap on a
     * calendar as empty as hers.
     */
    const val LIST_AHEAD = 366

    /**
     * Everything still ahead of her — not yet over — sorted by [concerns], with no cap and no
     * horizon.
     *
     * Unlike [upcoming] this keeps multi-day all-day spans (a fortnight of holidays, a week somebody
     * is away). [spansDays] exists because three lines on a home screen cannot afford a span, and a
     * screen that scrolls can — the exclusion is a rule about that block, not a claim that a span
     * is not an appointment.
     */
    fun planned(
        appointments: List<Appointment>,
        now: Long,
        startOfDay: (Long) -> Long
    ): List<Appointment> =
        appointments
            .filter { maxOf(it.end, it.begin) > now }
            .sortedBy { concerns(it, now, startOfDay) }

    /**
     * Where in [planned] the appointment she pressed on the home screen sits, so the list can
     * open at it rather than at the top. Null when it is not in the list at all, which is an
     * ordinary answer: the minute she presses it, an appointment that has just ended is gone from
     * the list she is being taken to.
     *
     * **The instance, not the entry.** A standing Tuesday is one id and many mornings, so the id
     * alone would land her on the first Tuesday there ever was, months behind the one she
     * pressed. Id and start together is what the list itself already keys its rows by — see
     * `AppointmentsScreen` — and two rows cannot share both.
     */
    fun indexIn(planned: List<Appointment>, id: Long, begin: Long): Int? =
        planned.indexOfFirst { it.id == id && it.begin == begin }.takeIf { it >= 0 }

    /**
     * Whether [planned] holds anything the home block does not itself already name — that is, more
     * than `upcoming(...)` when that is non-empty, and more than the single appointment `next(...)`
     * names when it is not.
     *
     * Follows the rule written in [ch.heuscher.gentleconnect.ui.components.MoreRow]'s KDoc:
     * a door onto the room she is standing in is not a door.
     */
    fun hasMore(
        appointments: List<Appointment>,
        now: Long,
        startOfDay: (Long) -> Long
    ): Boolean {
        val plan = planned(appointments, now, startOfDay)
        val up = upcoming(appointments, now, startOfDay)
        return if (up.isNotEmpty()) {
            plan.size > up.size
        } else {
            val nxt = next(appointments, now, startOfDay)
            plan.size > (if (nxt != null) 1 else 0)
        }
    }

    /**
     * Whole days from today to the day the appointment [concerns] her: 0 today, 1 tomorrow.
     *
     * Counted by walking one day at a time — `day = startOfDay(day + 36 * HOUR)` from
     * `startOfDay(now)` — and NOT by dividing a difference by 24 hours. A day is 23 or 25 hours long
     * twice a year, and 36 hours from a midnight lands in the middle of the next day whichever way
     * the clocks went. Stops at [cap].
     */
    fun daysUntil(
        appointment: Appointment,
        now: Long,
        startOfDay: (Long) -> Long,
        cap: Int = LIST_AHEAD
    ): Int {
        var count = 0
        var current = startOfDay(now)
        val targetDay = startOfDay(concerns(appointment, now, startOfDay))
        while (current < targetDay && count < cap) {
            current = startOfDay(current + 36 * HOUR)
            count++
        }
        return count
    }

    /**
     * The next few appointments, soonest first — or an empty list, which is an answer too.
     *
     * @param startOfDay midnight before an instant. Passed in for the reason
     *   [ch.heuscher.gentleconnect.data.ConversationBuilder] takes its `dayOf`: midnight needs a
     *   time zone, a time zone needs Android, and this has to stay testable.
     *
     * An appointment she is sitting in is still shown. It is over at its end, not at its start:
     * at ten past two, "Today, 2 o'clock, Dr. Müller" is still the useful line, and taking it
     * away the moment it began would make the block go empty exactly when she looked at it. One
     * with no length at all — which is a thing calendars contain — goes at the moment it starts,
     * because there is nothing else its end could mean.
     */
    fun upcoming(
        appointments: List<Appointment>,
        now: Long,
        startOfDay: (Long) -> Long,
        days: Int = DAYS,
        most: Int = MOST
    ): List<Appointment> {
        val horizon = startOfDay(now + days * DAY)
        return appointments
            .filter { maxOf(it.end, it.begin) > now && it.begin < horizon }
            .filterNot { spansDays(it, startOfDay) }
            // All-day first within a day, and it falls out rather than being asked for: an
            // all-day event begins at midnight, and midnight is before anything else that day.
            .sortedBy { concerns(it, now, startOfDay) }
            .take(most)
    }

    /**
     * The earliest appointment coming up after the home screen's three-day horizon, or null
     * when there is nothing ahead.
     *
     * Her calendar is sparse — a handful of entries a month. When the three-day window is empty,
     * the block says "Nothing on today", which is true for today but unhelpful when she has a
     * doctor appointment in nine days. This finds that next appointment so the screen can tell
     * her what is coming without cluttering the daily view.
     *
     * Like [upcoming], multi-day spans (holidays, project phases) are left out: a span is
     * background rather than something she has to do. Anything already over is also skipped.
     */
    fun next(
        appointments: List<Appointment>,
        now: Long,
        startOfDay: (Long) -> Long,
        days: Int = DAYS
    ): Appointment? {
        val horizon = startOfDay(now + days * DAY)
        return appointments
            .filter { maxOf(it.end, it.begin) > now && it.begin >= horizon }
            .filterNot { spansDays(it, startOfDay) }
            .minByOrNull { it.begin }
    }

    /**
     * When an appointment next concerns her, which is not always when it began.
     *
     * Sorting by the raw start looks right until something runs for longer than a day. A phase
     * of work that started seven weeks ago has an earlier start than everything else on the
     * calendar and so takes the top line every morning until it ends, pushing this afternoon's
     * doctor off a screen that only holds three — and gets labelled with the weekday it began
     * on, so a thing happening right now says "Monday". Both of those are the same mistake:
     * asking when it started rather than when it is hers to think about. Anything already
     * running is hers to think about today.
     */
    fun concerns(appointment: Appointment, now: Long, startOfDay: (Long) -> Long): Long =
        maxOf(appointment.begin, startOfDay(now))

    /**
     * Whether this is a stretch of days that began before today.
     *
     * What she needs to know about one of those is when it ends, not when it started: a phase of
     * work that began in July, written out on the list as "Monday, 6 July – Friday, 27 November",
     * reads at a glance as something happening on the sixth of July, which is a date that is gone.
     * "Until Friday, 27 November" is the same fact and the only half of it she can act on.
     */
    fun alreadyRunning(appointment: Appointment, now: Long, startOfDay: (Long) -> Long): Boolean =
        appointment.begin < startOfDay(now)

    /**
     * Whether an all-day event covers more than the one day.
     *
     * The home block leaves those out; the full list keeps them and writes them as a range. Public
     * for that second reader — the question "is this one day or a stretch of them" is the same
     * question either way, and two answers to it would eventually disagree.
     *
     * A calendar uses all-day entries for two unlike things. One is a day: a birthday, a public
     * holiday, the day somebody visits — an appointment in every sense except that it has no
     * clock time, and exactly what this block is for. The other is a span — a project phase, a
     * fortnight of school holidays, a month somebody is away. That second kind is background
     * rather than something to do, it is true for days on end, and on a screen with three lines
     * it crowds out every real appointment for as long as it lasts. So the days stay and the
     * spans go.
     *
     * Asked of `startOfDay` rather than by measuring the length, because a day is not always
     * twenty-four hours long: on the March Sunday it is twenty-three, and an all-day event that
     * happened to be measured then would be mistaken for a span. The end is exclusive — midnight
     * at the far side of the last day — so the last moment it covers is the millisecond before,
     * and `maxOf` keeps a zero-length entry from reading as the day before its own start.
     */
    fun spansDays(appointment: Appointment, startOfDay: (Long) -> Long): Boolean =
        appointment.isAllDay &&
            startOfDay(maxOf(appointment.end - 1, appointment.begin)) !=
            startOfDay(appointment.begin)

    /**
     * Which day an appointment belongs to, as a decision separate from the words for it.
     *
     * Each boundary is computed rather than subtracted, like
     * [ch.heuscher.gentleconnect.ui.dayNameFor]: on the two days a year the clocks change, a day
     * is not twenty-four hours long, and "tomorrow" quietly becoming a weekday name is the kind
     * of small wrongness that costs her a moment of doubt about what she is looking at.
     */
    fun dayOf(appointment: Appointment, now: Long, startOfDay: (Long) -> Long): AgendaDay =
        when (startOfDay(concerns(appointment, now, startOfDay))) {
            startOfDay(now) -> AgendaDay.TODAY
            startOfDay(now + DAY) -> AgendaDay.TOMORROW
            else -> AgendaDay.LATER
        }

    /**
     * Whether an appointment is somebody's birthday rather than something she goes to.
     *
     * Read off the title, because that is all a calendar entry says about itself: the birthdays on
     * her phone are ordinary yearly entries somebody typed into her own calendar — "Anna hat
     * Geburtstag", "Geburtstag Lala 🎂" — and not a calendar that could be unticked instead.
     *
     * The word standing on its own, not inside a longer one: "Geburtstagsfest bei Anna" is a party
     * she may well be going to. A birthday cake counts as the word. What this cannot tell apart is
     * a birthday and a celebration titled exactly like one; a caregiver who switches birthdays off
     * has decided that too.
     */
    fun isBirthday(appointment: Appointment): Boolean = BIRTHDAY.containsMatchIn(appointment.title)

    /**
     * Her calendar as the caregiver wants it shown: all of it, or all of it but the birthdays — see
     * [RemoteSettings.showBirthdays]. Applied before anything is counted, so that "more on this
     * day" and the next appointment named on a free day do not count a birthday either.
     */
    fun shown(appointments: List<Appointment>, showBirthdays: Boolean): List<Appointment> =
        if (showBirthdays) appointments else appointments.filterNot(::isBirthday)

    private val BIRTHDAY = Regex(
        """(?<!\p{L})(geburtstag|geburi|birthday|anniversaire|compleanno)(?!\p{L})|🎂""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Twenty-four hours, only ever used to land somewhere inside a later day before
     * [upcoming]'s `startOfDay` is asked which day that is. Its own rather than the one in the
     * screens, so that nothing in `model` has to reach into `ui` to know what a day is.
     */
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val HOUR = 60 * 60 * 1000L
}
