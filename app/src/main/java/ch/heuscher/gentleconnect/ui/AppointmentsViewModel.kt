package ch.heuscher.gentleconnect.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.heuscher.gentleconnect.data.CalendarRepository
import ch.heuscher.gentleconnect.model.Agenda
import ch.heuscher.gentleconnect.model.Appointment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Her appointments: the few on the home screen, and the year of them on the appointments list.
 *
 * Nothing else reads the calendar, so it was a self-contained block inside the home screen's view
 * model, waiting for a context like everything else there. It now gets its repository when it is
 * built and reads the calendar straight away.
 */
class AppointmentsViewModel(
    /** Null where there is no calendar to read — in tests. Then both lists stay null. */
    private val calendarRepository: CalendarRepository?
) : ViewModel() {

    private var appointmentsRefreshJob: Job? = null
    private var lastAppointmentsRefreshTime: Long = 0L
    private var appointmentsWatch: Job? = null

    /**
     * What her calendar has in it, or null when this phone has no calendar to read.
     *
     * Null and empty are different answers and the screen draws them differently: empty is a free
     * day, which is worth telling her, and null is a phone nobody ever set a calendar up on,
     * which is not. See [CalendarRepository].
     *
     * A window wider than the three days her screen shows, trimmed against the clock by
     * [ch.heuscher.gentleconnect.model.Agenda] as it ticks — so an appointment that ends at four
     * leaves the screen at four without anybody going back to the calendar to ask.
     */
    private val _appointments = MutableStateFlow<List<Appointment>?>(null)
    val appointments: StateFlow<List<Appointment>?> = _appointments.asStateFlow()

    /**
     * Read her calendar again: on the way in, when she comes back to the phone, and whenever the
     * calendar changes underneath the screen — see [keepAppointmentsWatched]. What brings a new
     * appointment onto this device is still Google's own sync; this decides when the launcher
     * looks.
     */
    fun refreshAppointments(force: Boolean = false) {
        val repo = calendarRepository ?: return
        keepAppointmentsWatched(repo)
        val now = System.currentTimeMillis()
        if (force) {
            appointmentsRefreshJob?.cancel()
        } else {
            if (appointmentsRefreshJob?.isActive == true) return
            if (now - lastAppointmentsRefreshTime < APPOINTMENTS_REFRESH_DEBOUNCE_MS) return
        }

        appointmentsRefreshJob = viewModelScope.launch {
            val appts = repo.appointments(
                // A day either side of what is drawn. An all-day event is stored against midnight
                // UTC, which lands in the day before in some of the world, and the query is what
                // has to be generous about that rather than the rule that trims it.
                from = startOfDay(now) - DAY_MS,
                // Wider than the three days the screen draws, so that there is a "next" for it
                // to name on the many days her calendar has nothing in the window at all. How
                // much wider is [Agenda.LOOK_AHEAD]'s decision, not this one.
                until = startOfDay(now + Agenda.LOOK_AHEAD * DAY_MS)
            )
            ensureActive()
            _appointments.value = appts
            lastAppointmentsRefreshTime = System.currentTimeMillis()
        }
    }

    /**
     * Keeps the home screen's appointments in step with the calendar for as long as the app is
     * alive, not only while one screen is up: the home screen is where she spends her day, and an
     * appointment her daughter adds should appear on it without her leaving and coming back.
     *
     * Started from [refreshAppointments] rather than once, because the first try can find no
     * permission — the watch is then an empty flow that ends at once — and the read that follows
     * her granting it is the moment to try again. A watch already running is left alone.
     *
     * [collectLatest] with a short settle: a sync writes many rows over a second or two, and the
     * calendar is read once after it has finished rather than once per row.
     */
    private fun keepAppointmentsWatched(repo: CalendarRepository) {
        if (appointmentsWatch?.isActive == true) return
        appointmentsWatch = viewModelScope.launch {
            repo.changes().collectLatest {
                delay(CALENDAR_SETTLE_MS)
                refreshAppointments(force = true)
            }
        }
    }

    /**
     * Her appointments for the coming year, read for the full appointments list.
     *
     * Kept raw here and trimmed / ordered against the clock by [ch.heuscher.gentleconnect.model.Agenda.planned]
     * on the screen, exactly as [appointments] is for the home block. It is a separate flow and a
     * separate query on purpose: the home screen must not carry a year of rows around for a
     * question nobody asked.
     */
    private val _plannedAppointments = MutableStateFlow<List<Appointment>?>(null)
    val plannedAppointments: StateFlow<List<Appointment>?> = _plannedAppointments.asStateFlow()

    /**
     * Read her year of appointments for the list, and keep reading it while the list is open.
     *
     * Suspends until the screen goes away, like [watchRecentCalls]: an appointment that syncs in
     * or is moved while she is reading the list turns up in it. The screen's jump to the
     * appointment she arrived by is latched, so these re-reads do not move the page under her.
     */
    suspend fun watchPlannedAppointments() {
        val repo = calendarRepository ?: return
        readPlannedAppointments(repo)
        repo.changes().collectLatest {
            delay(CALENDAR_SETTLE_MS)
            readPlannedAppointments(repo)
        }
    }

    private suspend fun readPlannedAppointments(repo: CalendarRepository) {
        // The clock is asked on every read, not once on the way in: the list can stay open over
        // midnight.
        val now = System.currentTimeMillis()
        _plannedAppointments.value = repo.appointments(
            from = startOfDay(now) - DAY_MS,
            until = startOfDay(now + Agenda.LIST_AHEAD * DAY_MS)
        )
    }

    // Last, so the flows above exist before the first read writes to them. Once, here, so her
    // appointments are on the home screen the first time it is drawn rather than after she has
    // left the app and come back to it.
    init {
        refreshAppointments(force = true)
    }

    private companion object {
        /**
         * How soon her calendar may be queried again after a read.
         *
         * Stepping out of the launcher to another app and returning seconds later should not hit
         * the Calendar provider repeatedly unless a change actually justified it.
         */
        const val APPOINTMENTS_REFRESH_DEBOUNCE_MS = 30_000L

        /**
         * How long the calendar has to be quiet before a change is read. A sync arrives as a
         * burst of rows; reading after the burst rather than during it is one query, not dozens.
         */
        const val CALENDAR_SETTLE_MS = 1_000L
    }
}
