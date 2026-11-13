package ch.heuscher.gentleconnect.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportedMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ImportedMessageEntity>)

    @Query("SELECT * FROM imported_messages WHERE partnerIdentity = :partnerIdentity ORDER BY timestamp ASC")
    fun getMessagesForPartnerFlow(partnerIdentity: String): Flow<List<ImportedMessageEntity>>

    @Query("SELECT * FROM imported_messages ORDER BY timestamp DESC")
    fun getAllImportedMessagesFlow(): Flow<List<ImportedMessageEntity>>

    @Query("SELECT * FROM imported_messages WHERE partnerIdentity = :partnerIdentity ORDER BY timestamp ASC")
    suspend fun getMessagesForPartner(partnerIdentity: String): List<ImportedMessageEntity>

    /**
     * Every imported message, for reconciling an import against what is already here.
     *
     * Deliberately its own query rather than [getMessagesForPartner] with the filter taken out:
     * that filter is what makes a conversation one conversation.
     */
    @Query("SELECT * FROM imported_messages ORDER BY timestamp ASC")
    suspend fun getAllMessages(): List<ImportedMessageEntity>

    /**
     * Throw away rows an earlier import wrote that were never messages.
     *
     * A backup's archive holds `nonces.csv`, `ballot.csv`, `reaction_counts.csv` and more, and an
     * earlier version of the importer read every `.csv` in it: one real import produced 7,301
     * "messages" of which 4,564 were nonces, each filed under a partner named after the file it
     * came from. Nothing ever displayed them, but the exporter would have written them into her
     * next backup as though they were things people had said.
     */
    @Query("DELETE FROM imported_messages WHERE partnerIdentity IN (:partnerIdentities)")
    suspend fun deleteByPartners(partnerIdentities: List<String>): Int

    /**
     * Throw away rows an earlier import filed under the name of the file they came out of.
     *
     * A group's messages live in `group_message_1919635298.csv`, and before the importer could
     * key a group they were filed under `group_message_1919635298` as though that were a person.
     * Those rows cannot be reached by re-importing — the archive now files the same messages under
     * the group, so the old copies are never met again and never overwritten.
     */
    @Query(
        "DELETE FROM imported_messages " +
            "WHERE partnerIdentity LIKE 'group_message_%' OR partnerIdentity LIKE 'message_%'"
    )
    suspend fun deleteFileNamedPartners(): Int

    /**
     * Remove exactly the messages an archive contains, so the import can write them again.
     *
     * See ThreemaBackupParser.parseMessageIds for why an import is allowed to do this and why it
     * is allowed to do no more than this.
     */
    @Query("DELETE FROM imported_messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int
}
