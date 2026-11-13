package ch.heuscher.gentleconnect.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreemaContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContacts(contacts: List<ThreemaContactEntity>)

    @Query("SELECT * FROM threema_contacts")
    fun getAllContactsFlow(): Flow<List<ThreemaContactEntity>>

    @Query("SELECT * FROM threema_contacts")
    suspend fun getAllContacts(): List<ThreemaContactEntity>

    @Query("SELECT * FROM threema_contacts WHERE identity = :identity LIMIT 1")
    suspend fun getContactByIdentity(identity: String): ThreemaContactEntity?
}
