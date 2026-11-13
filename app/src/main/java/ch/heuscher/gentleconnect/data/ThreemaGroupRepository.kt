package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.data.db.ThreemaGroupDao
import ch.heuscher.gentleconnect.data.db.ThreemaGroupEntity
import ch.heuscher.gentleconnect.data.db.ThreemaGroupMemberEntity
import ch.heuscher.gentleconnect.model.GroupIdentity

/**
 * What we know about the groups she is in, kept up to date from the control messages.
 *
 * Everything that decides anything lives in [GroupControlParser] and [GroupDescription], which are
 * pure and therefore tested. This applies the result.
 */
class ThreemaGroupRepository(private val dao: ThreemaGroupDao) {

    val groups = dao.getAllGroupsFlow()
    val members = dao.getAllMembersFlow()

    suspend fun record(control: GroupControlMessage, receivedAt: Long) {
        when (control) {
            is GroupControlMessage.Setup -> applySetup(control, receivedAt)
            is GroupControlMessage.Renamed -> applyRename(control)
            is GroupControlMessage.Left -> applyLeave(control)
        }
    }

    /**
     * The creator's member list replaces whatever we had, rather than being merged into it.
     *
     * Merging would look tidier and would be wrong: somebody removed from the group would stay in
     * it here for ever, and a reply fanned out to that stale list would go to a person the family
     * had deliberately taken out of the conversation.
     */
    private suspend fun applySetup(setup: GroupControlMessage.Setup, receivedAt: Long) {
        ensureRow(setup.group)
        dao.updateLastSetupAt(setup.group.creatorIdentity, setup.group.groupId, receivedAt)
        dao.clearMembers(setup.group.creatorIdentity, setup.group.groupId)
        if (setup.members.isEmpty()) return
        dao.insertMembers(
            setup.members.map { identity ->
                ThreemaGroupMemberEntity(
                    creatorIdentity = setup.group.creatorIdentity,
                    groupId = setup.group.groupId,
                    identity = identity
                )
            }
        )
    }

    private suspend fun applyRename(renamed: GroupControlMessage.Renamed) {
        ensureRow(renamed.group)
        dao.updateName(renamed.group.creatorIdentity, renamed.group.groupId, renamed.name)
    }

    private suspend fun applyLeave(left: GroupControlMessage.Left) {
        dao.removeMember(left.group.creatorIdentity, left.group.groupId, left.identity)
    }

    /**
     * Makes sure the group exists before one of the narrow updates writes to it.
     *
     * A rename arriving before the setup, or the other way round, is entirely ordinary, so this
     * must not touch an existing row: the insert is ignored when one is already there, and the
     * caller then updates only the single column its own message actually said something about.
     */
    private suspend fun ensureRow(group: GroupIdentity) {
        dao.insertGroupIfNew(
            ThreemaGroupEntity(
                creatorIdentity = group.creatorIdentity,
                groupId = group.groupId
            )
        )
    }
}
