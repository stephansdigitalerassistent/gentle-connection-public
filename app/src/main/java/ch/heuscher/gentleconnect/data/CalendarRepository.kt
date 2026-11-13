package ch.heuscher.gentleconnect.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import ch.heuscher.gentleconnect.model.Appointment
import ch.heuscher.gentleconnect.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * Her calendar, read off the phone rather than off the internet.
 *
 * Google Calendar is already on this phone. Android syncs it into the calendar provider for
 * whichever Google account is signed in, keeps it fresh, and keeps it there when the phone has no
 * signal — so the way to show her appointments is to read that, not to sign the app into Google
 * and ask the Calendar API.
 *
 * That is not only the cheaper road, it is the only one that ends anywhere. The calendar scope is
 * a sensitive one: an unverified app asking for it puts a warning screen in front of whoever is
 * signing in, and there is no chance of her getting past it — nor of this app being verified,
 * since it is handed out by App Distribution and not by Play. Everything OAuth would buy is
 * already on the device.
 *
 * The part that earns the most is the one that looks like a detail: the provider's `Instances`
 * table has recurring events already worked out into the actual days they fall on. "Physiotherapy
 * every Tuesday" arrives as Tuesdays. Reading an .ics feed instead would mean implementing
 * recurrence rules, which is not a small thing and is certainly not a basic one.
 *
 * **How an appointment gets onto her phone**: her daughter puts it in her own Google calendar and
 * shares that calendar with her mother's account. Nobody touches this phone. The one catch is
 * worth knowing about because it is silent: a newly shared calendar is not synced to the device
 * until it is ticked once under "Sync" at calendar.google.com, and until then it simply looks like
 * an empty week.
 */
class CalendarRepository(private val context: Context) {

    /**
     * Whether she has let this app read her calendar.
     *
     * Asked again in each method that goes on to query, which looks like a redundancy and is not:
     * the tool that reads this code for missing permissions only trusts a check it can see in the
     * method doing the asking, and lint failing is the build failing here. See
     * [ch.heuscher.gentleconnect.service.Attention], where the same thing had to be spelled out
     * for the same reason. The one that decides what she is *shown* is the check in
     * [appointments]: it is the only one that can tell a refusal apart from a free day.
     */
    private fun allowed(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Everything in her calendar between [from] and [until], or null when there is no calendar on
     * this phone to read.
     *
     * The difference between null and an empty list is the whole reason this returns the first:
     * an empty list means today is free, which is a fact worth putting on her screen, and null
     * means nobody has ever set a calendar up here, which is not something to tell her about at
     * all. A phone with no Google account would otherwise spend the rest of its life announcing
     * that she has nothing on.
     *
     * A wider window than the screen shows, on purpose. Trimming to what is coming is
     * [ch.heuscher.gentleconnect.model.Agenda]'s job and happens against the clock as it ticks;
     * this only has to make sure the appointment is in the room when it is asked for.
     */
    suspend fun appointments(from: Long, until: Long): List<Appointment>? =
        withContext(Dispatchers.IO) {
            if (!allowed()) return@withContext null
            try {
                if (!hasACalendar()) return@withContext null
                read(from, until)
            } catch (unreadable: Exception) {
                // A revoked permission, a provider that is not there, a cursor that dies under a
                // sync. None of it is her doing and none of it has a thing she could press, so it
                // reads the same way as having no calendar: the block is not on her screen.
                null
            }
        }

    /**
     * A tick whenever anything in the calendar provider changes: Google's sync bringing in an
     * appointment her daughter has just added, one being moved or called off, a calendar being
     * shared or unticked.
     *
     * The calendar is still read, not held — this only says *when* to read it again, and keeps no
     * cursor open between times, the same as [CallLogRepository.changes]. It exists because the
     * home screen is the one screen she never leaves: an appointment that arrived while she was
     * looking at it used to wait there, unseen, until she went to another app and came back.
     *
     * The whole provider rather than one table, because a single sync touches events, instances
     * and reminders in turn and any of them is reason enough. Conflated, because that sync writes
     * dozens of rows in one breath and they are one reason to read again, not dozens.
     *
     * Empty, never an error, without the permission — see [appointments] for why a refusal must
     * not look like anything at all.
     */
    fun changes(): Flow<Unit> {
        if (!allowed()) return emptyFlow()
        return callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
            val registered = try {
                context.contentResolver.registerContentObserver(
                    CalendarContract.CONTENT_URI,
                    true,
                    observer
                )
                true
            } catch (e: SecurityException) {
                // Taken away between the question above and the asking here. Nothing will tick;
                // the screen keeps what it last read, which is where it was before this existed.
                false
            }
            awaitClose {
                if (registered) context.contentResolver.unregisterContentObserver(observer)
            }
        }.conflate()
    }

    /**
     * Whether this phone has a calendar at all.
     *
     * Visible ones only, which is the same question the Google Calendar app answers with its
     * tick-boxes: a calendar somebody has switched off there is one they have decided not to look
     * at, and her phone is not the place to overrule that.
     */
    private fun hasACalendar(): Boolean {
        if (!allowed()) return false

        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null
        )?.use { it.moveToFirst() } ?: false
    }

    private fun read(from: Long, until: Long): List<Appointment> {
        if (!allowed()) return emptyList()

        // The window goes in the path rather than in the selection — that is how the provider
        // wants to be asked, and asking any other way returns every instance of every recurring
        // event since the calendar was created.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, from)
            ContentUris.appendId(this, until)
        }.build()

        // Declined events are left out here rather than drawn in grey. She has no way to un-decline
        // one and no reason to read about a thing she is not going to; the same goes for one the
        // organiser has called off, which the provider keeps rather than deletes.
        val selection = "${CalendarContract.Instances.VISIBLE} = 1" +
            " AND (${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL" +
            " OR ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})" +
            " AND (${CalendarContract.Instances.STATUS} IS NULL" +
            " OR ${CalendarContract.Instances.STATUS} != ${CalendarContract.Events.STATUS_CANCELED})"

        val projection = arrayOf(
            // The instance, not the event. A daily appointment is one event and three rows in
            // three days, and keying the screen off the event would make them one row.
            CalendarContract.Instances._ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            // Location is the only other field read off the event. No description, no organiser,
            // no attendees: Appointment's doc promises the repository keeps the rest to itself.
            CalendarContract.Instances.EVENT_LOCATION
        )

        return context.contentResolver.query(
            uri,
            projection,
            selection,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            val appointments = mutableListOf<Appointment>()
            while (cursor.moveToNext()) {
                val allDay = cursor.getInt(4) == 1
                val begin = cursor.getLong(2)
                val end = cursor.getLong(3)
                appointments += Appointment(
                    id = cursor.getLong(0),
                    // Kept as it is written, including an empty one — the screen has the word for
                    // an appointment nobody named, in both languages, and this object has no
                    // business knowing which language the phone is in.
                    title = cursor.getString(1).orEmpty().trim(),
                    begin = if (allDay) local(begin) else begin,
                    end = if (allDay) local(end) else end,
                    isAllDay = allDay,
                    location = Place.shorten(cursor.getString(5))
                )
            }
            appointments
        }.orEmpty()
    }

    /**
     * Midnight UTC, as the midnight she means.
     *
     * An all-day event has no time of day, so the provider stores it against a bare date at
     * midnight UTC. Read as an instant on a Swiss phone that is two o'clock in the morning of the
     * right day, and west of Greenwich it is the evening of the day before — which is how an
     * all-day event on Thursday comes to sit under Wednesday. Shifting it by the offset puts it
     * at midnight where she is, so that every instant leaving this class means what it says and
     * nothing downstream has to know the difference.
     */
    private fun local(utcMidnight: Long): Long =
        utcMidnight - TimeZone.getDefault().getOffset(utcMidnight)
}
