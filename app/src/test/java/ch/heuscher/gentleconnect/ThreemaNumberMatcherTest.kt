package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.MessageRouter
import ch.heuscher.gentleconnect.data.PhoneNumberNormalizer
import ch.heuscher.gentleconnect.data.ThreemaContactMatcher
import ch.heuscher.gentleconnect.data.ThreemaNumberMatcher
import ch.heuscher.gentleconnect.data.db.ThreemaContactEntity
import ch.heuscher.gentleconnect.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreemaNumberMatcherTest {

    private fun contact(name: String, number: String) =
        Contact(id = name, name = name, phoneNumber = number)

    private fun normalized(contact: Contact): String? =
        PhoneNumberNormalizer.matchable(null, contact.phoneNumber, "41")

    @Test
    fun `a matched number gives its contact the id that reaches them`() {
        val result = ThreemaNumberMatcher.attachThreemaIds(
            contacts = listOf(contact("Simon", "079 123 45 67")),
            identityByNumber = mapOf("41791234567" to "TSTMBR03"),
            normalizedNumberOf = ::normalized
        )
        assertEquals("TSTMBR03", result.single().threemaId)
    }

    @Test
    fun `an identity found on one of their other numbers still reaches them`() {
        // Her son: the number the contact carries is his office landline, which nobody matched,
        // and his mobile — the one he is on Threema under — was never looked at.
        val result = ThreemaNumberMatcher.attachThreemaIds(
            contacts = listOf(contact("Fabian", "031 555 01 04")),
            identityByNumber = mapOf("41795550106" to "TSTMBR08"),
            normalizedNumberOf = ::normalized,
            otherNormalizedNumbersOf = { listOf("41315550105", "41795550106") }
        )
        assertEquals("TSTMBR08", result.single().threemaId)
    }

    @Test
    fun `the number the contact carries wins over their other ones`() {
        val result = ThreemaNumberMatcher.attachThreemaIds(
            contacts = listOf(contact("Simon", "079 123 45 67")),
            identityByNumber = mapOf("41791234567" to "TSTMBR03", "41315550105" to "WRONG111"),
            normalizedNumberOf = ::normalized,
            otherNormalizedNumbersOf = { listOf("41315550105") }
        )
        assertEquals("TSTMBR03", result.single().threemaId)
    }

    @Test
    fun `a contact whose number nobody matched is left alone`() {
        val result = ThreemaNumberMatcher.attachThreemaIds(
            contacts = listOf(contact("Fremde Person", "079 999 99 99")),
            identityByNumber = mapOf("41791234567" to "TSTMBR03"),
            normalizedNumberOf = ::normalized
        )
        assertNull(result.single().threemaId)
    }

    @Test
    fun `the number wins over the name`() {
        // Two Simons in her Threema backup and one in her phone: the name matcher gives up, and
        // it should — but the directory server answered for this exact number, and that is not a
        // guess. The whole point of doing the lookup is that it settles what names cannot.
        val simon = contact("Simon Hartmann", "079 123 45 67")
        val byName = ThreemaContactMatcher.attachThreemaIds(
            contacts = listOf(simon),
            threemaContacts = listOf(
                ThreemaContactEntity("WRONG111", "Simon", "Hartmann", null, null),
                ThreemaContactEntity("WRONG222", "Simon", "Hartmann", null, null)
            )
        )
        assertNull(byName.single().threemaId)

        val byNumber = ThreemaNumberMatcher.attachThreemaIds(
            contacts = byName,
            identityByNumber = mapOf("41791234567" to "TSTMBR03"),
            normalizedNumberOf = ::normalized
        )
        assertEquals("TSTMBR03", byNumber.single().threemaId)
    }

    @Test
    fun `a number match corrects a name match that found the wrong person`() {
        val named = listOf(contact("Simon", "079 123 45 67").copy(threemaId = "GUESSED1"))
        val result = ThreemaNumberMatcher.attachThreemaIds(
            contacts = named,
            identityByNumber = mapOf("41791234567" to "TSTMBR03"),
            normalizedNumberOf = ::normalized
        )
        assertEquals("TSTMBR03", result.single().threemaId)
    }

    @Test
    fun `nothing matched changes nothing`() {
        val contacts = listOf(contact("Simon", "079 123 45 67"))
        assertEquals(
            contacts,
            ThreemaNumberMatcher.attachThreemaIds(contacts, emptyMap(), ::normalized)
        )
    }

    @Test
    fun `a matched contact becomes reachable`() {
        val result = ThreemaNumberMatcher.attachThreemaIds(
            contacts = listOf(contact("Simon", "+41 79 123 45 67")),
            identityByNumber = mapOf("41791234567" to "TSTMBR03"),
            normalizedNumberOf = ::normalized
        )
        val simon = result.single()
        assertTrue(MessageRouter.canReach(simon.threemaId, null, simon.phoneNumber))
    }
}
