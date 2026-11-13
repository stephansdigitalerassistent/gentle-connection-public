package ch.heuscher.gentleconnect.ui.components

import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.Agenda
import ch.heuscher.gentleconnect.model.Appointment
import ch.heuscher.gentleconnect.ui.startOfDay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ch.heuscher.gentleconnect.util.traceTaps

/**
 * One appointment, opened from her list of them.
 *
 * **What it adds is the end, and a voice.** The list already names the day, the time it starts
 * and where it is; the one fact it has no room for is when the thing is over, which is the fact
 * that decides whether she can be fetched at four. And the read-aloud button is the whole reason
 * a dialog is worth the press at all: a doctor's appointment in small grey type is exactly the
 * kind of thing she asks somebody else to read to her, and this is the phone doing it instead.
 *
 * **A dialog and not a screen.** It is one appointment and there is nothing to do to it — no
 * editing, no deleting, nothing to navigate on from. Something she can dismiss with the back
 * button or the button on it, that leaves the list exactly where she left it, is the whole of
 * what this needs to be.
 *
 * An all-day appointment is given no times, for the reason the home block gives none: a birthday
 * that starts at 00:00 reads as something happening in the night. One that runs over several days
 * is named by its first and last day, the end being exclusive so the last day it covers is the
 * millisecond before.
 */
@Composable
fun AppointmentDetailDialog(
    appointment: Appointment,
    onSpeakRequested: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val locale = Locale.forLanguageTag(stringResource(R.string.tts_language_tag))
    val title = appointment.title.ifBlank { stringResource(R.string.appointment_untitled) }
    val whenText = appointmentDetailWhen(appointment, locale)
    val place = appointment.location
    val spokenText = stringResource(
        R.string.appointment_spoken,
        title,
        whenText,
        place ?: ""
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .traceTaps("appointment")
                .padding(8.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Title, big (titleLarge)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // 2. When it is (from and to)
                Text(
                    text = whenText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 3. Location (if present)
                if (place != null && place.isNotBlank()) {
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
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 4. Two buttons: Read aloud and Close
                ReadAloudKey(
                    text = spokenText,
                    onSpeakRequested = onSpeakRequested,
                    modifier = Modifier.fillMaxWidth()
                )

                GentlePrimaryKey(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    minHeight = 56.dp,
                    fillWidth = true
                ) {
                    Text(
                        text = stringResource(R.string.close),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun appointmentDetailWhen(appointment: Appointment, locale: Locale): String {
    val dateFormat = remember(locale) {
        SimpleDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale)
    }
    val timeFormat = remember(locale) { SimpleDateFormat("HH:mm", locale) }

    return if (appointment.isAllDay) {
        if (Agenda.spansDays(appointment, ::startOfDay)) {
            val endStr = dateFormat.format(Date(maxOf(appointment.end - 1, appointment.begin)))
            stringResource(
                R.string.calendar_range,
                dateFormat.format(Date(appointment.begin)),
                endStr
            )
        } else {
            dateFormat.format(Date(appointment.begin))
        }
    } else {
        val startDayStr = dateFormat.format(Date(appointment.begin))
        val startTimeStr = timeFormat.format(Date(appointment.begin))
        val endTimeStr = timeFormat.format(Date(appointment.end))

        if (startOfDay(appointment.begin) == startOfDay(maxOf(appointment.end - 1, appointment.begin))) {
            if (appointment.begin == appointment.end) {
                stringResource(R.string.calendar_when, startDayStr, startTimeStr)
            } else {
                val timeRange = stringResource(R.string.calendar_range, startTimeStr, endTimeStr)
                stringResource(R.string.calendar_when, startDayStr, timeRange)
            }
        } else {
            val startPart = stringResource(R.string.calendar_when, startDayStr, startTimeStr)
            val endDayStr = dateFormat.format(Date(appointment.end))
            val endPart = stringResource(R.string.calendar_when, endDayStr, endTimeStr)
            stringResource(R.string.calendar_range, startPart, endPart)
        }
    }
}
