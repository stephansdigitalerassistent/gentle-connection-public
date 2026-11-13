package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Agenda
import ch.heuscher.gentleconnect.model.AgendaDay
import ch.heuscher.gentleconnect.model.Appointment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * What her home screen says is coming.
 *
 * Worth testing rather than looking at, because every answer here depends on the time it is
 * asked, and "wait until Tuesday and check" is not a way to find out whether a phone is right.
 */
class AgendaTest {

    private val zone = TimeZone.getTimeZone("Europe/Zurich")

    /** Midnight before an instant, the way the screens compute it — see `ui.startOfDay`. */
    private val startOfDay: (Long) -> Long = { moment ->
        Calendar.getInstance(zone).apply {
            timeInMillis = moment
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            set(2026, Calendar.AUGUST, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun appointment(
        id: Long = 1L,
        title: String = "Dr. Müller",
        begin: Long,
        end: Long = begin + 60 * 60 * 1000L,
        allDay: Boolean = false,
        location: String? = null
    ) = Appointment(id = id, title = title, begin = begin, end = end, isAllDay = allDay, location = location)

    private val wednesdayMorning = at(26, 9)

    @Test
    fun `this afternoon is what she is told about`() {
        val doctor = appointment(begin = at(26, 14))

        assertEquals(listOf(doctor), Agenda.upcoming(listOf(doctor), wednesdayMorning, startOfDay))
    }

    @Test
    fun `an appointment she is sitting in is still on the screen`() {
        // Ten past two, the appointment began at two. Taking it away the moment it started would
        // empty the block exactly when she looked down at the phone in the waiting room.
        val doctor = appointment(begin = at(26, 14), end = at(26, 15))

        assertEquals(listOf(doctor), Agenda.upcoming(listOf(doctor), at(26, 14, 10), startOfDay))
    }

    @Test
    fun `an appointment that is over is not news`() {
        val doctor = appointment(begin = at(26, 9), end = at(26, 10))

        assertTrue(Agenda.upcoming(listOf(doctor), at(26, 11), startOfDay).isEmpty())
    }

    @Test
    fun `an appointment with no length at all goes when it starts`() {
        // Calendars contain these. There is nothing else its end could mean.
        val moment = appointment(begin = at(26, 14), end = at(26, 14))

        assertEquals(listOf(moment), Agenda.upcoming(listOf(moment), at(26, 13), startOfDay))
        assertTrue(Agenda.upcoming(listOf(moment), at(26, 14, 1), startOfDay).isEmpty())
    }

    @Test
    fun `next week does not take the place of this afternoon`() {
        // The whole reason there is a horizon. A holiday in three weeks is a true thing about her
        // calendar and a useless thing to put at the top of her screen every morning until then.
        val holiday = appointment(id = 2L, title = "Ferien", begin = at(26 + 14, 9))
        val doctor = appointment(begin = at(26, 14))

        assertEquals(
            listOf(doctor),
            Agenda.upcoming(listOf(holiday, doctor), wednesdayMorning, startOfDay)
        )
    }

    @Test
    fun `three days is today and the two after it`() {
        val friday = appointment(id = 3L, title = "Coiffeur", begin = at(28, 10))
        val saturday = appointment(id = 4L, title = "Besuch", begin = at(29, 10))

        val shown = Agenda.upcoming(listOf(friday, saturday), wednesdayMorning, startOfDay)

        assertEquals(listOf(friday), shown)
    }

    @Test
    fun `the soonest come first, and only Agenda MOST of them`() {
        val third = appointment(id = 3L, begin = at(27, 9))
        val first = appointment(id = 1L, begin = at(26, 14))
        val fourth = appointment(id = 4L, begin = at(27, 15))
        val second = appointment(id = 2L, begin = at(26, 18))

        val shown = Agenda.upcoming(
            listOf(third, first, fourth, second),
            wednesdayMorning,
            startOfDay
        )

        assertEquals(listOf(first, second), shown)
        assertEquals(Agenda.MOST, shown.size)
    }

    @Test
    fun `an all-day event stands above the appointments of its own day`() {
        // It begins at midnight, so it sorts first without anybody asking it to — which is also
        // the order she would say them in: "Thursday is Peter's birthday, and there's the dentist
        // at ten."
        val birthday = appointment(id = 5L, title = "Peters Geburtstag", begin = at(27, 0), end = at(28, 0), allDay = true)
        val dentist = appointment(id = 6L, title = "Zahnarzt", begin = at(27, 10))

        assertEquals(
            listOf(birthday, dentist),
            Agenda.upcoming(listOf(dentist, birthday), wednesdayMorning, startOfDay)
        )
    }

    @Test
    fun `today, tomorrow, and then the day gets its name`() {
        assertEquals(
            AgendaDay.TODAY,
            Agenda.dayOf(appointment(begin = at(26, 14)), wednesdayMorning, startOfDay)
        )
        assertEquals(
            AgendaDay.TOMORROW,
            Agenda.dayOf(appointment(begin = at(27, 9)), wednesdayMorning, startOfDay)
        )
        assertEquals(
            AgendaDay.LATER,
            Agenda.dayOf(appointment(begin = at(28, 9)), wednesdayMorning, startOfDay)
        )
    }

    @Test
    fun `late in the evening, tomorrow is still tomorrow`() {
        // Half past eleven at night. An hour's arithmetic either way lands in the wrong day here,
        // which is why the boundaries are computed rather than subtracted.
        val lateWednesday = at(26, 23, 30)

        assertEquals(
            AgendaDay.TODAY,
            Agenda.dayOf(appointment(begin = at(26, 23, 45)), lateWednesday, startOfDay)
        )
        assertEquals(
            AgendaDay.TOMORROW,
            Agenda.dayOf(appointment(begin = at(27, 0, 15)), lateWednesday, startOfDay)
        )
    }

    // Everything below is about an appointment that crosses a day boundary. The first version of
    // this file had none, every fixture began and ended inside one day, and so all of it passed
    // while her home screen showed three project phases from a work calendar and none of the
    // three appointments she actually had that day.

    @Test
    fun `a span of days is not an appointment and is left out`() {
        // Seven weeks in, still running. Sorted by when it began it beats everything on the
        // calendar, and there are only three lines.
        val phase = appointment(
            id = 9L,
            title = "Epic Upgrade «vMay26» - Overall",
            begin = at(5, 0),
            end = at(56, 0),
            allDay = true
        )
        val doctor = appointment(begin = at(26, 14))

        assertEquals(
            listOf(doctor),
            Agenda.upcoming(listOf(phase, doctor), wednesdayMorning, startOfDay)
        )
    }

    @Test
    fun `a span starting later is left out too`() {
        // Not a question of it having started in the past: a fortnight of school holidays
        // beginning tomorrow is the same kind of thing and just as unhelpful on three lines.
        val holidays = appointment(
            id = 10L,
            title = "Herbstferien",
            begin = at(27, 0),
            end = at(28 + 14, 0),
            allDay = true
        )

        assertTrue(Agenda.upcoming(listOf(holidays), wednesdayMorning, startOfDay).isEmpty())
    }

    @Test
    fun `one day of its own is kept, which is the whole point of the distinction`() {
        // The guard against fixing the above by throwing away birthdays and public holidays.
        val birthday = appointment(
            id = 11L,
            title = "Peters Geburtstag",
            begin = at(27, 0),
            end = at(28, 0),
            allDay = true
        )

        assertEquals(
            listOf(birthday),
            Agenda.upcoming(listOf(birthday), wednesdayMorning, startOfDay)
        )
    }

    @Test
    fun `an all-day event with no length at all is still a day`() {
        // Calendars contain these. Measured naively it covers no days and would be dropped.
        val birthday = appointment(
            id = 12L,
            title = "Geburtstag",
            begin = at(27, 0),
            end = at(27, 0),
            allDay = true
        )

        assertEquals(
            listOf(birthday),
            Agenda.upcoming(listOf(birthday), wednesdayMorning, startOfDay)
        )
    }

    @Test
    fun `something that began yesterday and is still on says today`() {
        // It is running now, so it is hers to think about today. Naming the weekday it started
        // on tells her about a day that is behind her.
        val visit = appointment(id = 13L, title = "Anna ist da", begin = at(25, 9), end = at(27, 17))

        assertEquals(AgendaDay.TODAY, Agenda.dayOf(visit, wednesdayMorning, startOfDay))
    }

    @Test
    fun `what she had that morning, in the order she needed it`() {
        // The screen this was found on, minus the calendars that are not hers.
        val overall = appointment(id = 14L, title = "Overall", begin = at(5, 0), end = at(56, 0), allDay = true)
        val build = appointment(id = 15L, title = "Buildphase", begin = at(25, 0), end = at(32, 0), allDay = true)
        val notes = appointment(id = 16L, title = "Reviewphase", begin = at(25, 0), end = at(32, 0), allDay = true)
        val weekly = appointment(id = 17L, title = "AI Lead weekly", begin = at(26, 10), end = at(26, 11))
        val lunch = appointment(id = 18L, title = "Mittag m. Simon", begin = at(26, 13), end = at(26, 16))

        val shown = Agenda.upcoming(
            listOf(overall, build, notes, weekly, lunch),
            wednesdayMorning,
            startOfDay
        )

        assertEquals(listOf(weekly, lunch), shown)
    }

    @Test
    fun `nothing in the calendar is an empty list, not a missing one`() {
        assertTrue(Agenda.upcoming(emptyList(), wednesdayMorning, startOfDay).isEmpty())
    }

    @Test
    fun `nothing in the window but something in three weeks is returned as next`() {
        val doctor = appointment(id = 20L, title = "Dr. Müller", begin = at(26 + 21, 14))

        assertEquals(doctor, Agenda.next(listOf(doctor), wednesdayMorning, startOfDay))
    }

    @Test
    fun `nothing at all returns null`() {
        assertEquals(null, Agenda.next(emptyList(), wednesdayMorning, startOfDay))
    }

    @Test
    fun `a multi-day span beyond the window is not next`() {
        val holidays = appointment(
            id = 21L,
            title = "Herbstferien",
            begin = at(26 + 14, 0),
            end = at(26 + 28, 0),
            allDay = true
        )

        assertEquals(null, Agenda.next(listOf(holidays), wednesdayMorning, startOfDay))
    }

    @Test
    fun `something inside the three days is not returned by next`() {
        // Belongs to upcoming, not next.
        val tomorrow = appointment(id = 22L, title = "Coiffeur", begin = at(27, 10))

        assertEquals(null, Agenda.next(listOf(tomorrow), wednesdayMorning, startOfDay))
    }

    @Test
    fun `next picks the earliest appointment after the horizon`() {
        val doctorInThreeWeeks = appointment(id = 23L, title = "Dr. Müller", begin = at(26 + 21, 14))
        val dentistInTwoWeeks = appointment(id = 24L, title = "Zahnarzt", begin = at(26 + 14, 10))

        assertEquals(
            dentistInTwoWeeks,
            Agenda.next(listOf(doctorInThreeWeeks, dentistInTwoWeeks), wednesdayMorning, startOfDay)
        )
    }

    @Test
    fun `planned keeps a span that upcoming drops`() {
        val holidays = appointment(
            id = 30L,
            title = "Herbstferien",
            begin = at(27, 0),
            end = at(28 + 14, 0),
            allDay = true
        )
        val doctor = appointment(id = 31L, begin = at(26, 14))

        assertTrue(Agenda.upcoming(listOf(holidays), wednesdayMorning, startOfDay).isEmpty())
        assertEquals(
            listOf(doctor, holidays),
            Agenda.planned(listOf(holidays, doctor), wednesdayMorning, startOfDay)
        )
    }

    @Test
    fun `planned leaves out what is over`() {
        val over = appointment(id = 32L, begin = at(26, 7), end = at(26, 8))
        val later = appointment(id = 33L, begin = at(26, 14), end = at(26, 15))

        assertEquals(
            listOf(later),
            Agenda.planned(listOf(over, later), wednesdayMorning, startOfDay)
        )
    }

    @Test
    fun `planned is in order`() {
        val inTwoWeeks = appointment(id = 34L, begin = at(26 + 14, 10))
        val tomorrow = appointment(id = 35L, begin = at(27, 9))
        val today = appointment(id = 36L, begin = at(26, 14))

        val result = Agenda.planned(listOf(inTwoWeeks, today, tomorrow), wednesdayMorning, startOfDay)

        assertEquals(listOf(today, tomorrow, inTwoWeeks), result)
    }

    @Test
    fun `indexIn finds the row the home screen was showing`() {
        val today = appointment(id = 90L, begin = at(26, 14))
        val tomorrow = appointment(id = 91L, begin = at(27, 9))
        val later = appointment(id = 92L, begin = at(30, 9))

        val plan = Agenda.planned(listOf(later, today, tomorrow), wednesdayMorning, startOfDay)

        assertEquals(0, Agenda.indexIn(plan, 90L, at(26, 14)))
        assertEquals(1, Agenda.indexIn(plan, 91L, at(27, 9)))
        assertEquals(2, Agenda.indexIn(plan, 92L, at(30, 9)))
    }

    @Test
    fun `indexIn tells one morning of a repeating appointment from another`() {
        // Same id, three Tuesdays: the id alone would send her to the first of them.
        val first = appointment(id = 93L, title = "Turnen", begin = at(27, 10))
        val second = appointment(id = 93L, title = "Turnen", begin = at(27 + 7, 10))
        val third = appointment(id = 93L, title = "Turnen", begin = at(27 + 14, 10))

        val plan = Agenda.planned(listOf(first, second, third), wednesdayMorning, startOfDay)

        assertEquals(1, Agenda.indexIn(plan, 93L, at(27 + 7, 10)))
    }

    @Test
    fun `indexIn is null for something no longer in the list`() {
        val today = appointment(id = 94L, begin = at(26, 14))

        val plan = Agenda.planned(listOf(today), wednesdayMorning, startOfDay)

        assertEquals(null, Agenda.indexIn(plan, 95L, at(26, 14)))
        assertEquals(null, Agenda.indexIn(plan, 94L, at(25, 14)))
    }

    @Test
    fun `hasMore is false when the three days already show everything`() {
        val today = appointment(id = 37L, begin = at(26, 14))
        val tomorrow = appointment(id = 38L, begin = at(27, 10))

        assertEquals(false, Agenda.hasMore(listOf(today, tomorrow), wednesdayMorning, startOfDay))
    }

    @Test
    fun `hasMore is true when there is a fourth thing further out`() {
        val first = appointment(id = 39L, begin = at(26, 10))
        val second = appointment(id = 40L, begin = at(26, 14))
        val third = appointment(id = 41L, begin = at(27, 10))
        val fourth = appointment(id = 42L, begin = at(28, 15))

        assertEquals(true, Agenda.hasMore(listOf(first, second, third, fourth), wednesdayMorning, startOfDay))
    }

    @Test
    fun `hasMore is false when the window is empty and the only thing ahead is the one next already names`() {
        val nextDoctor = appointment(id = 43L, begin = at(26 + 21, 14))

        assertEquals(false, Agenda.hasMore(listOf(nextDoctor), wednesdayMorning, startOfDay))
    }

    @Test
    fun `daysUntil gives 0 for today, 1 for tomorrow, and 9 for nine days out`() {
        val today = appointment(id = 44L, begin = at(26, 14))
        val tomorrow = appointment(id = 45L, begin = at(27, 9))
        val inNineDays = appointment(id = 46L, begin = at(26 + 9, 11))

        assertEquals(0, Agenda.daysUntil(today, wednesdayMorning, startOfDay))
        assertEquals(1, Agenda.daysUntil(tomorrow, wednesdayMorning, startOfDay))
        assertEquals(9, Agenda.daysUntil(inNineDays, wednesdayMorning, startOfDay))
    }

    @Test
    fun `daysUntil counts correctly across the March Sunday the clocks change`() {
        val marchAt: (Int, Int) -> Long = { day, hour ->
            Calendar.getInstance(zone).apply {
                set(2027, Calendar.MARCH, day, hour, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        // In 2027, the last Sunday in March is March 28 (daylight saving time begins, 23-hour day).
        val fridayBefore = marchAt(26, 9)
        val tuesdayAfter = appointment(id = 47L, begin = marchAt(30, 10))

        assertEquals(4, Agenda.daysUntil(tuesdayAfter, fridayBefore, startOfDay))

        val saturdayBefore = marchAt(27, 14)
        val mondayAfter = appointment(id = 48L, begin = marchAt(29, 10))

        assertEquals(2, Agenda.daysUntil(mondayAfter, saturdayBefore, startOfDay))
    }

    @Test
    fun `a stretch of days that began before today is already running`() {
        val phase = appointment(
            id = 49L,
            title = "Epic Upgrade",
            begin = at(20, 0),
            end = at(28 + 30, 0),
            allDay = true
        )
        val holidaysNextWeek = appointment(
            id = 50L,
            title = "Herbstferien",
            begin = at(26 + 7, 0),
            end = at(26 + 14, 0),
            allDay = true
        )

        assertTrue(Agenda.alreadyRunning(phase, wednesdayMorning, startOfDay))
        assertEquals(false, Agenda.alreadyRunning(holidaysNextWeek, wednesdayMorning, startOfDay))
    }

    @Test
    fun `something starting later today has not started yet`() {
        val doctor = appointment(id = 51L, begin = at(26, 14))

        assertEquals(false, Agenda.alreadyRunning(doctor, wednesdayMorning, startOfDay))
    }

    @Test
    fun `a birthday is told by its title`() {
        val begin = at(20, 0)
        assertTrue(Agenda.isBirthday(appointment(title = "Anna Muster hat Geburtstag", begin = begin, allDay = true)))
        assertTrue(Agenda.isBirthday(appointment(title = "Geburtstag Lala 🎂🍰🎂", begin = begin)))
        assertTrue(Agenda.isBirthday(appointment(title = "Geburtstag🎂🎁🍸", begin = begin)))
        assertTrue(Agenda.isBirthday(appointment(title = "Peter 🎂", begin = begin)))
        assertTrue(Agenda.isBirthday(appointment(title = "BIRTHDAY Tom", begin = begin)))
        assertTrue(Agenda.isBirthday(appointment(title = "Geburi Lisa", begin = begin)))
        // A party is somewhere she may be going, and a doctor is not a birthday.
        assertFalse(Agenda.isBirthday(appointment(title = "Geburtstagsfest bei Anna", begin = begin)))
        assertFalse(Agenda.isBirthday(appointment(title = "Dr. Müller", begin = begin)))
    }

    @Test
    fun `birthdays are left out only when a caregiver has switched them off`() {
        val birthday = appointment(id = 1L, title = "Anna hat Geburtstag", begin = at(20, 0), allDay = true)
        val doctor = appointment(id = 2L, title = "Dr. Müller", begin = at(20, 10))
        assertEquals(listOf(birthday, doctor), Agenda.shown(listOf(birthday, doctor), showBirthdays = true))
        assertEquals(listOf(doctor), Agenda.shown(listOf(birthday, doctor), showBirthdays = false))
    }
}
