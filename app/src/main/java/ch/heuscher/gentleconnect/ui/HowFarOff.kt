package ch.heuscher.gentleconnect.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.heuscher.gentleconnect.R

/**
 * How far off an appointment is, in spoken words ("in 5 days", "in 3 weeks", "in 2 months").
 *
 * Appended to the full date line on the appointments list when the appointment is far enough
 * off that a relative phrase adds orientation beyond the date.
 *
 * Rules:
 * - 0 and 1 -> null. The row already says "today" or "tomorrow" through the date; a second phrase
 *   saying the same thing twice is noise.
 * - 2..13 -> [R.string.in_days] with `days` ("in %d days" / "in %d Tagen")
 * - 14..59 -> [R.string.in_weeks] with `days / 7` ("in %d weeks" / "in %d Wochen")
 * - 60 and beyond -> [R.string.in_months] with `days / 30` ("in %d months" / "in %d Monaten")
 *
 * Integer division on purpose: it never promises her more time than she has (13 days is still "in 13 days",
 * 14 days becomes "in 2 weeks", 59 days is "in 8 weeks", 60 days becomes "in 2 months").
 *
 * Deliberately no plurals in strings.xml: by construction every count these strings ever receive is
 * 2 or more (14 / 7 = 2, 60 / 30 = 2), so the singular form is unreachable.
 */
@Composable
fun howFarOff(days: Int): String? =
    when {
        days <= 1 -> null
        days in 2..13 -> stringResource(R.string.in_days, days)
        days in 14..59 -> stringResource(R.string.in_weeks, days / 7)
        else -> stringResource(R.string.in_months, days / 30)
    }
