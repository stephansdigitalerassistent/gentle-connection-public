package ch.heuscher.gentleconnect.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreemaMatchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMatches(matches: List<ThreemaMatchEntity>)

    @Query("SELECT * FROM threema_matches")
    fun getAllMatchesFlow(): Flow<List<ThreemaMatchEntity>>

    /**
     * Forgets a number that the directory no longer recognises.
     *
     * Kept deliberately narrow — there is no "clear everything" here. A failed or empty lookup
     * must not wipe the table, or a single bad network moment would make everyone she knows
     * unreachable at once.
     */
    @Query("DELETE FROM threema_matches WHERE phoneNumber = :phoneNumber")
    suspend fun forget(phoneNumber: String)

    @Query("SELECT * FROM threema_matches WHERE identity = :identity LIMIT 1")
    suspend fun getMatchByIdentity(identity: String): ThreemaMatchEntity?

    @Query("SELECT * FROM threema_matches WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getMatchByPhoneNumber(phoneNumber: String): ThreemaMatchEntity?
}
