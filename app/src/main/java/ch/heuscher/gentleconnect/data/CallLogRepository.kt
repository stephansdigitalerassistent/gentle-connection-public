package ch.heuscher.gentleconnect.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import ch.heuscher.gentleconnect.model.CallDirection
import ch.heuscher.gentleconnect.model.CallHistory
import ch.heuscher.gentleconnect.model.CallRecord
import ch.heuscher.gentleconnect.model.Nudge
import ch.heuscher.gentleconnect.telecom.UnknownCallers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

/**
 * Repository for querying missed call logs from Android system CallLog.Calls.
 */
class CallLogRepository(private val context: Context) {

    fun hasReadCallLogPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Fetch the most recent missed call, if it is recent enough to still be worth telling her
     * about.
     *
     * The window is now the backstop rather than the mechanism. It began as the whole answer,
     * because there was nowhere to record that she had seen a nudge — and without it one missed
     * call from a fortnight ago sat at the top of her home screen as *the* thing to notice, every
     * day, with nothing she could press to make it go away. Answering it is what takes the card
     * away now; see [ch.heuscher.gentleconnect.model.MissedCallNotice]. What is left here is the
     * case nothing can clear: a call from a number that is in nobody's address book, which has no
     * "Call back" to press. A day is long enough for that to be news and short enough to stop
     * being it.
     */
    /**
     * The newest missed call in the telephone's log, however old it is.
     *
     * No age limit here any more. There used to be a day's worth written into this file, and once
     * how long the card stays became something a caregiver chooses, that made two places deciding
     * the same question — with this one silently winning, so that "keep it until she deals with
     * it" would still have quietly expired overnight. The rule lives in
     * [ch.heuscher.gentleconnect.model.MissedCallNotice] and nowhere else; this reports what the
     * log says.
     */
    suspend fun fetchLatestMissedCall(): Nudge.MissedCallNudge? = withContext(Dispatchers.IO) {
        if (!hasReadCallLogPermission()) return@withContext null

        try {
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE,
                CallLog.Calls.TYPE
            )

            val selection = "${CallLog.Calls.TYPE} = ?"
            val selectionArgs = arrayOf(CallLog.Calls.MISSED_TYPE.toString())
            val sortOrder = "${CallLog.Calls.DATE} DESC"

            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                    val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)

                    val number = if (numberIdx != -1) c.getString(numberIdx) ?: "" else ""
                    val cachedName = if (nameIdx != -1) c.getString(nameIdx) else null
                    val date = if (dateIdx != -1) c.getLong(dateIdx) else System.currentTimeMillis()

                    // CACHED_NAME is only filled in if the dialer happened to resolve the
                    // number when the call came in; it is routinely empty. Falling straight
                    // through to the number put "+41315550110" on her home screen, which
                    // tells her nothing — she knows Claire-Lise, not Claire-Lise's number.
                    // Empty, not "Unknown caller". The card composes the sentence, and
                    // "Missed call from Unknown caller" is a worse thing to read than
                    // "Missed call" — it makes a stranger out of a gap.
                    val displayName = cachedName?.ifEmpty { null }
                        ?: lookupContactName(number)
                        ?: ""
                    return@withContext Nudge.MissedCallNudge(
                        contactName = displayName,
                        timestamp = date,
                        number = number
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    /**
     * The newest missed calls in the telephone's log, newest first — for marking everybody who
     * rang on the calls tab, not only the one the card is about.
     *
     * Numbers and times only. The tab matches them against the address book it is already
     * drawing, so looking every caller up here as [fetchLatestMissedCall] does would be a query
     * per call for a name nobody reads.
     */
    suspend fun recentMissedCalls(limit: Int = MISSED_CALLS_READ): List<Nudge.MissedCallNudge> =
        withContext(Dispatchers.IO) {
            if (!hasReadCallLogPermission()) return@withContext emptyList()
            val found = mutableListOf<Nudge.MissedCallNudge>()
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
                    "${CallLog.Calls.TYPE} = ?",
                    arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
                    "${CallLog.Calls.DATE} DESC"
                )?.use { c ->
                    val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                    val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                    while (found.size < limit && c.moveToNext()) {
                        found += Nudge.MissedCallNudge(
                            contactName = if (nameIdx != -1) c.getString(nameIdx).orEmpty() else "",
                            timestamp = if (dateIdx != -1) c.getLong(dateIdx) else 0L,
                            number = if (numberIdx != -1) c.getString(numberIdx).orEmpty() else ""
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            found
        }

    /**
     * A tick every time the telephone writes to its record of calls.
     *
     * The record is still pulled and not watched — this says only *when* to pull, and holds no
     * cursor open on the provider between times. It exists because the moment the newest call is
     * written is the one moment nobody can arrange to be looking: the row appears when the call
     * **ends**, which is after she left this app to make it and, on a phone that is quick about
     * it, a moment after she is already back looking at the list. Without this, the call she had
     * just finished was missing from her own record of calls until she navigated away and came
     * back — which is not something she would think to do, and it made the list look like it had
     * stopped working.
     *
     * Empty, never an error, when the permission is missing: a screen that already says "nothing
     * here" in its own words needs no help from a crash.
     *
     * Conflated, because several rows written in the same breath are one reason to read again
     * and not four.
     */
    fun changes(): Flow<Unit> {
        if (!hasReadCallLogPermission()) return emptyFlow()
        return callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
            val registered = try {
                context.contentResolver.registerContentObserver(
                    CallLog.Calls.CONTENT_URI,
                    true,
                    observer
                )
                true
            } catch (e: SecurityException) {
                // Taken away between the question above and the asking here — revoked from
                // Android's own settings while she had this screen open. Nothing will tick; the
                // screen keeps whatever it read on the way in, which is the same as before.
                false
            }
            awaitClose {
                if (registered) context.contentResolver.unregisterContentObserver(observer)
            }
        }.conflate()
    }

    /**
     * The calls, newest first, as her screen wants them.
     *
     * Reads more of the log than it returns and lets [CallHistory.forHer] cut it down: the four
     * attempts to reach her son this afternoon are four rows in the telephone's record and one
     * event in her life, and which of those the screen shows is a decision that belongs in a pure
     * function rather than in a cursor loop.
     *
     * The address book is consulted *after* that cut, not during the read. Looking a number up
     * costs a query of its own, and doing it per raw row meant three hundred of them to draw
     * forty — most of them for rows that were about to be collapsed away, and the same number
     * looked up twelve times over for one run of unanswered calls.
     *
     * Empty, never an error, when the permission is missing. The screen that shows this says so
     * in its own words; a repository has no way to say anything to her.
     */
    suspend fun recentCalls(): List<CallRecord> = withContext(Dispatchers.IO) {
        if (!hasReadCallLogPermission()) return@withContext emptyList()

        val records = mutableListOf<CallRecord>()
        try {
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION
            )

            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                // LIMIT is not portable across the providers of every Android build, so the read
                // is bounded by the loop below instead. Enough rows to survive a long run of
                // repeats being collapsed away, and far short of the five hundred the telephone
                // keeps.
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val idIdx = c.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val durationIdx = c.getColumnIndex(CallLog.Calls.DURATION)

                // Read once, not once a row.
                val showBlocked = UnknownCallers.showsInCallLog(context)

                while (c.moveToNext() && records.size < READ_AT_MOST) {
                    val number = (if (numberIdx != -1) c.getString(numberIdx) else null).orEmpty()
                    // A call with no number at all — a withheld caller — cannot be rung back and
                    // cannot be saved, so there is nothing a row for it could offer her.
                    if (number.isBlank()) continue

                    val type = if (typeIdx != -1) c.getInt(typeIdx) else -1

                    // Rejected calls are always shown. Platform-blocked calls are kept off her screen
                    // unless a caregiver asked for those (showBlocked), so an unknown caller kept away
                    // does not appear right back in front of her. See
                    // [ch.heuscher.gentleconnect.telecom.UnknownCallers].
                    if (!UnknownCallers.shouldIncludeInCallLog(type, showBlocked)) {
                        continue
                    }

                    val date = if (dateIdx != -1) c.getLong(dateIdx) else System.currentTimeMillis()

                    records += CallRecord(
                        // The list draws itself by this, so two rows sharing one is not a wrong
                        // label — it is a crash. The log's own id whenever there is one, and the
                        // number and the minute it happened when there is not; a provider that
                        // hands back no id would otherwise give every row the same empty string.
                        id = (if (idIdx != -1) c.getString(idIdx) else null)?.ifBlank { null }
                            ?: "$number@$date",
                        // The log's own cached copy for now; her address book overrules it below.
                        name = (if (nameIdx != -1) c.getString(nameIdx) else null)
                            ?.ifBlank { null },
                        number = number,
                        direction = when (type) {
                            CallLog.Calls.MISSED_TYPE -> CallDirection.MISSED
                            // Not MISSED: amber on her list means somebody is waiting for her.
                            CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> CallDirection.BLOCKED
                            CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
                            else -> CallDirection.INCOMING
                        },
                        timestamp = date,
                        // Absent on some providers, and a column that is not there reads as -1
                        // rather than as an error. Zero is the honest answer for both a missed
                        // call and a log that does not keep this.
                        durationSeconds = if (durationIdx != -1) c.getInt(durationIdx) else 0
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext withNames(CallHistory.forHer(records))
    }

    /**
     * Outgoing calls placed since [sinceMillis], newest first.
     *
     * Used by photo recipient ranking to count how often she has rung each person.
     * Queries NUMBER and DATE only for calls with TYPE = OUTGOING_TYPE.
     * Returns an empty list if the READ_CALL_LOG permission is not granted.
     */
    suspend fun placedCallsSince(sinceMillis: Long): List<CallRecord> = withContext(Dispatchers.IO) {
        if (!hasReadCallLogPermission()) return@withContext emptyList()

        val records = mutableListOf<CallRecord>()
        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE
            )
            val selection = "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?"
            val selectionArgs = arrayOf(
                CallLog.Calls.OUTGOING_TYPE.toString(),
                sinceMillis.toString()
            )

            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)

                while (c.moveToNext()) {
                    val number = (if (numberIdx != -1) c.getString(numberIdx) else null).orEmpty()
                    if (number.isBlank()) continue

                    val date = if (dateIdx != -1) c.getLong(dateIdx) else 0L

                    records += CallRecord(
                        id = "$number@$date",
                        name = null,
                        number = number,
                        direction = CallDirection.OUTGOING,
                        timestamp = date,
                        durationSeconds = 0
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        records
    }

    /**
     * The rows that survived the cut, with whoever her address book says they are.
     *
     * Her address book wins over `CACHED_NAME`. The cached name is whatever the dialler resolved
     * at the moment the call came in and is never corrected afterwards — somebody renamed since
     * then goes on ringing through under the old name for as long as the log keeps the row, and
     * the whole point of holding a row to correct a name would be undone by it.
     *
     * Looked up once per number rather than once per row: two rows for one person are one query.
     */
    private fun withNames(records: List<CallRecord>): List<CallRecord> {
        val seen = mutableMapOf<String, KnownCaller?>()
        return records.map { record ->
            // containsKey rather than getOrPut: a number that is in nobody's address book caches
            // a null, and getOrPut treats a cached null as a cache miss — so the numbers most
            // worth remembering the answer for would be the ones looked up again every row.
            if (!seen.containsKey(record.number)) {
                seen[record.number] = lookupContact(record.number)
            }
            val person = seen[record.number]
            if (person == null) {
                record
            } else {
                record.copy(name = person.name, contactId = person.id)
            }
        }
    }

    /**
     * The number, looked up in her address book. Null when it is not there, or when the app
     * cannot read contacts.
     */
    private fun lookupContactName(phoneNumber: String): String? = lookupContact(phoneNumber)?.name

    /**
     * Who this number belongs to, and Android's handle for them.
     *
     * The handle is what makes a row on the call list able to open that person in the telephone's
     * own contact editor — see [ContactEditing]. Without it the only thing offerable is "save as
     * a new person", which for somebody already in the address book quietly creates a duplicate.
     *
     * Public because the ringing telephone asks the same question of the same address book — see
     * `telecom.GentlePhoneCallService`. Two lookups would be two chances to disagree about who is
     * calling, and the screen that says who is calling is not a screen to be wrong on.
     */
    fun lookupContact(phoneNumber: String): KnownCaller? {
        if (phoneNumber.isBlank()) return null
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.CONTACT_ID,
                    // Her photograph, for the call screen. Free here — the row is already being
                    // read — and the alternative is a second query at the moment the telephone
                    // is ringing, which is the worst moment to be doing extra work.
                    ContactsContract.PhoneLookup.PHOTO_URI
                ),
                null,
                null,
                null
            )?.use { c ->
                // Every row this returns, not only the first.
                //
                // One number can match several rows — the same woman synced from Google and
                // saved again on the SIM, or entered twice under slightly different names — and
                // they do not arrive with the fullest one first. Reading row 0 alone took her
                // name from an empty duplicate and her photograph from nowhere, so a call from
                // her own daughter arrived under the grey placeholder while the picture sat two
                // rows further down. Measured on the test phone 2026-08-17: four rows for one
                // number, PHOTO_URI null on the first and set on the third.
                //
                // The name is still whichever row has one first, because that is the name the
                // rest of the phone shows for this number and the ringing screen must agree with
                // the call list. Only the photograph is worth looking past that row for.
                var name: String? = null
                var id: String? = null
                var photoUri: String? = null
                while (c.moveToNext() && (name == null || photoUri == null)) {
                    if (name == null) {
                        val here = c.getString(0)?.ifBlank { null }
                        if (here != null) {
                            name = here
                            id = c.getString(1)?.ifBlank { null }
                        }
                    }
                    if (photoUri == null) {
                        photoUri = c.getString(2)?.ifBlank { null }
                    }
                }
                KnownCaller(
                    id = id,
                    name = name ?: return@use null,
                    photoUri = photoUri
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** A number that turned out to be somebody. */
    data class KnownCaller(val id: String?, val name: String, val photoUri: String? = null)

    companion object {
        /**
         * How far down the telephone's own log to read before giving up.
         *
         * Comfortably more than [CallHistory.SHOWN], because runs of repeats collapse away and a
         * run can be long — twelve attempts to reach somebody who is not answering is one row.
         * Bounded all the same: each row costs an address-book lookup, and the log has no end
         * worth reaching.
         */
        private const val READ_AT_MOST = 300

        /**
         * Missed calls read for the marks on the calls tab. The longest window a caregiver can
         * choose is a day, or "until she deals with it"; fifty missed calls is past either.
         */
        private const val MISSED_CALLS_READ = 50
    }
}
