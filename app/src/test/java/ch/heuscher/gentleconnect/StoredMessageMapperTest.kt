package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.StoredMessageMapper
import ch.heuscher.gentleconnect.data.db.StoredMessageEntity
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.SendState
import ch.heuscher.gentleconnect.model.arrivedAt
import ch.heuscher.gentleconnect.model.canBeAnswered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredMessageMapperTest {

    private fun entity(direction: String = "OUTGOING", sendState: String? = "SENT") =
        StoredMessageEntity(
            id = "out_1",
            conversationId = "contact:7",
            direction = direction,
            counterpartId = "ECHO1001",
            counterpartName = "Sabine",
            text = "Yes, gladly",
            timestamp = 1_700_000_000_000L,
            isRead = true,
            sendState = sendState,
            transport = "THREEMA",
            channel = "ON_DEVICE"
        )

    @Test
    fun `a message she sent comes back the way it went in`() {
        val message = Message(
            id = "out_1",
            senderName = "Sabine",
            senderId = "ECHO1001",
            text = "Yes, gladly",
            timestamp = 1_700_000_000_000L,
            sourcePackage = "",
            isRead = true,
            transport = BridgeTransport.THREEMA,
            channel = MessageChannel.ON_DEVICE,
            direction = MessageDirection.OUTGOING,
            sendState = SendState.PENDING
        )

        val restored = StoredMessageMapper.toMessage(
            StoredMessageMapper.toEntity(message, "contact:7")
        )

        assertEquals(MessageDirection.OUTGOING, restored.direction)
        assertEquals(SendState.PENDING, restored.sendState)
        assertEquals(BridgeTransport.THREEMA, restored.transport)
        assertEquals(MessageChannel.ON_DEVICE, restored.channel)
        assertEquals("contact:7", restored.conversationId)
        assertEquals(message.text, restored.text)
    }

    @Test
    fun `a group message keeps the group it was sent to`() {
        val message = Message(
            id = "threema_9",
            senderName = "Anna",
            senderId = "ECHO1001",
            text = "Kommt ihr am Sonntag?",
            timestamp = 1L,
            sourcePackage = "",
            groupIdentity = GroupIdentity("TSTMBR06", "0a1b2c3d4e5f6071")
        )

        val restored = StoredMessageMapper.toMessage(
            StoredMessageMapper.toEntity(message, "group:TSTMBR06:0a1b2c3d4e5f6071")
        )

        assertEquals(GroupIdentity("TSTMBR06", "0a1b2c3d4e5f6071"), restored.groupIdentity)
    }

    @Test
    fun `a row we cannot fully read is still her message`() {
        // Enums are stored by name, so a value written by a later version of the app reads as
        // nothing we know. Losing the whole row would lose something she wrote; the attribute is
        // the cheaper thing to lose.
        val restored = StoredMessageMapper.toMessage(
            entity(direction = "SIDEWAYS", sendState = "MAYBE").copy(
                transport = "CARRIER PIGEON",
                channel = "SEMAPHORE"
            )
        )

        assertEquals("Yes, gladly", restored.text)
        assertEquals(MessageDirection.INCOMING, restored.direction)
        assertNull(restored.sendState)
        assertEquals(BridgeTransport.UNKNOWN, restored.transport)
        assertEquals(MessageChannel.DEVICE, restored.channel)
    }

    @Test
    fun `a video message keeps both its still and its film`() {
        // The one row that carries two attachments, and they arrive minutes apart. Dropping
        // either on the way through the database would undo a download that cannot be repeated:
        // WhatsApp expires media, so a film fetched once is often the only copy this phone will
        // ever have.
        val message = Message(
            id = "whatsapp_3EB2",
            senderName = "Anna",
            senderId = "41791234567@s.whatsapp.net",
            text = "[Video]",
            timestamp = 1L,
            sourcePackage = "",
            photoPath = "/data/x/media/3EB2.jpg",
            videoPath = "/data/x/media/3EB2.mp4"
        )

        val restored = StoredMessageMapper.toMessage(
            StoredMessageMapper.toEntity(message, "contact:7")
        )

        assertEquals("/data/x/media/3EB2.jpg", restored.photoPath)
        assertEquals("/data/x/media/3EB2.mp4", restored.videoPath)
    }

    @Test
    fun `a message with no film has none`() {
        // Which is nearly every message. Null has to survive the round trip as null, because the
        // play control is drawn on exactly this: a path that is there.
        assertNull(StoredMessageMapper.toMessage(entity()).videoPath)
    }

    @Test
    fun `a message of hers is not something she can answer`() {
        val restored = StoredMessageMapper.toMessage(entity())
        assertTrue(restored.isOutgoing)
        assertFalse(restored.canBeAnswered)
    }

    @Test
    fun `a quoted message keeps all four quote fields through the store`() {
        val message = Message(
            id = "in_2",
            senderName = "Sabine",
            senderId = "ECHO1001",
            text = "Ja, gerne!",
            timestamp = 1_700_000_000_000L,
            sourcePackage = "",
            isRead = true,
            transport = BridgeTransport.THREEMA,
            channel = MessageChannel.ON_DEVICE,
            quotedId = "threema_a3f19c04b7e25d81",
            quotedSenderName = "Anna",
            quotedText = "Kommst du am Sonntag zum Essen?",
            quotedIsHers = true
        )

        val restored = StoredMessageMapper.toMessage(
            StoredMessageMapper.toEntity(message, "contact:7")
        )

        assertEquals("threema_a3f19c04b7e25d81", restored.quotedId)
        assertEquals("Anna", restored.quotedSenderName)
        assertEquals("Kommst du am Sonntag zum Essen?", restored.quotedText)
        assertTrue(restored.quotedIsHers)
    }

    @Test
    fun `a message preserves its receivedAt timestamp through the store`() {
        val message = Message(
            id = "threema_rec",
            senderName = "Sabine",
            senderId = "ECHO1001",
            text = "Gleich da",
            timestamp = 1_700_000_000_000L,
            receivedAt = 1_700_000_005_000L,
            sourcePackage = "",
            isRead = false,
            transport = BridgeTransport.THREEMA,
            channel = MessageChannel.ON_DEVICE
        )

        val entity = StoredMessageMapper.toEntity(message, "contact:7")
        assertEquals(1_700_000_005_000L, entity.receivedAt)

        val restored = StoredMessageMapper.toMessage(entity)
        assertEquals(1_700_000_005_000L, restored.receivedAt)
        assertEquals(1_700_000_005_000L, restored.arrivedAt())
    }
}
