package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.BridgeMapper
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.MessageChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeMessageMappingTest {

    @Test
    fun `mapToMessage correctly parses a valid complete Firestore document map`() {
        val data = mapOf(
            "senderName" to "Lisa",
            "senderId" to "ECHO1001",
            "transport" to "threema",
            "text" to "Hallo Mama",
            "timestamp" to 1774614400000L,
            "direction" to "in",
            "isRead" to false,
            "reaction" to "❤️"
        )

        val message = BridgeMapper.mapToMessage("doc_123", data)

        assertNotNull(message)
        assertEquals("doc_123", message!!.id)
        assertEquals("Lisa", message.senderName)
        assertEquals("ECHO1001", message.senderAddress)
        assertEquals("ECHO1001", message.senderId)
        assertEquals(BridgeTransport.THREEMA, message.transport)
        assertEquals("Hallo Mama", message.text)
        assertEquals(1774614400000L, message.timestamp)
        assertEquals("", message.sourcePackage)
        assertFalse(message.isRead)
        assertEquals("❤️", message.reaction)
        assertEquals(MessageChannel.BRIDGE, message.channel)
    }

    @Test
    fun `mapToMessage handles missing optional fields using graceful fallbacks`() {
        val data = mapOf(
            "text" to "Test ohne Name"
        )

        val message = BridgeMapper.mapToMessage("doc_456", data)

        assertNotNull(message)
        assertEquals("doc_456", message!!.id)
        assertEquals("Unbekannte Person", message.senderName)
        assertNull(message.senderAddress)
        assertNull(message.senderId)
        assertEquals(BridgeTransport.UNKNOWN, message.transport)
        assertEquals("Test ohne Name", message.text)
        assertEquals(0L, message.timestamp)
        assertFalse(message.isRead)
        assertNull(message.reaction)
        assertEquals(MessageChannel.BRIDGE, message.channel)
    }

    @Test
    fun `mapToMessage handles wrong data types gracefully without throwing`() {
        val data = mapOf(
            "senderName" to 12345,
            "senderId" to mapOf("nested" to true),
            "transport" to 7,
            "text" to 9999,
            "timestamp" to "not_a_number",
            "isRead" to "true",
            "reaction" to 42
        )

        val message = BridgeMapper.mapToMessage("doc_789", data)

        assertNotNull(message)
        assertEquals("doc_789", message!!.id)
        assertEquals("Unbekannte Person", message.senderName)
        assertEquals(0L, message.timestamp)
        assertFalse(message.isRead)
        assertEquals(MessageChannel.BRIDGE, message.channel)
    }

    @Test
    fun `mapToMessage returns null for null document data`() {
        val message = BridgeMapper.mapToMessage("doc_000", null)
        assertNull(message)
    }

    @Test
    fun `mapToPartner maps valid partner document`() {
        val data = mapOf(
            "id" to "MARKUS01",
            "transport" to "threema",
            "displayName" to "Markus",
            "phoneNumber" to "+41791234567",
            "photoUrl" to "https://example.com/photo.jpg",
            "lastContactAt" to 1774614400000L,
            "canInitiate" to true
        )

        val partner = BridgeMapper.mapToPartner("threema:MARKUS01", data)

        assertNotNull(partner)
        assertEquals("MARKUS01", partner!!.id)
        assertEquals(BridgeTransport.THREEMA, partner.transport)
        assertEquals("Markus", partner.displayName)
        assertEquals("+41791234567", partner.phoneNumber)
        assertEquals("https://example.com/photo.jpg", partner.photoUrl)
        assertEquals(1774614400000L, partner.lastContactAt)
        assertTrue(partner.canInitiate)
    }

    @Test
    fun `mapToMessage strips threema quote v2 marker from text without setting quotedId`() {
        val data = mapOf(
            "senderName" to "Lisa",
            "senderId" to "ECHO1001",
            "transport" to "threema",
            "text" to "> quote #a3f19c04b7e25d81\n\nJa, gerne!",
            "timestamp" to 1774614400000L,
            "direction" to "in",
            "isRead" to false
        )

        val message = BridgeMapper.mapToMessage("doc_123", data)

        assertNotNull(message)
        assertEquals("Ja, gerne!", message!!.text)
        assertNull(message.quotedId)
    }

    @Test
    fun `mapToMessage strips legacy v1 quote marker from text without setting quotedId`() {
        val data = mapOf(
            "senderName" to "Lisa",
            "senderId" to "ECHO1001",
            "transport" to "threema",
            "text" to "> ECHO1001: Kommst du am Sonntag zum Essen?\nJa, gerne!",
            "timestamp" to 1774614400000L,
            "direction" to "in",
            "isRead" to false
        )

        val message = BridgeMapper.mapToMessage("doc_124", data)

        assertNotNull(message)
        assertEquals("Ja, gerne!", message!!.text)
        assertNull(message.quotedId)
    }

    @Test
    fun `mapToMessage reads quotedSenderName and quotedText from Firestore data while leaving quotedId null`() {
        val data = mapOf(
            "senderName" to "Lisa",
            "senderId" to "ECHO1001",
            "transport" to "threema",
            "text" to "Ja, gerne!",
            "quotedMessageId" to "a3f19c04b7e25d81",
            "quotedSenderName" to "Anna",
            "quotedText" to "Kommst du am Sonntag zum Essen?",
            "timestamp" to 1774614400000L,
            "direction" to "in",
            "isRead" to false
        )

        val message = BridgeMapper.mapToMessage("doc_125", data)

        assertNotNull(message)
        assertEquals("Ja, gerne!", message!!.text)
        assertNull(message.quotedId)
        assertEquals("Anna", message.quotedSenderName)
        assertEquals("Kommst du am Sonntag zum Essen?", message.quotedText)
        assertFalse(message.quotedIsHers)
    }
}
