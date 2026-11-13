package ch.heuscher.gentleconnect.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreemaGroupDao {

    @Query("SELECT * FROM threema_groups")
    fun getAllGroupsFlow(): Flow<List<ThreemaGroupEntity>>

    @Query("SELECT * FROM threema_group_members")
    fun getAllMembersFlow(): Flow<List<ThreemaGroupMemberEntity>>

    /**
     * IGNORE, not REPLACE. This exists only to make sure a row is there before one of the narrow
     * updates below writes to it, and a REPLACE would blank the column the current message says
     * nothing about — a rename would erase the membership timestamp, which is the fact Stage 3
     * gates replying on.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroupIfNew(group: ThreemaGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<ThreemaGroupMemberEntity>)

    /**
     * Renaming leaves the membership alone, and recording a setup leaves the name alone.
     *
     * Hence two narrow updates rather than one insert-or-replace of the whole row: the two facts
     * arrive in separate messages and in either order, and a rename that arrived first must not be
     * erased by the setup that follows it.
     */
    @Query("UPDATE threema_groups SET name = :name WHERE creatorIdentity = :creator AND groupId = :groupId")
    suspend fun updateName(creator: String, groupId: String, name: String?): Int

    /** The group's picture, set without touching the name or the membership it arrived beside. */
    @Query("UPDATE threema_groups SET photoPath = :path WHERE creatorIdentity = :creator AND groupId = :groupId")
    suspend fun updatePhoto(creator: String, groupId: String, path: String?): Int

    @Query("UPDATE threema_groups SET lastSetupAt = :at WHERE creatorIdentity = :creator AND groupId = :groupId")
    suspend fun updateLastSetupAt(creator: String, groupId: String, at: Long): Int

    /**
     * How old the membership we hold for this group is, or null when the group is new to us.
     *
     * Asked by the backup import, which carries a member list of its own with a date on it: the
     * newer of the two knowledges wins, so that restoring an old archive cannot put somebody the
     * family has since removed back into the fan-out of her next reply.
     */
    @Query("SELECT lastSetupAt FROM threema_groups WHERE creatorIdentity = :creator AND groupId = :groupId")
    suspend fun lastSetupAt(creator: String, groupId: String): Long?

    @Query("DELETE FROM threema_group_members WHERE creatorIdentity = :creator AND groupId = :groupId")
    suspend fun clearMembers(creator: String, groupId: String)

    @Query(
        "DELETE FROM threema_group_members " +
            "WHERE creatorIdentity = :creator AND groupId = :groupId AND identity = :identity"
    )
    suspend fun removeMember(creator: String, groupId: String, identity: String)
}
