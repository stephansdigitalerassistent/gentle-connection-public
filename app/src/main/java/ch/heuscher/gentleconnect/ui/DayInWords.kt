package ch.heuscher.gentleconnect.ui

import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import ch.heuscher.gentleconnect.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val DAY_MS = 24 * 60 * 60 * 1000L

/** Which of the three ways a day gets named. */
enum class DayName { TODAY, YESTERDAY, DATE }

/**
 * Which day something belongs to, as a decision separate from the words for it.
 *
 * Pure, so it is testable and so there is still only one rule after the caregiver's log started
 * dating things too. Each boundary is computed rather than subtracted from today: on the two days
 * a year the clocks change a day is not 24 hours long.
 */
fun dayNameFor(timestamp: Long, now: Long = System.currentTimeMillis()): DayName =
    when (startOfDay(timestamp)) {
        startOfDay(now) -> DayName.TODAY
        startOfDay(now - DAY_MS) -> DayName.YESTERDAY
        else -> DayName.DATE
    }

/**
 * The day something happened, in the words she would use: today, yesterday, or the date.
 *
 * One implementation on purpose. The home screen and the conversation both date the same message,
 * and when they disagree the phone is telling her two different things about one event — which is
 * exactly how this was found: a message from Tuesday read "Yesterday" on the home screen while the
 * conversation dated it correctly.
 */
@Composable
fun dayInWords(timestamp: Long): String = dayInWords(timestamp, "EEEEdMMMM")

/**
 * The same day, in the few characters a list row can spare: "13. Aug." rather than
 * "Donnerstag, 13. August".
 *
 * For the corner of a row, where the day shares one line with the name of the person it is
 * about. Found on the phone: with the full form there, two of the five messages on the screen
 * had been reduced to "B…" and "E…" — the date had taken the whole line and the only thing on
 * the row that says *who wrote* was a single letter. The day is a detail; the person is not.
 *
 * Still [dayNameFor]'s decision, and still today and yesterday in words, so this cannot name a
 * day differently from the conversation it opens. Shorter is not a different answer: "13. Aug."
 * and "Donnerstag, 13. August" are the same Thursday, and only one of them fits.
 */
@Composable
fun dayInWordsShort(timestamp: Long): String = dayInWords(timestamp, "dMMM")

/**
 * Today, yesterday, or the date written to the given skeleton.
 *
 * A skeleton rather than a pattern: the order of day and month, and whether the month is
 * followed by a full stop, are the locale's business and not this app's — "Aug 13" in English
 * and "13. Aug." in German come out of the same request.
 */
@Composable
private fun dayInWords(timestamp: Long, dateSkeleton: String): String {
    val locale = Locale.forLanguageTag(stringResource(R.string.tts_language_tag))
    val dateFormat = remember(locale, dateSkeleton) {
        SimpleDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, dateSkeleton), locale)
    }

    // "Yesterday" quietly becoming a date is the kind of small wrongness that costs her a moment
    // of doubt about what she is looking at, so the boundary rule lives in one place — see
    // [dayNameFor].
    return when (dayNameFor(timestamp)) {
        DayName.TODAY -> stringResource(R.string.day_today)
        DayName.YESTERDAY -> stringResource(R.string.time_yesterday)
        DayName.DATE -> dateFormat.format(Date(timestamp))
    }
}

/**
 * Midnight before this instant, in the phone's own time zone.
 *
 * Not `timestamp / DAY_MS`: that divides at midnight UTC, so on a Swiss evening every message
 * after eleven would be filed under tomorrow.
 */
fun startOfDay(timestamp: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timestamp
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}
