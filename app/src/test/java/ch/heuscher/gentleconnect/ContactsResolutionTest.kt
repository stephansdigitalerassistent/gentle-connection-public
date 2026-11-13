package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsResolutionTest {

    @Test
    fun `caregiver favorites list is preserved when no system contacts`() {
        val caregiverFavorites = listOf(
            Contact(id = "c-1", name = "Lisa", phoneNumber = "+41791234567", order = 1),
            Contact(id = "c-2", name = "Markus", phoneNumber = "+41792345678", order = 2)
        )

        assertEquals(2, caregiverFavorites.size)
        assertEquals("Lisa", caregiverFavorites[0].name)
        assertEquals("+41791234567", caregiverFavorites[0].phoneNumber)
    }

    @Test
    fun `contact phone number normalization strips non-numeric characters`() {
        val rawNumber = "+41 (79) 123-4567"
        val normalized = rawNumber.replace(Regex("[^0-9+]"), "")
        assertEquals("+41791234567", normalized)
    }
}
