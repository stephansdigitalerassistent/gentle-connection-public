package ch.heuscher.gentleconnect.util

import android.content.Context
import android.util.Log
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.data.db.CaregiverEventEntity
import ch.heuscher.gentleconnect.data.db.MailCountRow
import ch.heuscher.gentleconnect.data.AppGraph
import ch.heuscher.gentleconnect.data.db.MessageCountRow
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.StatusBroadcast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps `caregiver-stats.json` in the app's external files directory, for the twice-daily
 * caregiver report on the assistant server to `adb pull`.
 *
 * That report used to read the database through `run-as`, which only works on a debug build; on
 * the release build Play installs it fell back to logcat, which holds under an hour, and reported
 * no messages for a day in which she had ten. This file is readable over adb on any build.
 *
 * Counts, not contents: for each message only when, which way, which messenger and whether it
 * was in a group or a status; for each mail only when and whether the sender is known. No text
 * and no people's names.
 *
 * A group message also says *which* group, as a tag (`g1`, `g2`, …) numbered in order of first
 * appearance in this file, with the group's name beside it in `groups` when one is known. Without
 * that the report could only say "a group", and a reply she sent to three people read as a reply
 * the whole family should have received. The group's own id never leaves the phone, and a tag
 * means nothing outside the file it is in.
 *
 * Of the caregiver events only the kind and
 * time, and the subject only for the two kinds whose subject is not a person
 * ([SUBJECT_KINDS]). Other apps cannot read the directory from Android 11 on.
 */
object CaregiverStatsExport {

    private const val TAG = "CaregiverStats"
    const val FILE_NAME = "caregiver-stats.json"

    /** Rewritten this often. The report runs twice a day; five minutes stale is nothing to it. */
    private const val INTERVAL_MS = 5 * 60 * 1000L

    /** How far back the file reaches: a week and a day, more than any report period. */
    private const val WINDOW_MS = 8 * 24 * 60 * 60 * 1000L

    private val SUBJECT_KINDS = setOf("RESTLESSNESS_DETECTED", "SUPPORT_OPENED_NOT_CONFIRMED")

    private var job: Job? = null

    /** From [ch.heuscher.gentleconnect.service.MessengerService]; a second call while running does nothing. */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                runCatching { write(app) }.onFailure { Log.w(TAG, "could not write $FILE_NAME", it) }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun write(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: return
        val now = System.currentTimeMillis()
        val since = now - WINDOW_MS
        val db = AppDatabase.getInstance(context)
        val json = toJson(
            now = now,
            since = since,
            messages = db.messageDao().countsSince(since),
            groupNames = AppGraph.catalog(context).groupDescriptions.value,
            events = db.caregiverEventDao().since(since),
            mails = db.mailDao().countsSince(since)
        )
        // Written aside and renamed, so a pull in the middle of a write never reads half a file.
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(File(dir, FILE_NAME))) {
            tmp.delete()
            Log.w(TAG, "could not replace $FILE_NAME")
        }
    }

    internal fun toJson(
        now: Long,
        since: Long,
        messages: List<MessageCountRow>,
        events: List<CaregiverEventEntity>,
        mails: List<MailCountRow> = emptyList(),
        groupNames: Map<GroupIdentity, String> = emptyMap()
    ): JSONObject {
        val messageArray = JSONArray()
        // Keyed by creator and id together, as GroupIdentity is: two groups may share an id.
        val tagByGroup = LinkedHashMap<Pair<String, String>, String>()
        val groups = JSONObject()
        for (m in messages) {
            val status = StatusBroadcast.isStatusJid(m.groupId)
            val groupId = m.groupId?.takeIf { !status }
            val o = JSONObject()
                .put("t", m.timestamp)
                .put("dir", m.direction)
                .put("transport", m.transport)
                .put("group", groupId != null)
                .put("status", status)
            if (groupId != null) {
                val key = m.groupCreator?.trim()?.uppercase().orEmpty() to groupId.trim().lowercase()
                val tag = tagByGroup.getOrPut(key) {
                    val next = "g${tagByGroup.size + 1}"
                    val entry = JSONObject().put("transport", m.transport)
                    GroupIdentity.of(m.groupCreator, groupId)
                        ?.let { groupNames[it] }
                        ?.takeIf { it.isNotBlank() }
                        ?.let { entry.put("name", it) }
                    groups.put(next, entry)
                    next
                }
                o.put("g", tag)
            }
            messageArray.put(o)
        }
        val eventArray = JSONArray()
        for (e in events) {
            val o = JSONObject()
                .put("t", e.at)
                .put("kind", e.kind)
                .put("occurrences", e.occurrences)
            if (e.kind in SUBJECT_KINDS && e.subject != null) o.put("subject", e.subject)
            eventArray.put(o)
        }
        val mailArray = JSONArray()
        for (m in mails) {
            mailArray.put(JSONObject().put("t", m.timestamp).put("known", m.fromKnownSender))
        }
        return JSONObject()
            .put("version", 1)
            .put("writtenAt", now)
            .put("since", since)
            .put("messages", messageArray)
            .put("groups", groups)
            .put("caregiverEvents", eventArray)
            .put("mails", mailArray)
    }
}
