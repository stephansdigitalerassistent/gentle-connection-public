package ch.heuscher.gentleconnect.ui.components

import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.Agenda
import ch.heuscher.gentleconnect.model.AgendaDay
import ch.heuscher.gentleconnect.model.Appointment
import ch.heuscher.gentleconnect.ui.startOfDay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What is coming, on her home screen.
 *
 * It sits directly under the clock every morning, finishing the sentence the clock starts: the
 * clock says which day it is, and this says what that day holds. It stays there on the days
 * somebody has written too — the nudge card comes underneath rather than above, so the one part
 * of this screen she finds by position is always in the same place. It is still drawn quietly:
 * the nudge card is the one thing on this screen allowed to be loud, and being first no longer
 * has to mean being loudest.
 *
 * **Nothing on today is written out, not left blank.** Everywhere else in this app an empty
 * section goes away, because an empty list of messages is the absence of a thing rather than a
 * fact. A free day is the opposite: "is there anything today?" is a question she asks often, and
 * a screen that answers it by saying nothing at all is a screen she has to remember the answer
 * for. It is also the only reason this can be trusted the rest of the time — a block that shows
 * up only when it has something to say cannot be read as "then there is nothing on".
 *
 * **And what comes after today, when today is empty.** Her calendar holds a handful of entries a
 * month rather than a full diary, so the empty card is the usual one — and on a screen that looks
 * three days ahead, "Nothing on today" was the whole of what it said even when the doctor was nine
 * days away, a fact the phone had in its hand and sat on. So the empty card carries a second line
 * naming the next thing there is, whenever there is one. Only the empty card: when the three days
 * have something in them, what comes after them is not news, and it must not take a line away from
 * what is.
 *
 * **Two doors, and they lead to different places.** The "More appointments" button at the foot of
 * the block is the way to the whole list, and it is the third thing in the block exactly as it is
 * in every other section on this screen — see [MoreRow]. A press on one of the cards is not the
 * same request: it means *that* appointment, so it opens the list already scrolled to it rather
 * than at the top, which for a woman with a fortnight of things in her calendar is the difference
 * between arriving at the answer and arriving at a list to search. The empty card is not
 * pressable — there is nothing it could be a card *about*.
 *
 * **No heading over it.** Every other section on this screen is a list of things of one kind and
 * says which kind it is; this one is a sentence about today, and "Appointments" written above
 * "Nothing on today" is a word she has to read before the sentence that answers her. It also cost
 * a line at the top of the screen, which is the most expensive line there is here.
 *
 * The block is absent altogether when there is no calendar on the phone. See
 * [ch.heuscher.gentleconnect.data.CalendarRepository]: a phone nobody has set a calendar up on
 * must not spend its life announcing that she has nothing on.
 */
@Composable
fun AgendaSection(
    appointments: List<Appointment>,
    now: Long,
    hasMore: Boolean = false,
    onOpenList: () -> Unit = {},
    onOpenAppointment: (Appointment) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val coming = remember(appointments, now) {
        Agenda.upcoming(appointments, now, ::startOfDay)
    }
    val next = remember(appointments, now) {
        Agenda.next(appointments, now, ::startOfDay)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (coming.isEmpty()) {
            AgendaCard(onClick = null) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.calendar_nothing_today),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (next != null) {
                        Text(
                            text = stringResource(R.string.calendar_next, whenNextIs(next)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            coming.forEach { appointment ->
                AgendaCard(onClick = { onOpenAppointment(appointment) }) {
                    // 12.dp, the padding of the contact rows below rather than the 20.dp this
                    // had. Two cards' worth of air at the top of the screen is a line of
                    // somebody's name at the bottom of it.
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = whenItIs(appointment, now),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            // An appointment somebody put in without naming it. "Termin" is
                            // not much, but a card with a time on it and nothing else reads
                            // as the phone having lost the rest.
                            text = appointment.title.ifBlank {
                                stringResource(R.string.appointment_untitled)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            // Two lines, and then it stops. A calendar entry can be a
                            // sentence — work calendars are full of them — and a card that
                            // grows with its longest title pushes her son's face off the
                            // screen underneath it. Lines rather than a height in
                            // millimetres, so that turning the text size up makes the card
                            // taller, as it should, instead of cutting the words in half.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // The third thing in the block, wherever the two above it came from — the same button,
        // in the same place, as the one under her faces and under her messages. A section that
        // ends differently from the section under it is a section she has to learn separately.
        if (hasMore) {
            MoreRow(
                label = stringResource(R.string.show_more_appointments),
                onClick = onOpenList
            )
        }
    }
}

/**
 * One card in the block, and — when it is about an appointment rather than about the absence of
 * one — the way to that appointment in the full list.
 *
 * @param onClick this card's own appointment, or null on the "nothing on today" card, which is
 *   about no appointment and so cannot lead to one.
 */
@Composable
private fun AgendaCard(onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    val label = stringResource(R.string.open_appointment)
    // Bound to a name: a smart cast the compiler declines is a round trip through CI to find
    // out about.
    val openTheList = onClick

    // Only a card that leads somewhere is a key. "Nothing on today" stays flat on the page,
    // because a lip on it would promise a press that does nothing.
    if (openTheList == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            content()
        }
        return
    }

    GentleKey(
        onClick = openTheList,
        onClickLabel = label,
        modifier = Modifier.fillMaxWidth(),
        face = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        minHeight = 0.dp,
        contentPadding = PaddingValues(0.dp),
        contentAlignment = Alignment.Start,
        fillWidth = true,
        spacing = 0.dp
    ) {
        Column(modifier = Modifier.weight(1f)) { content() }
    }
}

/**
 * "Today, 14:00", "Tomorrow", "Friday, 10:00".
 *
 * The day comes first because it is what decides whether the rest matters to her this morning.
 * An all-day event is given no time at all rather than a midnight — a birthday at 00:00 reads as
 * something happening in the night.
 *
 * Something that began before today is given no time either, for the same reason turned around:
 * it is on today, but it did not start today, and printing the hour it started would name a
 * moment on a day she is not being shown. "Today" alone is the whole of what is true.
 *
 * The weekday is safe to use unqualified only because [Agenda.DAYS] is short: within three days
 * there is exactly one Friday, and "Friday" cannot be read as the one next week.
 */
@Composable
private fun whenItIs(appointment: Appointment, now: Long): String {
    val locale = Locale.forLanguageTag(stringResource(R.string.tts_language_tag))
    // The pattern comes from the locale and not from here: a phone that fell back to the English
    // strings must not be handed a German weekday to read out. See the clock on the home screen,
    // where that was found.
    val weekdayFormat = remember(locale) {
        SimpleDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, "EEEE"), locale)
    }
    val timeFormat = remember(locale) { SimpleDateFormat("HH:mm", locale) }

    val concerns = Agenda.concerns(appointment, now, ::startOfDay)
    val day = when (Agenda.dayOf(appointment, now, ::startOfDay)) {
        AgendaDay.TODAY -> stringResource(R.string.day_today)
        AgendaDay.TOMORROW -> stringResource(R.string.day_tomorrow)
        AgendaDay.LATER -> weekdayFormat.format(Date(concerns))
    }

    if (appointment.isAllDay || concerns != appointment.begin) return day

    return stringResource(R.string.calendar_when, day, timeFormat.format(Date(appointment.begin)))
}

/**
 * The day and time of an appointment further ahead: "Dienstag, 16. September, 14:00" or
 * "Dienstag, 16. September" for an all-day event.
 *
 * The bare weekday used by [whenItIs] is deliberately NOT reusable here: it is unambiguous only
 * because that window is three days long, and "Tuesday" for something three weeks out is a lie.
 * Full date, always.
 */
@Composable
private fun whenNextIs(appointment: Appointment): String {
    val locale = Locale.forLanguageTag(stringResource(R.string.tts_language_tag))
    val dateFormat = remember(locale) {
        SimpleDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale)
    }
    val timeFormat = remember(locale) { SimpleDateFormat("HH:mm", locale) }

    val day = dateFormat.format(Date(appointment.begin))
    if (appointment.isAllDay) return day

    return stringResource(R.string.calendar_when, day, timeFormat.format(Date(appointment.begin)))
}
