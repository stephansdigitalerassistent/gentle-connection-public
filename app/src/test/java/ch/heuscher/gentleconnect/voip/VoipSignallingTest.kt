package ch.heuscher.gentleconnect.voip

import ch.heuscher.gentleconnect.voip.signalling.BadMessageException
import ch.heuscher.gentleconnect.voip.signalling.VideoFeature
import ch.heuscher.gentleconnect.voip.signalling.VoipCallAnswerData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallHangupData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallOfferData
import ch.heuscher.gentleconnect.voip.signalling.VoipCallRingingData
import ch.heuscher.gentleconnect.voip.signalling.VoipICECandidatesData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class VoipSignallingTest {

    private val realisticSdpOffer = """
        v=0
        o=- 123456789 2 IN IP4 127.0.0.1
        s=-
        t=0 0
        a=group:BUNDLE audio
        m=audio 9 UDP/TLS/RTP/SAVPF 111 63 9 0 8 126
        c=IN IP4 0.0.0.0
        a=rtcp:9 IN IP4 0.0.0.0
        a=ice-ufrag:abcd
        a=ice-pwd:1234567890abcdef
        a=fingerprint:sha-256 00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF
        a=setup:actpass
        a=mid:audio
        a=sendrecv
        a=rtpmap:111 opus/48000/2
    """.trimIndent()

    private val realisticSdpAnswer = """
        v=0
        o=- 987654321 2 IN IP4 127.0.0.1
        s=-
        t=0 0
        a=group:BUNDLE audio
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        c=IN IP4 0.0.0.0
        a=ice-ufrag:efgh
        a=ice-pwd:fedcba0987654321
        a=setup:active
        a=mid:audio
        a=sendrecv
        a=rtpmap:111 opus/48000/2
    """.trimIndent()

    @Test
    fun testCallOfferRoundTripRealisticSdp() {
        val testCallId = 987654321L
        val offerData = VoipCallOfferData.OfferData()
            .setSdpType("offer")
            .setSdp(realisticSdpOffer)

        val originalOffer = VoipCallOfferData()
            .setCallId(testCallId)
            .setOfferData(offerData)
            .addFeature(VideoFeature())

        val bos = ByteArrayOutputStream()
        originalOffer.write(bos)
        val jsonStr = bos.toString("UTF-8")

        val parsedOffer = VoipCallOfferData.parse(jsonStr)

        assertEquals(testCallId, parsedOffer.callId)
        assertNotNull(parsedOffer.offerData)
        assertEquals("offer", parsedOffer.offerData?.sdpType)
        assertTrue(parsedOffer.offerData?.sdp?.contains("a=ice-ufrag:abcd") == true)
        assertTrue(parsedOffer.offerData?.sdp?.contains("opus/48000/2") == true)
        assertTrue(parsedOffer.features.hasFeature("video"))
    }

    @Test
    fun testCallAnswerRoundTripAccept() {
        val testCallId = 987654321L
        val answerData = VoipCallAnswerData.AnswerData()
            .setSdpType("answer")
            .setSdp(realisticSdpAnswer)

        val originalAnswer = VoipCallAnswerData()
            .setCallId(testCallId)
            .setAction(VoipCallAnswerData.Action.ACCEPT)
            .setAnswerData(answerData)

        val bos = ByteArrayOutputStream()
        originalAnswer.write(bos)
        val jsonStr = bos.toString("UTF-8")

        val parsedAnswer = VoipCallAnswerData.parse(jsonStr)

        assertEquals(testCallId, parsedAnswer.callId)
        assertEquals(VoipCallAnswerData.Action.ACCEPT, parsedAnswer.action)
        assertNotNull(parsedAnswer.answerData)
        assertEquals("answer", parsedAnswer.answerData?.sdpType)
        assertTrue(parsedAnswer.answerData?.sdp?.contains("a=ice-ufrag:efgh") == true)
        assertNull(parsedAnswer.rejectReason)
    }

    @Test
    fun testCallAnswerRoundTripReject() {
        val testCallId = 12345L
        val originalAnswer = VoipCallAnswerData()
            .setCallId(testCallId)
            .setAction(VoipCallAnswerData.Action.REJECT)
            .setRejectReason(VoipCallAnswerData.RejectReason.BUSY)

        val jsonStr = originalAnswer.generateString()
        val parsedAnswer = VoipCallAnswerData.parse(jsonStr)

        assertEquals(testCallId, parsedAnswer.callId)
        assertEquals(VoipCallAnswerData.Action.REJECT, parsedAnswer.action)
        assertEquals(VoipCallAnswerData.RejectReason.BUSY, parsedAnswer.rejectReason)
        assertNull(parsedAnswer.answerData)
    }

    @Test
    fun testICECandidatesRoundTrip() {
        val testCallId = 445566L
        val cand1 = VoipICECandidatesData.Candidate(
            candidate = "candidate:1 1 UDP 2122260223 192.168.1.100 54321 typ host",
            sdpMid = "audio",
            sdpMLineIndex = 0,
            ufrag = "abcd"
        )
        val cand2 = VoipICECandidatesData.Candidate(
            candidate = "candidate:2 1 UDP 1686052863 1.2.3.4 12345 typ srflx raddr 192.168.1.100 rport 54321",
            sdpMid = "audio",
            sdpMLineIndex = 0,
            ufrag = "abcd"
        )

        val candidatesData = VoipICECandidatesData()
            .setCallId(testCallId)
            .setCandidates(arrayOf(cand1, cand2))

        val jsonStr = candidatesData.generateString()
        val parsedCandidates = VoipICECandidatesData.parse(jsonStr)

        assertEquals(testCallId, parsedCandidates.callId)
        assertNotNull(parsedCandidates.candidates)
        assertEquals(2, parsedCandidates.candidates?.size)
        assertEquals("audio", parsedCandidates.candidates?.get(0)?.sdpMid)
        assertEquals("abcd", parsedCandidates.candidates?.get(1)?.ufrag)
    }

    @Test
    fun testCallRingingAndHangupRoundTrip() {
        val testCallId = 778899L

        val ringing = VoipCallRingingData().setCallId(testCallId)
        val ringingJson = ringing.generateString()
        val parsedRinging = VoipCallRingingData.parse(ringingJson)
        assertEquals(testCallId, parsedRinging.callId)

        val hangup = VoipCallHangupData().setCallId(testCallId)
        val hangupJson = hangup.generateString()
        val parsedHangup = VoipCallHangupData.parse(hangupJson)
        assertEquals(testCallId, parsedHangup.callId)

        // Historical empty string parse test
        val emptyHangup = VoipCallHangupData.parse("   ")
        assertNull(emptyHangup.callId)
    }

    @Test
    fun testMalformedInputRejection() {
        // Rejects invalid JSON
        try {
            VoipCallOfferData.parse("{invalid json")
            fail("Expected BadMessageException for invalid JSON string")
        } catch (e: BadMessageException) {
            // Success
        }

        // Rejects offer missing sdpType
        try {
            VoipCallOfferData.parse("""{"callId":123,"offer":{"sdp":"some sdp"}}""")
            fail("Expected BadMessageException for missing sdpType")
        } catch (e: BadMessageException) {
            // Success
        }

        // Rejects offer with sdpType "answer"
        try {
            VoipCallOfferData.parse("""{"callId":123,"offer":{"sdpType":"answer","sdp":"some sdp"}}""")
            fail("Expected BadMessageException for sdpType answer in offer")
        } catch (e: BadMessageException) {
            // Success
        }

        // Rejects answer ACCEPT without answerData
        try {
            VoipCallAnswerData.parse("""{"callId":123,"action":1}""")
            fail("Expected BadMessageException for ACCEPT without answerData")
        } catch (e: BadMessageException) {
            // Success
        }

        // Rejects negative callId
        try {
            VoipCallOfferData().setCallId(-10L)
            fail("Expected IllegalArgumentException for negative callId")
        } catch (e: IllegalArgumentException) {
            // Success
        }

        // Rejects callId >= 2^32
        try {
            VoipCallOfferData().setCallId(1L shl 32)
            fail("Expected IllegalArgumentException for callId exceeding 32-bit uint")
        } catch (e: IllegalArgumentException) {
            // Success
        }
    }
}
