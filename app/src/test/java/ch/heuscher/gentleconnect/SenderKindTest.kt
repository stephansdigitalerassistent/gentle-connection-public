package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.SenderKind
import ch.heuscher.gentleconnect.model.senderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The senders in these tests are the ones actually sitting in her inbox, read off the phone.
 */
class SenderKindTest {

    @Test
    fun `alphanumeric originator is a company`() {
        // Her laundry, and her carrier's roaming tariff. Neither can be replied to.
        assertEquals(SenderKind.ORGANISATION, SenderKind.of("Reinigung!"))
        assertEquals(SenderKind.ORGANISATION, SenderKind.of("RoamingInfo"))
        assertEquals(SenderKind.ORGANISATION, SenderKind.of("Swisscom"))
    }

    @Test
    fun `a full telephone number is a person`() {
        assertEquals(SenderKind.PERSON, SenderKind.of("+41791234567"))
        assertEquals(SenderKind.PERSON, SenderKind.of("031 555 01 10"))
        assertEquals(SenderKind.PERSON, SenderKind.of("0041315550110"))
    }

    @Test
    fun `short codes are companies`() {
        assertEquals(SenderKind.ORGANISATION, SenderKind.of("444"))
        assertEquals(SenderKind.ORGANISATION, SenderKind.of("12345"))
    }

    @Test
    fun `seven digits is already long enough to be somebody`() {
        // The boundary errs towards calling a borderline number a person: filing a real
        // person away as a company is the worse mistake of the two.
        assertEquals(SenderKind.PERSON, SenderKind.of("5550110"))
        assertEquals(SenderKind.ORGANISATION, SenderKind.of("555011"))
    }

    @Test
    fun `a missing address is not a person`() {
        assertEquals(SenderKind.ORGANISATION, SenderKind.of(null))
        assertEquals(SenderKind.ORGANISATION, SenderKind.of(""))
        assertEquals(SenderKind.ORGANISATION, SenderKind.of("   "))
    }

    @Test
    fun `bridge messages are always people`() {
        // A Threema ID is eight characters of letters and digits, which the address rule
        // would otherwise read as a company.
        assertEquals(
            SenderKind.PERSON,
            SenderKind.of("ECHOECHO", channel = MessageChannel.BRIDGE)
        )
    }

    @Test
    fun `somebody in her address book is a person whatever the address looks like`() {
        assertEquals(
            SenderKind.PERSON,
            SenderKind.of("Reinigung!", contactId = "42")
        )
    }

    @Test
    fun `only a person can be replied to`() {
        assertTrue(SenderKind.PERSON.canReply)
        assertFalse(SenderKind.ORGANISATION.canReply)
    }

    @Test
    fun `the extension reads the address off the message`() {
        val fromCarrier = message(senderName = "RoamingInfo", senderAddress = "RoamingInfo")
        val fromDaughter = message(senderName = "Claire-Lise", senderAddress = "+41791234567")

        assertEquals(SenderKind.ORGANISATION, fromCarrier.senderKind)
        assertEquals(SenderKind.PERSON, fromDaughter.senderKind)
    }

    private fun message(
        senderName: String,
        senderAddress: String?,
        contactId: String? = null,
        channel: MessageChannel = MessageChannel.DEVICE
    ) = Message(
        id = "id",
        senderName = senderName,
        senderAddress = senderAddress,
        contactId = contactId,
        text = "text",
        timestamp = 0L,
        sourcePackage = "pkg",
        channel = channel
    )
}
