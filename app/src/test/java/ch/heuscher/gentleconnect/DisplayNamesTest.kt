package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber
import ch.heuscher.gentleconnect.model.DisplayNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a person is called on her screen when two sources disagree.
 *
 * They disagree often, and the case that matters is her father: the Threema backup files him as
 * "Johann 'Papa Opa' Hartmann" — the name he chose for himself — and she has called him Dad for
 * seventy years. Until [DisplayNames.byContact] existed, the backup's name won on every row.
 */
class DisplayNamesTest {

    private val dad = Contact(
        id = "2421",
        name = "Dad",
        phoneNumber = "+41795550103",
        threemaId = "TSTMBR04"
    )

    private val strangerNotInHerBook = Contact(
        id = "77",
        name = "Praxis Dr. Beispiel",
        phoneNumber = "+41315550123"
    )

    @Test
    fun `the name she gave him is the one that is kept`() {
        val names = DisplayNames.byContact(listOf(dad, strangerNotInHerBook))

        assertEquals("Dad", names["TSTMBR04"])
        // A contact with no confirmed identity contributes nothing: there is no key to file it
        // under, and inventing one would name the wrong conversation.
        assertEquals(1, names.size)
    }

    @Test
    fun `an identity is matched however it was written down`() {
        val lowerCase = dad.copy(threemaId = " tstmbr04 ")

        assertEquals("Dad", DisplayNames.byContact(listOf(lowerCase))["TSTMBR04"])
    }

    @Test
    fun `a contact with no name at all is not allowed to erase one`() {
        val nameless = dad.copy(name = "   ")

        assertNull(DisplayNames.byContact(listOf(nameless))["TSTMBR04"])
    }

    @Test
    fun `the full ordering still prefers her address book over the backup`() {
        // byIdentity is the softer rule, used where a message arrived with no readable name at
        // all: the backup fills the gaps her address book leaves, and never the other way round.
        val names = DisplayNames.byIdentity(
            contacts = listOf(dad),
            imported = listOf(
                "TSTMBR04" to "Johann \"Papa Opa\" Hartmann",
                "TSTMBR05" to "Nono"
            )
        )

        assertEquals("Dad", names["TSTMBR04"])
        assertEquals("Nono", names["TSTMBR05"])
    }

    /**
     * The address book, found by telephone number.
     *
     * A WhatsApp message that arrives before the address book has been read keeps whatever name
     * came with it — the name the sender chose for themselves, or none at all — and that name was
     * then frozen in the database and shown for good. One contact calls herself "." on WhatsApp,
     * and that is what her conversation was called.
     */
    @Test
    fun `a number finds the name she filed it under`() {
        val names = DisplayNames.byNumber(
            listOf(
                Contact(id = "1", name = "Sonja Widmer", phoneNumber = "+41795550119"),
                Contact(id = "2", name = "Sina Jenni", phoneNumber = "079 555 01 20")
            )
        )

        assertEquals("Sonja Widmer", names[WhatsAppNumber.subscriberKey("41795550119")])
        // However either side happens to be written.
        assertEquals("Sina Jenni", names[WhatsAppNumber.subscriberKey("+41795550120")])
    }

    @Test
    fun `a contact with no number and a contact with no name are both skipped`() {
        val names = DisplayNames.byNumber(
            listOf(
                Contact(id = "1", name = "Nameless", phoneNumber = ""),
                Contact(id = "2", name = "  ", phoneNumber = "+41795550119")
            )
        )
        assertTrue(names.isEmpty())
    }

    /** One number written down twice is one person, and the phone book's order decides. */
    @Test
    fun `the first entry for a number wins`() {
        val names = DisplayNames.byNumber(
            listOf(
                Contact(id = "1", name = "Sonja Widmer", phoneNumber = "+41795550119"),
                Contact(id = "2", name = "Sonja (old)", phoneNumber = "+41795550119")
            )
        )
        assertEquals("Sonja Widmer", names[WhatsAppNumber.subscriberKey("+41795550119")])
    }
}
