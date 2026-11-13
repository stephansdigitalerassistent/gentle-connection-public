package ch.heuscher.gentleconnect.ui

import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.foundation.layout.PaddingValues
import ch.heuscher.gentleconnect.ui.components.GentleKey
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.Agenda
import ch.heuscher.gentleconnect.model.Appointment
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.ui.components.AppointmentDetailDialog
import ch.heuscher.gentleconnect.ui.components.BottomTab
import ch.heuscher.gentleconnect.ui.components.GentleBottomBar
import ch.heuscher.gentleconnect.ui.components.GentleScreenHeader
import ch.heuscher.gentleconnect.ui.components.GentleScrollbar
import ch.heuscher.gentleconnect.ui.components.OUTSIDE_THE_LANE
import ch.heuscher.gentleconnect.ui.components.SCROLLBAR_LANE
import ch.heuscher.gentleconnect.ui.components.scrollbarFor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Everything that is coming up on her calendar, sorted soonest first.
 *
 * Reached by tapping "More appointments" on the home screen when there are appointments
 * beyond what the three lines on home can show. Like the call list under Call, this screen
 * lives under [BottomTab.HOME], so Home is marked in the bottom bar.
 *
 * Deliberately has no month grid: a grid is a navigation problem before it is an answer,
 * thirty cells to search through for the one fact she wanted, and a way to get lost in a month
 * that is not this one. Instead, appointments are listed in plain language with month headings
 * separating them.
 */
@Composable
fun AppointmentsScreen(
    /**
     * Everything ahead of her, already ordered by [Agenda.planned] — or null while nobody has
     * asked the calendar yet, which is not the same as an empty one. Drawn as nothing rather than
     * as "nothing planned", so that the screen never tells her something untrue for the half
     * second before the answer arrives. See [ch.heuscher.gentleconnect.ui.AppointmentsViewModel.appointments],
     * where the same two nothings are kept apart.
     */
    appointments: List<Appointment>?,
    now: Long,
    /**
     * The appointment she pressed on the home screen, when that is how she arrived here — the
     * list then opens at it instead of at the top. Null when she came by the "More appointments"
     * button or by the bar along the bottom, which are both requests for the list itself.
     */
    openAt: Appointment? = null,
    onBackToHome: () -> Unit,
    /**
     * The Home key in the bar at the bottom. Not [onBackToHome]: going back returns her to where
     * she was on home, while the Home key starts home over at the top, as the phone's own does.
     */
    onHomeTab: () -> Unit = onBackToHome,
    onCallRequested: () -> Unit,
    onMessagesRequested: () -> Unit,
    onMailRequested: () -> Unit,
    onSpeakRequested: (String) -> Unit,
    onStopSpeaking: () -> Unit = {},
    messagesMarked: Boolean = false,
    support: SupportButtonState = SupportButtonState.None,
    onSupportPressed: () -> Unit = {},
    isRestless: Boolean = false,
    modifier: Modifier = Modifier
) {
    var openAppointment by remember { mutableStateOf<Appointment?>(null) }
    val listState = rememberLazyListState()

    // Keyed on the list as well as on the appointment, because the two arrive apart: the screen
    // is drawn first and the calendar is read a moment later (see watchPlannedAppointments),
    // so a jump attempted on the way in would be a jump into an empty list. Not finding it is an
    // ordinary answer and means stay at the top — the appointment ended between her pressing it
    // and this list being built.
    //
    // And once only. The calendar is re-read while she is standing here, and without the latch
    // every re-read would take the screen off whatever she had scrolled to and put it back on the
    // appointment she arrived by — the phone tugging the page out of her hands.
    var jumped by remember(openAt) { mutableStateOf(false) }
    LaunchedEffect(openAt, appointments, jumped) {
        if (jumped) return@LaunchedEffect
        val target = openAt ?: return@LaunchedEffect
        val list = appointments ?: return@LaunchedEffect
        val row = Agenda.indexIn(list, target.id, target.begin) ?: return@LaunchedEffect
        listState.scrollToItem(row)
        jumped = true
    }

    val locale = Locale.forLanguageTag(stringResource(R.string.tts_language_tag))

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            GentleBottomBar(
                selected = BottomTab.HOME,
                onHomeClicked = onHomeTab,
                onCallClicked = onCallRequested,
                onMessageClicked = onMessagesRequested,
                onLookClicked = onMailRequested,
                messagesMarked = messagesMarked
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 20.dp, top = 20.dp, end = SCROLLBAR_LANE, bottom = 20.dp)
        ) {
            GentleScreenHeader(
                modifier = Modifier.padding(end = OUTSIDE_THE_LANE),
                title = stringResource(R.string.appointments_title),
                onBack = onBackToHome,
                support = support,
                onSupportPressed = onSupportPressed,
                isRestless = isRestless
            )

            // Bound to a name rather than read through the parameter inside the lambdas below:
            // a smart cast the compiler declines is a round trip through CI to find out about.
            val planned = appointments

            if (planned != null && planned.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = OUTSIDE_THE_LANE),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.appointments_empty),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (planned != null) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.padding(end = OUTSIDE_THE_LANE),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Keyed by the instance and the day it falls on rather than by the id alone.
                        // A repeated key is not a wrong drawing, it is an exception, and the cost
                        // of being wrong about how unique the provider's instance ids are is her
                        // screen going away.
                        itemsIndexed(
                            planned,
                            key = { _, item -> "${item.id}-${item.begin}" }
                        ) { index, appointment ->
                            val concerns = remember(appointment, now) {
                                Agenda.concerns(appointment, now, ::startOfDay)
                            }
                            val isFirstOfMonth = if (index == 0) {
                                true
                            } else {
                                val prevConcerns = Agenda.concerns(planned[index - 1], now, ::startOfDay)
                                isDifferentMonth(concerns, prevConcerns)
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isFirstOfMonth) {
                                    val heading = remember(concerns, now, locale) {
                                        monthHeader(concerns, now, locale)
                                    }
                                    Text(
                                        text = heading,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp)
                                    )
                                }

                                AppointmentCard(
                                    appointment = appointment,
                                    now = now,
                                    locale = locale,
                                    onClick = { openAppointment = appointment }
                                )
                            }
                        }
                    }

                    GentleScrollbar(
                        bar = scrollbarFor(listState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }

    openAppointment?.let { appointment ->
        AppointmentDetailDialog(
            appointment = appointment,
            onSpeakRequested = onSpeakRequested,
            onDismiss = {
                openAppointment = null
                onStopSpeaking()
            }
        )
    }
}

/**
 * One appointment card on the full appointments list.
 */
@Composable
private fun AppointmentCard(
    appointment: Appointment,
    now: Long,
    locale: Locale,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GentleKey(
        onClick = onClick,
        onClickLabel = stringResource(R.string.appointment_show),
        modifier = modifier.fillMaxWidth(),
        face = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        minHeight = 0.dp,
        contentPadding = PaddingValues(20.dp),
        contentAlignment = Alignment.Start,
        fillWidth = true,
        spacing = 0.dp
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Line 1: Date & time (or range for spans), plus howFarOff when applicable
            Text(
                text = appointmentWhen(appointment, now, locale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Line 2: Title
            Text(
                text = appointment.title.ifBlank {
                    stringResource(R.string.appointment_untitled)
                },
                style = MaterialTheme.typography.titleMedium
            )

            // Line 3: Location (if present)
            val place = appointment.location
            if (place != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = stringResource(R.string.appointment_location),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = place,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Full date and time formatting for the appointment list: "Dienstag, 16. September, 14:00 · in 3 Wochen",
 * "Dienstag, 16. September – Freitag, 19. September" for multi-day spans, or without time for all-day events.
 */
@Composable
private fun appointmentWhen(appointment: Appointment, now: Long, locale: Locale): String {
    val dateFormat = remember(locale) {
        SimpleDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale)
    }
    val timeFormat = remember(locale) { SimpleDateFormat("HH:mm", locale) }

    val datePart = if (appointment.isAllDay) {
        if (Agenda.spansDays(appointment, ::startOfDay)) {
            // The end is exclusive — midnight at the far side of the last day — so the last day it
            // covers is the millisecond before.
            val endStr = dateFormat.format(Date(maxOf(appointment.end - 1, appointment.begin)))
            if (Agenda.alreadyRunning(appointment, now, ::startOfDay)) {
                stringResource(R.string.calendar_until, endStr)
            } else {
                stringResource(
                    R.string.calendar_range,
                    dateFormat.format(Date(appointment.begin)),
                    endStr
                )
            }
        } else {
            dateFormat.format(Date(appointment.begin))
        }
    } else {
        val dayStr = dateFormat.format(Date(appointment.begin))
        val timeStr = timeFormat.format(Date(appointment.begin))
        stringResource(R.string.calendar_when, dayStr, timeStr)
    }

    val days = Agenda.daysUntil(appointment, now, ::startOfDay)
    val far = howFarOff(days)

    return if (far != null) {
        stringResource(R.string.calendar_far, datePart, far)
    } else {
        datePart
    }
}

private fun isDifferentMonth(t1: Long, t2: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = t1 }
    val c2 = Calendar.getInstance().apply { timeInMillis = t2 }
    return c1.get(Calendar.YEAR) != c2.get(Calendar.YEAR) ||
        c1.get(Calendar.MONTH) != c2.get(Calendar.MONTH)
}

private fun monthHeader(concerns: Long, now: Long, locale: Locale): String {
    val cConcerns = Calendar.getInstance().apply { timeInMillis = concerns }
    val cNow = Calendar.getInstance().apply { timeInMillis = now }
    val appointmentYear = cConcerns.get(Calendar.YEAR)
    val currentYear = cNow.get(Calendar.YEAR)

    val skeleton = if (appointmentYear == currentYear) "MMMM" else "yyyyMMMM"
    val pattern = AndroidDateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).format(Date(concerns))
}
