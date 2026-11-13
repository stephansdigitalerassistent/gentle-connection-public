package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.MessageRouter
import ch.heuscher.gentleconnect.data.SendChannel
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.Recipient
import ch.heuscher.gentleconnect.model.SenderKind
import ch.heuscher.gentleconnect.model.canBeAnswered
import ch.heuscher.gentleconnect.model.senderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRoutingTest {

    @Test
    fun `threema wins when she has an id for the person`() {
        val decision = MessageRouter.decide(
            threemaId = "ECHO1001",
            whatsappId = "41791234567",
            phoneNumber = "+41791234567"
        )
        assertEquals(SendChannel.THREEMA, decision?.channel)
        assertEquals("ECHO1001", decision?.target)
    }

    @Test
    fun `whatsapp is used when there is no threema id`() {
        val decision = MessageRouter.decide(
            threemaId = null,
            whatsappId = "41791234567",
            phoneNumber = "+41791234567"
        )
        assertEquals(SendChannel.WHATSAPP, decision?.channel)
        assertEquals("41791234567", decision?.target)
    }

    @Test
    fun `sms is the last resort, and reached only when it is the only one`() {
        val decision = MessageRouter.decide(
            threemaId = null,
            whatsappId = null,
            phoneNumber = "+41791234567"
        )

        assertEquals(SendChannel.SMS, decision?.channel)
        assertEquals("+41791234567", decision?.target)

        // Last, though: anything that reaches her people for nothing wins over the radio.
        assertEquals(
            SendChannel.WHATSAPP,
            MessageRouter.decide(null, "41791234567", "+41791234567")?.channel
        )
    }

    @Test
    fun `blank is not a route`() {
        // A Firestore document with an empty string in it must not beat a real route.
        val decision = MessageRouter.decide(
            threemaId = "   ",
            whatsappId = "",
            phoneNumber = "+41791234567"
        )
        assertEquals(SendChannel.SMS, decision?.channel)

        // Whatever SMS is doing, a blank never beats a real Threema ID.
        assertEquals(
            SendChannel.THREEMA,
            MessageRouter.decide("ECHO1001", "", "  ")?.channel
        )
    }

    @Test
    fun `nobody reachable yields no decision`() {
        assertNull(MessageRouter.decide(null, null, null))
        assertNull(MessageRouter.decide("", "  ", ""))
    }

    @Test
    fun `sms does not travel over the bridge`() {
        assertNull(MessageRouter.transportFor(SendChannel.SMS))
        assertEquals(BridgeTransport.THREEMA, MessageRouter.transportFor(SendChannel.THREEMA))
        assertEquals(BridgeTransport.WHATSAPP, MessageRouter.transportFor(SendChannel.WHATSAPP))
    }

    @Test
    fun `a recipient with no route is not reachable`() {
        assertFalse(Recipient(name = "Claire-Lise").isReachable)
        assertFalse(Recipient(name = "Claire-Lise", phoneNumber = "  ").isReachable)
        assertTrue(Recipient(name = "Claire-Lise", threemaId = "ECHO1001").isReachable)
        assertTrue(Recipient(name = "Claire-Lise", whatsappId = "41791234567").isReachable)
    }

    @Test
    fun `reachability agrees with the router, so the picker cannot offer a dead end`() {
        // The bug this guards: a name in the list of people she can write to, and a send that
        // then has nowhere to go. Holding the rule twice is how that happens.
        val phoneOnly = Recipient(name = "Claire-Lise", phoneNumber = "+41791234567")

        assertTrue(phoneOnly.isReachable)
        assertEquals(
            MessageRouter.decide(null, null, "+41791234567") != null,
            phoneOnly.isReachable
        )
    }

    @Test
    fun `a text message from a person can be answered`() {
        val fromDaughter = Message(
            id = "sms_1",
            senderName = "Claire-Lise",
            senderAddress = "+41791234567",
            contactId = "42",
            text = "Bis morgen",
            timestamp = 0L,
            sourcePackage = "pkg",
            channel = MessageChannel.DEVICE
        )
        // Somebody texts her; she can text back. Her own SMS history is on her screen in full,
        // and a year of her sister's texts with no way to answer any of them is not a smaller
        // app, it is a broken one.
        assertEquals(SenderKind.PERSON, fromDaughter.senderKind)
        assertTrue(fromDaughter.canBeAnswered)

        // A service address still cannot be: there is no subscriber behind "RoamingInfo".
        assertFalse(fromDaughter.copy(senderAddress = "RoamingInfo", contactId = null).canBeAnswered)

        // A message that came over the bridge is answerable regardless.
        assertTrue(fromDaughter.copy(channel = MessageChannel.BRIDGE).canBeAnswered)
    }
}
