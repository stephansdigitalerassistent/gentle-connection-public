package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.ContactDeduplicator
import ch.heuscher.gentleconnect.data.ContactDeduplicator.Row
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.PhoneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every case here was read out of her actual address book.
 */
class ContactDeduplicatorTest {

    @Test
    fun `one person with three numbers is one person`() {
        val people = ContactDeduplicator.dedupe(
            listOf(
                Row("1776", "Winkler Rosa", "078 555 01 11"),
                Row("1776", "Winkler Rosa", "031 555 01 10"),
                Row("1776", "Winkler Rosa", "+41 31 555 01 09")
            )
        )

        assertEquals(1, people.size)
        // Nothing says what kind of numbers these are, so the address book's order stands.
        assertEquals("078 555 01 11", people.single().phoneNumber)
    }

    @Test
    fun `nono's face rings her mobile, not the landline sorted before it`() {
        // Rows matching an Android contacts provider response.
        val people = ContactDeduplicator.dedupe(
            listOf(
                Row("2161", "Nora \"Nono\" Weber-Keller", "+41 31 555 01 07", kind = PhoneKind.LANDLINE),
                Row("2161", "Nora \"Nono\" Weber-Keller", "+41 79 555 01 08", kind = PhoneKind.MOBILE, isPrimary = true),
                Row("2161", "Nora \"Nono\" Weber-Keller", "+41315550107", kind = PhoneKind.LANDLINE),
                Row("2161", "Nora \"Nono\" Weber-Keller", "+41795550108", kind = PhoneKind.MOBILE)
            )
        )

        assertEquals("+41 79 555 01 08", people.single().phoneNumber)
    }

    @Test
    fun `a mobile beats a landline even when neither is primary`() {
        val people = ContactDeduplicator.dedupe(
            listOf(
                Row("7", "Rosa", "031 555 01 10", kind = PhoneKind.LANDLINE),
                Row("7", "Rosa", "+41 31 555 01 09"),
                Row("7", "Rosa", "078 555 01 11", kind = PhoneKind.MOBILE)
            )
        )

        assertEquals("078 555 01 11", people.single().phoneNumber)
    }

    @Test
    fun `an untyped number beats a landline`() {
        val people = ContactDeduplicator.dedupe(
            listOf(
                Row("7", "Rosa", "031 555 01 10", kind = PhoneKind.LANDLINE),
                Row("7", "Rosa", "+41 31 555 01 09")
            )
        )

        assertEquals("+41 31 555 01 09", people.single().phoneNumber)
    }

    @Test
    fun `a default chosen by hand wins, even a landline`() {
        val people = ContactDeduplicator.dedupe(
            listOf(
                Row("7", "Rosa", "078 555 01 11", kind = PhoneKind.MOBILE, isPrimary = true),
                Row("7", "Rosa", "031 555 01 10", kind = PhoneKind.LANDLINE, isSuperPrimary = true)
            )
        )

        assertEquals("031 555 01 10", people.single().phoneNumber)
    }

    @Test
    fun `choosing a number does not move the person in the list`() {
        val people = ContactDeduplicator.dedupe(
            listOf(
                Row("1", "Anna", "031 111 11 11", kind = PhoneKind.LANDLINE),
                Row("2", "Bea", "079 222 22 22", kind = PhoneKind.MOBILE),
                Row("1", "Anna", "079 111 11 11", kind = PhoneKind.MOBILE)
            )
        )

        assertEquals(listOf("Anna", "Bea"), people.map { it.name })
        assertEquals("079 111 11 11", people.first().phoneNumber)
    }

    @Test
    fun `colleagues on one switchboard are two people`() {
        // +41 31 555 01 12 reaches both of them. Keying on the number deleted one.
        val people = ContactDeduplicator.dedupe(
            listOf(
                Row("1633", "Gregor Albrecht", "+41 31 555 01 12"),
                Row("1750", "Sandro Bieri", "+41 31 555 01 12")
            )
        )

        assertEquals(
            listOf("Gregor Albrecht", "Sandro Bieri"),
            people.map { it.name }
        )
    }

    @Test
    fun `nine people whose stored number is just a country code are nine people`() {
        val names = listOf(
            "Lukas Sommer", "Marcel Keller", "Sandra Graf", "Oliver Huber",
            "Stefan Frei", "Fabian Roth", "Noah Steiner",
            "Firma Empfang", "Mia Lang"
        )
        val people = ContactDeduplicator.dedupe(
            names.mapIndexed { index, name -> Row(index.toString(), name, "+41") }
        )

        assertEquals(names, people.map { it.name })
    }

    @Test
    fun `two unnamed entries for the same number are one line she can read`() {
        // Both of these carry the number as their display name, so on her screen they are the
        // same line of digits printed twice.
        val people = ContactDeduplicator.dedupe(
            listOf(
                Row("1320", "+41 78 555 01 11", "+41 78 555 01 11"),
                Row("1628", "+41 78 555 01 11", "+41785550111")
            )
        )

        assertEquals(1, people.size)
    }

    @Test
    fun `the same number under two different names stays two entries`() {
        val people = ContactDeduplicator.dedupe(
            listOf(
                Row("98", "Firma Empfangstelefonie", "+41 31 555 01 13"),
                Row("207", "Firma", "+41315550113")
            )
        )

        assertEquals(2, people.size)
    }

    @Test
    fun `a row with no usable number is dropped`() {
        val people = ContactDeduplicator.dedupe(
            listOf(
                Row("1", "Claire-Lise", ""),
                Row("1", "Claire-Lise", "+41 79 555 01 14")
            )
        )

        // The empty row must not claim the person, or the row carrying her actual telephone
        // number would be dropped behind it.
        assertEquals(1, people.size)
        assertEquals("+41 79 555 01 14", people.single().phoneNumber)
    }

    @Test
    fun `entries with no contact id fall back to name and number`() {
        val people = ContactDeduplicator.dedupe(
            listOf(
                Row("", "Jana", "079 555 01 22"),
                Row("", "Jana", "0795550122"),
                Row("", "Jana", "031 555 01 10")
            )
        )

        // Same name and number collapses; a second number under the same name does not, because
        // without an id there is nothing to say it is the same Jana.
        assertEquals(2, people.size)
    }

    @Test
    fun `photo and favourite survive`() {
        val people = ContactDeduplicator.dedupe(
            listOf(Row("42", "Claire-Lise", "+41 79 555 01 14", "content://photo/42", true))
        )

        assertEquals("content://photo/42", people.single().photoUri)
        assertEquals(true, people.single().isFavorite)
    }

    @Test
    fun `of two entries for the same person, the one with a face wins`() {
        // Out of her address book: Laura is in it twice on one number, and the photograph is on
        // the entry the family keeps rather than on the one a mail account imported.
        val chosen = ContactDeduplicator.bestOf(
            listOf(
                Contact("4883", "Laura Hartmann", "+41785550101", photoUri = null),
                Contact("1787", "Laura \"Lala Lulu\" Hartmann", "+41785550101", "content://photo/399")
            )
        )

        assertEquals("content://photo/399", chosen?.photoUri)
        assertEquals("1787", chosen?.id)
    }

    @Test
    fun `with no photograph anywhere, the first answer stands`() {
        val chosen = ContactDeduplicator.bestOf(
            listOf(
                Contact("1", "Claire-Lise", "+41795550114", photoUri = null),
                Contact("2", "Claire-Lise Schmid", "+41795550114", photoUri = null)
            )
        )

        // Which of two names is the better name is not a question a photograph can answer, so
        // nothing is reordered.
        assertEquals("1", chosen?.id)
    }

    @Test
    fun `a blank photo uri is no photograph`() {
        val chosen = ContactDeduplicator.bestOf(
            listOf(
                Contact("1", "Jana", "+41795550122", photoUri = "  "),
                Contact("2", "Jana", "+41795550122", photoUri = "content://photo/9")
            )
        )

        assertEquals("2", chosen?.id)
    }

    @Test
    fun `nobody matched is nobody`() {
        assertNull(ContactDeduplicator.bestOf(emptyList()))
    }

    @Test
    fun `numbers are compared without their formatting`() {
        assertEquals("+41785550111", ContactDeduplicator.normalizeNumber("+41 78 555 01 11"))
        assertEquals("+41785550111", ContactDeduplicator.normalizeNumber("+41-78-555-01-11"))
        assertEquals("", ContactDeduplicator.normalizeNumber(""))
    }
}
