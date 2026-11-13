package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.data.db.CaregiverEventDao
import ch.heuscher.gentleconnect.model.CaregiverEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Keeps the caregiver's error log.
 *
 * Everything that decides anything is in [CaregiverLog] and [CaregiverEventMapper], which are pure
 * and therefore tested. This applies the result.
 */
class CaregiverLogRepository(private val dao: CaregiverEventDao) {

    val events: Flow<List<CaregiverEvent>> =
        dao.getAllFlow().map { rows -> rows.mapNotNull(CaregiverEventMapper::fromRow) }

    /**
     * Writes one fault down, or notes that the last one happened again.
     *
     * The trim runs on every write rather than on a schedule, because there is no scheduler here
     * and a log nobody prunes is a table that grows for years on a phone nobody administers. It
     * runs twice, once per budget — a caregiver deleting messages must not be able to evict the
     * faults somebody will come looking for. See [CaregiverLog.MAX_ACTION_ENTRIES].
     */
    suspend fun record(event: CaregiverEvent) {
        val newestRow = dao.newest()
        val newest = newestRow?.let(CaregiverEventMapper::fromRow)
        if (newestRow != null && CaregiverLog.collapses(newest, event)) {
            dao.bump(newestRow.id, event.at, event.count, event.total)
            return
        }
        dao.insert(CaregiverEventMapper.toRow(event))
        // Both budgets on every write, not just the one this row belongs to: rows written by an
        // older build were trimmed against a single shared budget, and running both here is what
        // brings such a table back to the two the app now keeps.
        dao.trimOtherKindsTo(CaregiverLog.CAREGIVER_ACTION_KINDS, CaregiverLog.MAX_ENTRIES)
        dao.trimKindsTo(CaregiverLog.CAREGIVER_ACTION_KINDS, CaregiverLog.MAX_ACTION_ENTRIES)
    }
}
