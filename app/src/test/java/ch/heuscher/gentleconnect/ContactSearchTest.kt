package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.ContactSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding one person among 1254.
 *
 * The address book on the phone is complete, alphabetical and unusable: no search, no index, and
 * the first row is a telephone number with nobody's name on it.
 */
class ContactSearchTest {

    private fun contact(name: String) = Contact(id = name, name = name, phoneNumber = "+41790000000")

    private val book = listOf(
        contact("+41 78 555 01 11"),
        contact("Anton Berger"),
        contact("Claire-Lise"),
        contact("Eliane \"Eli\" Müller"),
        contact("Sabine Hartmann-Berger"),
        contact("Simon Hartmann")
    )

    @Test
    fun `an empty search is the whole address book, minus the nameless`() {
        assertEquals(
            listOf("Anton Berger", "Claire-Lise", "Eliane \"Eli\" Müller",
                "Sabine Hartmann-Berger", "Simon Hartmann"),
            ContactSearch.forHer(book, "").map { it.name }
        )
    }

    @Test
    fun `a number filed under itself is not offered at all`() {
        // The first row of her real address book. She cannot decide whether to ring it, so it is
        // not a choice — it is an obstacle standing in front of the letter A.
        assertTrue(ContactSearch.looksLikeBareNumber("+41 78 555 01 11"))
        assertTrue(ContactSearch.looksLikeBareNumber("079 123 45 67"))
        assertTrue(ContactSearch.looksLikeBareNumber("(044) 123-4567"))
        assertFalse(ContactSearch.looksLikeBareNumber("Claire-Lise"))
        assertFalse(ContactSearch.looksLikeBareNumber("Haus 4"))
    }

    @Test
    fun `she can find someone by their family name, not only the first`() {
        assertEquals(
            listOf("Sabine Hartmann-Berger", "Simon Hartmann"),
            ContactSearch.forHer(book, "hartmann").map { it.name }
        )
    }

    @Test
    fun `a nickname is searchable even though it is not shown`() {
        // The other half of hiding it — see PersonNameTest. "Eli" is what that person is called.
        assertEquals(
            listOf("Eliane \"Eli\" Müller"),
            ContactSearch.forHer(book, "eli").map { it.name }
        )
    }

    @Test
    fun `an umlaut typed as a plain letter still finds the person`() {
        assertTrue(ContactSearch.matches("Eliane Müller", "muller"))
        assertTrue(ContactSearch.matches("Eliane Müller", "müller"))
        assertTrue(ContactSearch.matches("Céline", "celine"))
    }

    @Test
    fun `case and stray spaces do not matter`() {
        assertTrue(ContactSearch.matches("Claire-Lise", "  CLAIRE  "))
    }

    @Test
    fun `a name nobody has gives nothing back, rather than everything`() {
        assertTrue(ContactSearch.forHer(book, "zzzz").isEmpty())
    }
}
