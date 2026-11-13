package ch.heuscher.gentleconnect.model

/**
 * Which Threema group a message belongs to.
 *
 * A group is identified by the identity that created it together with the group's own id — not by
 * its name, which changes, and not by its membership, which changes too. Two different groups can
 * carry the same name, and the same group can be renamed twice in a week; only this pair is
 * stable.
 *
 * The bridge already sends both fields for every group message. Until this type existed, nothing
 * read them, and a message sent to eight people arrived looking exactly like a private one.
 */
data class GroupIdentity(
    /** The Threema ID of whoever created the group, upper case. */
    val creatorIdentity: String,
    /** The group id as lower-case hex, wire byte order, exactly as the bridge sends it. */
    val groupId: String
) {
    companion object {
        /**
         * @return the group both fields describe, or null if either is missing.
         *
         * Both or neither: a creator without a group id, or a group id without a creator, names no
         * group that can be answered or joined to anything. Treating a half-identified message as
         * a private one is how a group reply ends up with a single person, so a partial pair is
         * rejected rather than repaired.
         */
        fun of(creatorIdentity: String?, groupId: String?): GroupIdentity? {
            val creator = creatorIdentity?.trim()?.uppercase().orEmpty()
            val id = groupId?.trim()?.lowercase().orEmpty()
            if (creator.isEmpty() || id.isEmpty()) return null
            return GroupIdentity(creator, id)
        }
    }
}
