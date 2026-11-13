package ch.heuscher.gentleconnect.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A phone number in her address book that the Threema directory recognised, and the identity it
 * belongs to.
 *
 * Stored rather than held in memory so that the people she can write to do not disappear every
 * time the app restarts. Re-running the lookup on each launch would work, but it would mean her
 * home screen showing a different set of reachable faces depending on whether the network happened
 * to be up when she opened it.
 */
@Entity(tableName = "threema_matches")
data class ThreemaMatchEntity(
    /** Normalised to E.164 digits without the leading `+`, exactly as it was submitted. */
    @PrimaryKey val phoneNumber: String,
    val identity: String,
    val matchedAt: Long
)
