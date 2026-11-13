package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.ContactPhone
import ch.heuscher.gentleconnect.model.PhoneKind
import ch.heuscher.gentleconnect.model.SupportGroupResolver
import ch.heuscher.gentleconnect.model.SupportMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportGroupResolverTest {

    private val anna = Contact(id = "12", name = "Anna Muster", phoneNumber = "+41791111111", photoUri = "content://photo/12", threemaId = "ABCD1234")
    private val beat = Contact(id = "34", name = "Beat", phoneNumber = "+41792222222")

    private fun picked(contactId: String, name: String = "") =
        SupportMember(id = "contact-$contactId", name = name, phoneNumber = "", contactId = contactId)

    @Test
    fun `a picked favourite gets number photo and Threema from her address book`() {
        val resolved = SupportGroupResolver.resolve(listOf(picked("12", "Anna")), listOf(anna, beat))
        val m = resolved.single()
        assertEquals("contact-12", m.id)
        assertEquals("Anna Muster", m.name)
        assertEquals("+41791111111", m.phoneNumber)
        assertEquals("ABCD1234", m.threemaId)
        assertEquals("content://photo/12", m.buttonPhotoUri)
        assertNull(m.whatsAppNumber)
    }

    @Test
    fun `order is kept and there is no upper limit`() {
        val contacts = (1..8).map { Contact(id = "$it", name = "P$it", phoneNumber = "+4179000000$it") }
        val configured = (8 downTo 1).map { picked("$it") }
        val resolved = SupportGroupResolver.resolve(configured, contacts)
        assertEquals((8 downTo 1).map { "contact-$it" }, resolved.map { it.id })
    }

    @Test
    fun `WhatsApp only where the phone has proof`() {
        val resolved = SupportGroupResolver.resolve(
            configured = listOf(picked("12"), picked("34")),
            contacts = listOf(anna, beat),
            whatsAppNumberFor = { number -> number.takeIf { it == "+41792222222" } }
        )
        assertNull(resolved[0].whatsAppNumber)
        assertEquals("+41792222222", resolved[1].whatsAppNumber)
    }

    // Fabian, 2026-09-17. Three numbers in her address book, and the one the contacts provider
    // returned first — the one every lookup used — was his office landline. No Threema, no
    // WhatsApp, and a help request sent as an SMS to a telephone that cannot receive one.
    private val fabian = Contact(id = "56", name = "Fabian", phoneNumber = "+41315550104")

    private val fabianNumbers = listOf(
        ContactPhone("+41315550104", "56", PhoneKind.LANDLINE),
        ContactPhone("+41315550105", "56", PhoneKind.LANDLINE),
        ContactPhone("+41795550106", "56", PhoneKind.MOBILE)
    )

    @Test
    fun `the message goes to the mobile even when the address book leads with the landline`() {
        val resolved = SupportGroupResolver.resolve(
            configured = listOf(picked("56")),
            contacts = listOf(fabian),
            phonesOf = { fabianNumbers }
        )
        assertEquals("+41795550106", resolved.single().phoneNumber)
    }

    @Test
    fun `Threema and WhatsApp are found on any of their numbers`() {
        val resolved = SupportGroupResolver.resolve(
            configured = listOf(picked("56")),
            contacts = listOf(fabian),
            phonesOf = { fabianNumbers },
            threemaIdFor = { number -> "TSTMBR08".takeIf { number == "+41795550106" } },
            whatsAppNumberFor = { number -> number.takeIf { it == "+41795550106" } }
        )
        val m = resolved.single()
        assertEquals("TSTMBR08", m.threemaId)
        assertEquals("+41795550106", m.whatsAppNumber)
    }

    @Test
    fun `the numbers he might ring back from are kept`() {
        val resolved = SupportGroupResolver.resolve(
            configured = listOf(picked("56")),
            contacts = listOf(fabian),
            phonesOf = { fabianNumbers }
        )
        assertEquals(listOf("+41315550104", "+41315550105"), resolved.single().otherNumbers)
    }

    @Test
    fun `a landline is still used when it is all there is`() {
        val onlyLandline = listOf(ContactPhone("+41315550104", "56", PhoneKind.LANDLINE))
        val resolved = SupportGroupResolver.resolve(
            configured = listOf(picked("56")),
            contacts = listOf(fabian),
            phonesOf = { onlyLandline }
        )
        // Her button dials it, and a call to a landline is a perfectly good call.
        assertEquals("+41315550104", resolved.single().phoneNumber)
    }

    @Test
    fun `an untyped number is preferred over a landline`() {
        val mixed = listOf(
            ContactPhone("+41315550104", "56", PhoneKind.LANDLINE),
            ContactPhone("+41795550106", "56", PhoneKind.OTHER)
        )
        val resolved = SupportGroupResolver.resolve(
            configured = listOf(picked("56")),
            contacts = listOf(fabian),
            phonesOf = { mixed }
        )
        assertEquals("+41795550106", resolved.single().phoneNumber)
    }

    @Test
    fun `without the numbers it behaves as it always did`() {
        val resolved = SupportGroupResolver.resolve(listOf(picked("12", "Anna")), listOf(anna, beat))
        assertEquals("+41791111111", resolved.single().phoneNumber)
        assertEquals("ABCD1234", resolved.single().threemaId)
    }

    @Test
    fun `a contact gone from the address book is dropped`() {
        val resolved = SupportGroupResolver.resolve(listOf(picked("99"), picked("34")), listOf(anna, beat))
        assertEquals(listOf("contact-34"), resolved.map { it.id })
    }

    @Test
    fun `before the address book is read the stored member is kept`() {
        val resolved = SupportGroupResolver.resolve(listOf(picked("12", "Anna")), emptyList())
        assertEquals("Anna", resolved.single().name)
    }

    @Test
    fun `hand-typed members pass through unchanged`() {
        val typed = SupportMember(id = "member_1", name = "Carla", phoneNumber = "+41793333333", threemaId = "ZZZZ9999")
        val resolved = SupportGroupResolver.resolve(listOf(typed, picked("12")), listOf(anna))
        assertEquals(typed, resolved[0])
        assertTrue(resolved[1].contactId == "12")
    }
}
