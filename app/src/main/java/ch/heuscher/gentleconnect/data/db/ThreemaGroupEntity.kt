package ch.heuscher.gentleconnect.data.db

import androidx.room.Entity

/**
 * A Threema group we have heard from.
 *
 * Identified by **(creator identity, group id)** — not by name, which changes, and not by
 * membership, which changes.
 *
 * A group appears here only once somebody writes in it or sets it up. Groups she belongs to but
 * which are quiet do not occupy her screen and nothing lists them.
 */
@Entity(
    tableName = "threema_groups",
    primaryKeys = ["creatorIdentity", "groupId"]
)
data class ThreemaGroupEntity(
    val creatorIdentity: String,
    /** Lower-case hex, in wire byte order. */
    val groupId: String,
    /** What the group calls itself, if it has told us. Null is ordinary, not an error. */
    val name: String? = null,
    /**
     * When the creator last sent a member list.
     *
     * Zero means never, and that is the difference between a group we know the membership of and
     * one we merely have messages from — which is what Stage 3 will gate replying on, because a
     * fan-out to an incomplete membership reaches some people and looks exactly like success.
     */
    val lastSetupAt: Long = 0L,
    /**
     * The group's own picture, as a path on this phone, or null for the ordinary case of a group
     * that has never told us what it looks like.
     *
     * A group is the one conversation whose portrait is not a person's: see MessagePortrait.
     */
    val photoPath: String? = null
)

/**
 * One member of one group.
 *
 * Replaced wholesale whenever a setup message arrives, never merged: the creator's member list is
 * the whole truth about who is in the group at that moment, and merging would keep people in a
 * group after they had been removed from it.
 */
@Entity(
    tableName = "threema_group_members",
    primaryKeys = ["creatorIdentity", "groupId", "identity"]
)
data class ThreemaGroupMemberEntity(
    val creatorIdentity: String,
    val groupId: String,
    val identity: String
)
