package ch.heuscher.gentleconnect.data.threema

import ch.heuscher.gentleconnect.voip.signalling.BadMessageException
import ch.heuscher.gentleconnect.voip.signalling.VideoFeature
import ch.heuscher.gentleconnect.voip.signalling.VoipCallAnswerData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallHangupData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallOfferData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallRingingData
import ch.heuscher.gentleconnect.voip.signalling.VoipICECandidatesData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

class ThreemaCallMessageTest {

    private fun stringToHex(str: String): String {
        val bytes = str.toByteArray(StandardCharsets.UTF_8)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    @Test
    fun testParseOfferSignallingData() {
        val offerData = VoipCallOfferData.OfferData()
            .setSdpType("offer")
            .setSdp("v=0\r\no=- 123456789 2 IN IP4 127.0.0.1\r\n")

        val originalOffer = VoipCallOfferData()
            .setCallId(987654321L)
            .setOfferData(offerData)
            .addFeature(VideoFeature())

        val hex = stringToHex(originalOffer.generateString())
        val callMsg = ThreemaCallMessage(
            sender = "ECHO1001",
            messageType = 0x60,
            dataHex = hex,
            timestamp = 1700000000L,
            messageId = "0000000000000001"
        )

        val parsed = callMsg.parseSignallingData()
        assertTrue(parsed is VoipCallOfferData)
        val offer = parsed as VoipCallOfferData
        assertEquals(987654321L, offer.callId)
        assertEquals("offer", offer.offerData?.sdpType)
        assertTrue(offer.offerData?.sdp?.contains("123456789") == true)
        assertTrue(offer.features.hasFeature("video"))
    }

    @Test
    fun testParseAnswerSignallingData() {
        val answerData = VoipCallAnswerData.AnswerData()
            .setSdpType("answer")
            .setSdp("v=0\r\na=setup:active\r\n")

        val originalAnswer = VoipCallAnswerData()
            .setCallId(12345678L)
            .setAction(VoipCallAnswerData.Action.ACCEPT)
            .setAnswerData(answerData)

        val hex = stringToHex(originalAnswer.generateString())
        val callMsg = ThreemaCallMessage(
            sender = "ECHO1001",
            messageType = 0x61,
            dataHex = hex,
            timestamp = 1700000001L,
            messageId = "0000000000000002"
        )

        val parsed = callMsg.parseSignallingData()
        assertTrue(parsed is VoipCallAnswerData)
        val answer = parsed as VoipCallAnswerData
        assertEquals(12345678L, answer.callId)
        assertEquals(VoipCallAnswerData.Action.ACCEPT, answer.action)
        assertEquals("answer", answer.answerData?.sdpType)
    }

    @Test
    fun testParseICECandidatesSignallingData() {
        val cand = VoipICECandidatesData.Candidate(
            candidate = "candidate:1 1 UDP 2122260223 192.168.1.1 54321 typ host",
            sdpMid = "audio",
            sdpMLineIndex = 0,
            ufrag = "testfrag"
        )
        val candidatesData = VoipICECandidatesData()
            .setCallId(5555L)
            .setCandidates(arrayOf(cand))

        val hex = stringToHex(candidatesData.generateString())
        val callMsg = ThreemaCallMessage(
            sender = "ECHO1001",
            messageType = 0x62,
            dataHex = hex,
            timestamp = 1700000002L,
            messageId = "0000000000000003"
        )

        val parsed = callMsg.parseSignallingData()
        assertTrue(parsed is VoipICECandidatesData)
        val ice = parsed as VoipICECandidatesData
        assertEquals(5555L, ice.callId)
        assertNotNull(ice.candidates)
        assertEquals(1, ice.candidates?.size)
        assertEquals("audio", ice.candidates?.get(0)?.sdpMid)
    }

    @Test
    fun testParseHangupAndRingingSignallingData() {
        val hangup = VoipCallHangupData().setCallId(777L)
        val hangupMsg = ThreemaCallMessage(
            sender = "ECHO1001",
            messageType = 0x63,
            dataHex = stringToHex(hangup.generateString()),
            timestamp = 1700000003L,
            messageId = "0000000000000004"
        )
        val parsedHangup = hangupMsg.parseSignallingData()
        assertTrue(parsedHangup is VoipCallHangupData)
        assertEquals(777L, (parsedHangup as VoipCallHangupData).callId)

        val ringing = VoipCallRingingData().setCallId(888L)
        val ringingMsg = ThreemaCallMessage(
            sender = "ECHO1001",
            messageType = 0x64,
            dataHex = stringToHex(ringing.generateString()),
            timestamp = 1700000004L,
            messageId = "0000000000000005"
        )
        val parsedRinging = ringingMsg.parseSignallingData()
        assertTrue(parsedRinging is VoipCallRingingData)
        assertEquals(888L, (parsedRinging as VoipCallRingingData).callId)
    }

    @Test
    fun testUnknownMessageTypeRejection() {
        val msg = ThreemaCallMessage(
            sender = "ECHO1001",
            messageType = 0x99,
            dataHex = stringToHex("{}"),
            timestamp = 1700000005L,
            messageId = "0000000000000006"
        )
        try {
            msg.parseSignallingData()
            fail("Expected IllegalArgumentException for messageType 0x99")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun testMalformedJsonInHex() {
        val msg = ThreemaCallMessage(
            sender = "ECHO1001",
            messageType = 0x60,
            dataHex = stringToHex("{invalid json"),
            timestamp = 1700000006L,
            messageId = "0000000000000007"
        )
        try {
            msg.parseSignallingData()
            fail("Expected BadMessageException for malformed JSON")
        } catch (e: BadMessageException) {
            // Expected
        }
    }
}
