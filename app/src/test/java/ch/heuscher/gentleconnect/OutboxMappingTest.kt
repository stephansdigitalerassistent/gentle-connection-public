package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.BridgeMapper
import ch.heuscher.gentleconnect.model.BridgeTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutboxMappingTest {

    @Test
    fun `createOutboxReactionMap produces exact document shape mandated by bridge contract`() {
        val now = 1774614450000L
        val map = BridgeMapper.createOutboxReactionMap(
            to = "ECHO1001",
            transport = BridgeTransport.THREEMA,
            emoji = "❤️",
            inReplyToMessageId = "msg_987654321",
            createdAt = now
        )

        assertEquals("ECHO1001", map["to"])
        assertEquals("threema", map["transport"])
        assertEquals("reaction", map["kind"])
        assertEquals("❤️", map["emoji"])
        assertNull(map["text"])
        assertEquals("msg_987654321", map["inReplyToMessageId"])
        assertEquals(now, map["createdAt"])
        assertEquals("pending", map["status"])
    }

    @Test
    fun `createOutboxTextMap produces exact document shape mandated by bridge contract`() {
        val now = 1774614500000L
        val map = BridgeMapper.createOutboxTextMap(
            to = "MARKUS01",
            transport = BridgeTransport.THREEMA,
            text = "Ich komme später.",
            inReplyToMessageId = null,
            createdAt = now
        )

        assertEquals("MARKUS01", map["to"])
        assertEquals("threema", map["transport"])
        assertEquals("text", map["kind"])
        assertNull(map["emoji"])
        assertEquals("Ich komme später.", map["text"])
        assertNull(map["inReplyToMessageId"])
        assertEquals(now, map["createdAt"])
        assertEquals("pending", map["status"])
    }

    @Test
    fun `outbox documents carry the transport so the bridge knows which client to send on`() {
        val whatsapp = BridgeMapper.createOutboxReactionMap(
            to = "41791234567",
            transport = BridgeTransport.WHATSAPP,
            emoji = "👍"
        )

        assertEquals("41791234567", whatsapp["to"])
        assertEquals("whatsapp", whatsapp["transport"])

        // The security rules only accept 'threema' and 'whatsapp'. An UNKNOWN transport must
        // still serialise to something recognisable rather than silently looking valid.
        val unknown = BridgeMapper.createOutboxTextMap(
            to = "someone",
            transport = BridgeTransport.UNKNOWN,
            text = "hallo"
        )
        assertEquals("unknown", unknown["transport"])
    }

    @Test
    fun `transport wire names round-trip and unrecognised values fall back to UNKNOWN`() {
        assertEquals(BridgeTransport.THREEMA, BridgeTransport.fromWire("threema"))
        assertEquals(BridgeTransport.WHATSAPP, BridgeTransport.fromWire("WhatsApp"))
        assertEquals(BridgeTransport.UNKNOWN, BridgeTransport.fromWire("signal"))
        assertEquals(BridgeTransport.UNKNOWN, BridgeTransport.fromWire(null))
    }
}
