package ch.heuscher.gentleconnect.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CaregiverEventDao {

    /**
     * Newest first, which is the order it is read in and the order the trim depends on.
     *
     * Tie-broken by id, because several faults can land in the same millisecond — a fan-out that
     * fails does so for every member at once — and without the tie-break the newest row is
     * whichever one SQLite happened to return, so a collapse could bump the wrong line.
     */
    @Query("SELECT * FROM caregiver_events ORDER BY at DESC, id DESC")
    fun getAllFlow(): Flow<List<CaregiverEventEntity>>

    @Query("SELECT * FROM caregiver_events WHERE at >= :since ORDER BY at ASC")
    suspend fun since(since: Long): List<CaregiverEventEntity>

    @Query("SELECT * FROM caregiver_events ORDER BY at DESC, id DESC LIMIT 1")
    suspend fun newest(): CaregiverEventEntity?

    @Insert
    suspend fun insert(event: CaregiverEventEntity)

    @Query("UPDATE caregiver_events SET occurrences = occurrences + 1, at = :at, count = :count, total = :total WHERE id = :id")
    suspend fun bump(id: Long, at: Long, count: Int, total: Int)

    /**
     * Keeps the newest [keep] rows whose kind is in [kinds], and deletes the rest of that kind.
     *
     * Two budgets rather than one — see [ch.heuscher.gentleconnect.data.CaregiverLog.MAX_ACTION_ENTRIES].
     * The inner select is restricted to the same kinds as the delete, so that trimming one group
     * can never evict a row belonging to the other.
     */
    @Query(
        "DELETE FROM caregiver_events WHERE kind IN (:kinds) AND id NOT IN (" +
            "SELECT id FROM caregiver_events WHERE kind IN (:kinds) ORDER BY at DESC, id DESC LIMIT :keep)"
    )
    suspend fun trimKindsTo(kinds: List<String>, keep: Int)

    /**
     * The same, for everything that is *not* one of [kinds] — the faults.
     *
     * Expressed as "not in" rather than as its own list so that a kind added later counts as a
     * fault until somebody says otherwise, which is the safe default: a new kind that nobody has
     * classified yet gets the budget that is protected, not the one that is spent freely.
     */
    @Query(
        "DELETE FROM caregiver_events WHERE kind NOT IN (:kinds) AND id NOT IN (" +
            "SELECT id FROM caregiver_events WHERE kind NOT IN (:kinds) ORDER BY at DESC, id DESC LIMIT :keep)"
    )
    suspend fun trimOtherKindsTo(kinds: List<String>, keep: Int)
}
