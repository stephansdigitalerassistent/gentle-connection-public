package ch.heuscher.gentleconnect.model

/**
 * Domain model representing a contact person.
 * Used primarily for favorite faces on the home screen.
 */
data class Contact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val photoUri: String? = null,
    val threemaId: String? = null,
    val order: Int = 0,
    val isFavorite: Boolean = true
)
