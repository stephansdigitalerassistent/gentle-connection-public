package ch.heuscher.gentleconnect.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing an imported historical message.
 * Note: Every imported message has isRead = true by definition to prevent home screen clutter.
 */
@Entity(tableName = "imported_messages")
data class ImportedMessageEntity(
    @PrimaryKey val id: String,
    val partnerIdentity: String,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val isRead: Boolean = true
)
