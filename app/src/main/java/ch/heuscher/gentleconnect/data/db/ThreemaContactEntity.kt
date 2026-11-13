package ch.heuscher.gentleconnect.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing an imported Threema contact.
 */
@Entity(tableName = "threema_contacts")
data class ThreemaContactEntity(
    @PrimaryKey val identity: String,
    val firstName: String?,
    val lastName: String?,
    val nickname: String?,
    val photoPath: String?
) {
    val displayName: String
        get() {
            val full = listOfNotNull(firstName?.takeIf { it.isNotBlank() }, lastName?.takeIf { it.isNotBlank() })
                .joinToString(" ")
            if (full.isNotBlank()) return full
            if (!nickname.isNullOrBlank()) return nickname
            return identity
        }
}
