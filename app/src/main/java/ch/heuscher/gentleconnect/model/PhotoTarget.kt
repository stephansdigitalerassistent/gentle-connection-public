package ch.heuscher.gentleconnect.model

/**
 * A candidate recipient for photo sending.
 *
 * One entry per person or group she can send photos to:
 * - For people: [picture] is null and [memberCount] is 0.
 * - For groups: [picture] and [memberCount] reflect the group's avatar and membership.
 */
data class PhotoTarget(
    val recipient: Recipient,
    val picture: GroupPicture?,
    val memberCount: Int
)
