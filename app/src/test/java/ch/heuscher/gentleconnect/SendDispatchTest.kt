package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.OnDeviceMessageMapper
import ch.heuscher.gentleconnect.data.SendDispatch
import ch.heuscher.gentleconnect.data.SendMechanism
import ch.heuscher.gentleconnect.data.threema.ThreemaIncomingMessage
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.canBeAnswered
import ch.heuscher.gentleconnect.model.senderKind
import ch.heuscher.gentleconnect.model.SenderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendDispatchTest {

    @Test
    fun `threema is sent by the client on this phone`() {
        assertEquals(
            SendMechanism.ON_DEVICE_THREEMA,
            SendDispatch.forTransport(BridgeTransport.THREEMA)
        )
    }

    @Test
    fun `threema never falls back to the outbox`() {
        // The bug this guards is the worst kind this app can have: writing to a queue nobody
        // drains succeeds, so a message that went nowhere is reported as sent. If the client is
        // down she must be told it did not go.
        assertTrue(SendDispatch.forTransport(BridgeTransport.THREEMA) != SendMechanism.BRIDGE_OUTBOX)
    }

    @Test
    fun `an unknown transport sends nothing rather than guessing`() {
        assertEquals(SendMechanism.NONE, SendDispatch.forTransport(BridgeTransport.UNKNOWN))
    }

    @Test
    fun `whatsapp is sent by the bridge on this phone`() {
        // Was NONE while there was no carrier, and the point of that was never "WhatsApp is not
        // supported" — it was that the alternative on offer was the outbox. Now there is a real
        // one: native/whatsapp-bridge, linked as a companion to her own account.
        assertEquals(
            SendMechanism.ON_DEVICE_WHATSAPP,
            SendDispatch.forTransport(BridgeTransport.WHATSAPP)
        )
    }

    @Test
    fun `nothing at all is dispatched to the outbox`() {
        // Guards the whole enum rather than one branch: the outbox has no reader in this
        // repository, so any transport arriving there is a silent lie about delivery. If a
        // drain is ever deployed, this test is the place to record that it exists.
        BridgeTransport.entries.forEach { transport ->
            assertTrue(
                "$transport must not be dispatched to the undrained outbox",
                SendDispatch.forTransport(transport) != SendMechanism.BRIDGE_OUTBOX
            )
        }
    }

    @Test
    fun `an incoming threema message becomes an answerable message from a person`() {
        val message = OnDeviceMessageMapper.fromThreema(
            ThreemaIncomingMessage(
                sender = "echo1001",
                text = "Bis morgen",
                timestamp = 1_700_000_000_000L,
                messageId = "abc123"
            )
        )

        assertNotNull(message)
        requireNotNull(message)
        assertEquals(MessageChannel.ON_DEVICE, message.channel)
        assertEquals(BridgeTransport.THREEMA, message.transport)
        // Upper-cased, because every other Threema ID in the app is, and the name lookup that
        // turns this into "Anna" keys on it.
        assertEquals("ECHO1001", message.senderId)
        assertEquals("threema_abc123", message.id)
        assertEquals(SenderKind.PERSON, message.senderKind)
        // Unlike an SMS, this one she can answer — the route back is the client that brought it.
        assertTrue(message.canBeAnswered)
    }

    @Test
    fun `a quote with an empty body falls back to original text instead of being dropped`() {
        val incoming = ThreemaIncomingMessage(
            sender = "ECHO1001",
            text = "> quote #a3f19c04b7e25d81\n\n",
            timestamp = 1_700_000_000_000L,
            messageId = "abc123"
        )
        val message = OnDeviceMessageMapper.fromThreema(incoming)

        assertNotNull(message)
        assertEquals("> quote #a3f19c04b7e25d81\n\n", message!!.text)
        assertNull(message.quotedId)
    }

    @Test
    fun `an empty message is not put on her screen`() {
        val blankText = OnDeviceMessageMapper.fromThreema(
            ThreemaIncomingMessage(sender = "ECHO1001", text = "   ", timestamp = 1L, messageId = "a")
        )
        val noSender = OnDeviceMessageMapper.fromThreema(
            ThreemaIncomingMessage(sender = "  ", text = "Hallo", timestamp = 1L, messageId = "b")
        )
        assertNull(blankText)
        assertNull(noSender)
    }
}
