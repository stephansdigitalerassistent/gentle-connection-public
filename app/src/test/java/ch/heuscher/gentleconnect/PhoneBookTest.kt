package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.PhoneBook
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneBookTest {

    private fun contact(id: String, name: String, number: String = "") =
        Contact(id = id, name = name, phoneNumber = number)

    @Test
    fun `favourites come first, in the order they were chosen`() {
        val all = listOf(
            contact("s1", "Anna", "+41791110001"),
            contact("s2", "Bernhard", "+41791110002"),
            contact("s3", "Zora", "+41791110003")
        )
        val favourites = listOf(
            contact("c1", "Zora", "+41791110003"),
            contact("c2", "Anna", "+41791110001")
        )

        val shown = PhoneBook.forHer(all, favourites, "")

        assertEquals(listOf("Zora", "Anna", "Bernhard"), shown.all.map { it.name })
    }

    /**
     * The favourites carry the caregiver's contact ids, not the address book's, so anything
     * keying on the id prints every favourite twice.
     */
    @Test
    fun `a favourite is not also listed among everybody else`() {
        val all = listOf(contact("s1", "Anna", "+41791110001"))
        val favourites = listOf(contact("caregiver-id-not-s1", "Anna", "+41791110001"))

        val shown = PhoneBook.forHer(all, favourites, "")

        assertEquals(1, shown.all.size)
    }

    /** The two sides write the same telephone differently; it is still one telephone. */
    @Test
    fun `the same number written nationally and internationally is one person`() {
        val all = listOf(contact("s1", "Anna", "+41 79 111 00 01"))
        val favourites = listOf(contact("c1", "Anna", "079 111 00 01"))

        assertEquals(1, PhoneBook.forHer(all, favourites, "").all.size)
    }

    /**
     * The mistake this rule exists to avoid. Deduplicating by number alone once deleted fifty
     * people from her address book — colleagues who share an office switchboard.
     */
    @Test
    fun `two people on one office number both survive`() {
        val all = listOf(
            contact("s1", "Gregor Albrecht", "+41315550112"),
            contact("s2", "Sandro Bieri", "+41315550112")
        )
        val favourites = listOf(contact("c1", "Gregor Albrecht", "+41315550112"))

        val shown = PhoneBook.forHer(all, favourites, "")

        assertEquals(listOf("Gregor Albrecht", "Sandro Bieri"), shown.all.map { it.name })
    }

    @Test
    fun `searching still puts a matching favourite above a matching stranger`() {
        val all = listOf(
            contact("s1", "Anna Bauer", "+41791110001"),
            contact("s2", "Astrid Zimmermann", "+41791110009")
        )
        val favourites = listOf(contact("c1", "Anna Bauer", "+41791110001"))

        val shown = PhoneBook.forHer(all, favourites, "ann")

        assertEquals(listOf("Anna Bauer", "Astrid Zimmermann"), shown.all.map { it.name })
    }

    @Test
    fun `a favourite who does not match what she typed is not hoisted`() {
        val all = listOf(contact("s1", "Bernhard", "+41791110002"))
        val favourites = listOf(contact("c1", "Anna", "+41791110001"))

        assertEquals(listOf("Bernhard"), PhoneBook.forHer(all, favourites, "bern").all.map { it.name })
    }

    /**
     * [ContactSearch] drops entries filed under a bare telephone number, which is right for a
     * list of 1254 and wrong for a face somebody deliberately chose for her home screen.
     */
    @Test
    fun `a favourite named as a number is still shown`() {
        val all = listOf(
            contact("s1", "+41 78 555 01 11", "+41785550111"),
            contact("s2", "Anna", "+41791110001")
        )
        val favourites = listOf(contact("c1", "+41 78 555 01 11", "+41785550111"))

        val shown = PhoneBook.forHer(all, favourites, "")

        assertEquals(listOf("+41 78 555 01 11", "Anna"), shown.all.map { it.name })
    }

    @Test
    fun `no favourites at all leaves the address book as it was`() {
        val all = listOf(
            contact("s1", "Anna", "+41791110001"),
            contact("s2", "Bernhard", "+41791110002")
        )

        assertEquals(listOf("Anna", "Bernhard"), PhoneBook.forHer(all, emptyList(), "").all.map { it.name })
    }

    /**
     * The screen draws a line between the two groups and marks the people above it, so the split
     * has to be reported rather than left for the screen to reconstruct per row.
     */
    @Test
    fun `the two groups are reported separately`() {
        val all = listOf(
            contact("s1", "Anna", "+41791110001"),
            contact("s2", "Bernhard", "+41791110002")
        )
        val favourites = listOf(contact("c1", "Anna", "+41791110001"))

        val shown = PhoneBook.forHer(all, favourites, "")

        assertEquals(listOf("Anna"), shown.favourites.map { it.name })
        assertEquals(listOf("Bernhard"), shown.others.map { it.name })
    }

    /** No line to draw when one side is empty; the screen asks this before drawing one. */
    @Test
    fun `no favourites means nothing above the line`() {
        val all = listOf(contact("s1", "Anna", "+41791110001"))

        val shown = PhoneBook.forHer(all, emptyList(), "")

        assertEquals(emptyList<String>(), shown.favourites.map { it.name })
        assertEquals(listOf("Anna"), shown.others.map { it.name })
    }
}
