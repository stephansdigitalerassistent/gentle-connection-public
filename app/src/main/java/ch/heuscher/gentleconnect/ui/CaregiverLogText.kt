package ch.heuscher.gentleconnect.ui

import android.content.Context
import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.CaregiverLog
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One fault — or one deliberate action — in words, with when it happened. */
data class CaregiverLogLine(
    val whenText: String,
    val whatText: String,
    /**
     * Whether this line is something somebody did rather than something that went wrong.
     *
     * Carried here so the dialog can show the faults on their own without knowing anything about
     * event kinds. See [ch.heuscher.gentleconnect.data.CaregiverLog.isCaregiverAction].
     */
    val isCaregiverAction: Boolean = false
)

/**
 * Puts the caregiver's log into plain language.
 *
 * The mapping from event to sentence is a `when` over the enum with no `else` branch, so adding a
 * kind without giving it words is a compile error rather than a blank line on the one screen that
 * exists to explain things.
 *
 * These sentences are the opposite of the ones on her screen. Hers name no fault and offer no
 * explanation; these name the fault, say what it means for her, and where there is something to be
 * done about it they say that too — the reader is somebody who arrived wanting to know why her
 * phone is not doing what her family expects.
 *
 * Resolved through [Context.getString] rather than `stringResource`, so that everything below the
 * first two lines is ordinary Kotlin running once per event rather than a composable call per
 * sentence inside a loop.
 */
@Composable
fun caregiverLogLines(events: List<CaregiverEvent>): List<CaregiverLogLine> {
    val context = LocalContext.current
    val locale = Locale.forLanguageTag(stringResource(R.string.tts_language_tag))
    val formats = remember(locale) {
        LogTimeFormats(
            time = SimpleDateFormat("HH:mm", locale),
            date = SimpleDateFormat(
                AndroidDateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"),
                locale
            )
        )
    }

    return events.map { event -> logLine(context, formats, event) }
}

private class LogTimeFormats(
    val time: SimpleDateFormat,
    val date: SimpleDateFormat
)

private fun logLine(
    context: Context,
    formats: LogTimeFormats,
    event: CaregiverEvent
): CaregiverLogLine {
    // The name she sees, or nothing at all — never the identity behind it. Anonymised on the way
    // in as well; this is the second gate, for rows an earlier build may already have written.
    val subject = CaregiverLog.anonymise(event.subject)
        ?: context.getString(R.string.caregiver_log_someone)

    val what = when (event.kind) {
        CaregiverEventKind.MESSAGE_NOT_SENT ->
            context.getString(R.string.caregiver_log_message_not_sent, subject)

        CaregiverEventKind.GROUP_MESSAGE_PART_SENT ->
            context.getString(
                R.string.caregiver_log_group_part_sent,
                subject,
                event.count,
                event.total
            )

        CaregiverEventKind.GROUP_MEMBERSHIP_UNKNOWN ->
            context.getString(R.string.caregiver_log_group_membership_unknown, subject)

        CaregiverEventKind.GROUP_NO_LONGER_MEMBER ->
            context.getString(R.string.caregiver_log_group_no_longer_member, subject)

        CaregiverEventKind.GROUP_NOBODY_LEFT ->
            context.getString(R.string.caregiver_log_group_nobody_left, subject)

        CaregiverEventKind.MESSENGER_CONNECTION_FAILED ->
            context.getString(R.string.caregiver_log_messenger_connection_failed)

        CaregiverEventKind.CONTACT_SYNC_FAILED ->
            context.getString(R.string.caregiver_log_contact_sync_failed)

        CaregiverEventKind.CONTACTS_NOT_UNDERSTOOD ->
            context.getString(
                R.string.caregiver_log_contacts_not_understood,
                event.count,
                event.total
            )

        // Deliberately without the subject: this line is about a caller nobody has saved, and the
        // number is not something to put on a website. It is in the telephone's own list of calls
        // at the same minute, on the phone, where somebody holding it can see it.
        CaregiverEventKind.CALL_FROM_STRANGER_ENDED ->
            context.getString(R.string.caregiver_log_call_from_stranger_ended)

        CaregiverEventKind.MMS_DROPPED_UNSUPPORTED ->
            context.getString(R.string.caregiver_log_mms_dropped_unsupported)

        CaregiverEventKind.MESSAGES_LOST_ON_ARRIVAL ->
            context.getString(R.string.caregiver_log_messages_lost_on_arrival)

        CaregiverEventKind.SMS_NOT_STORED ->
            context.getString(R.string.caregiver_log_sms_not_stored)

        CaregiverEventKind.WHATSAPP_CALL_UNANSWERED ->
            context.getString(R.string.caregiver_log_whatsapp_call_unanswered)

        CaregiverEventKind.MESSAGE_DELETED_ON_PHONE ->
            context.getString(R.string.caregiver_log_message_deleted_on_phone, subject)

        CaregiverEventKind.CONVERSATION_DELETED_ON_PHONE ->
            context.getString(R.string.caregiver_log_conversation_deleted_on_phone, subject)

        // Deliberately without the number, for the same reason as CALL_FROM_STRANGER_ENDED: this
        // log is published to the caregiver website, and which number was blocked is not
        // something to put there. Whoever blocked it is holding the phone, where Android's own
        // list of blocked numbers says exactly which one it was.
        CaregiverEventKind.NUMBER_BLOCKED ->
            context.getString(R.string.caregiver_log_number_blocked)

        CaregiverEventKind.CONVERSATION_MUTED ->
            context.getString(R.string.caregiver_log_conversation_muted, subject)

        CaregiverEventKind.CALL_FAILED_NOT_FROM_ABROAD ->
            context.getString(R.string.caregiver_log_call_failed_abroad_without_threema, subject)

        CaregiverEventKind.CALL_FAILED_NO_CONNECTION ->
            context.getString(R.string.caregiver_log_call_failed_no_connection, subject)

        CaregiverEventKind.THREEMA_CALL_FROM_STRANGER_ENDED ->
            context.getString(R.string.caregiver_log_threema_call_from_stranger_ended)

        CaregiverEventKind.SUPPORT_OPENED_NOT_CONFIRMED ->
            context.getString(R.string.caregiver_log_support_opened_not_confirmed)

        CaregiverEventKind.RESTLESSNESS_DETECTED ->
            if (!event.subject.isNullOrBlank()) {
                "${context.getString(R.string.caregiver_log_restlessness_detected)}: ${event.subject}"
            } else {
                context.getString(R.string.caregiver_log_restlessness_detected)
            }

        CaregiverEventKind.MAIL_ACCESS_LOST ->
            context.getString(R.string.caregiver_log_mail_access_lost)
    }


    // The same day names her own screen uses, decided by the same rule — see [dayNameFor]. A log
    // read weeks after the fault is mostly dates, so the date is the case that has to be right.
    val day = when (dayNameFor(event.at)) {
        DayName.TODAY -> context.getString(R.string.day_today)
        DayName.YESTERDAY -> context.getString(R.string.time_yesterday)
        DayName.DATE -> formats.date.format(Date(event.at))
    }

    val repeated = if (event.occurrences > 1) {
        ", " + context.getString(R.string.caregiver_log_repeated, event.occurrences)
    } else {
        ""
    }

    return CaregiverLogLine(
        whenText = "$day ${formats.time.format(Date(event.at))}$repeated",
        whatText = what,
        isCaregiverAction = CaregiverLog.isCaregiverAction(event.kind)
    )
}
