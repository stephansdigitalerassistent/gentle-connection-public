package ch.heuscher.gentleconnect.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One line of the caregiver's error log, on disk.
 *
 * On disk rather than in memory because of who reads it and when. A launcher process is killed and
 * restarted constantly, so an in-memory log is empty almost every time somebody finally looks at
 * it — and the faults worth reporting are exactly the ones that happened while nobody was there.
 *
 * [kind] is stored as the enum name rather than its ordinal: reordering the enum would otherwise
 * silently relabel every row already written, so a failed send from last week would start reading
 * as a contact-sync failure.
 */
@Entity(tableName = "caregiver_events")
data class CaregiverEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val at: Long,
    val subject: String?,
    val count: Int,
    val total: Int,
    val occurrences: Int
)
