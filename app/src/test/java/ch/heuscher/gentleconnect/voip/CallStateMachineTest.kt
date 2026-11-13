package ch.heuscher.gentleconnect.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallStateMachineTest {

    private lateinit var sm: CallStateMachine

    @Before
    fun setUp() {
        sm = CallStateMachine()
    }

    @Test
    fun testNormalIncomingCallFlow() {
        val callId = 123456789L
        val sender = "ECHO1001"

        // Step 1: Offer -> INCOMING_RINGING
        val offerRes = sm.onIncomingOffer(callId, sender)
        assertTrue(offerRes is StateTransitionResult.Success)
        assertEquals(CallState.INCOMING_RINGING, sm.state)
        assertEquals(callId, sm.activeCallId)
        assertEquals(sender, sm.peerIdentity)

        // Step 2: Ringing
        val ringRes = sm.onRinging(callId)
        assertTrue(ringRes is StateTransitionResult.Success)
        assertEquals(CallState.INCOMING_RINGING, sm.state)

        // Step 3: Answer -> CONNECTING
        val answerRes = sm.onAnswer(callId)
        assertTrue(answerRes is StateTransitionResult.Success)
        assertEquals(CallState.CONNECTING, sm.state)

        // Step 4: Connected -> CONNECTED
        val connectRes = sm.onConnected(callId)
        assertTrue(connectRes is StateTransitionResult.Success)
        assertEquals(CallState.CONNECTED, sm.state)

        // Step 5: Hangup -> ENDED
        val hangupRes = sm.onHangup(callId)
        assertTrue(hangupRes is StateTransitionResult.Success)
        assertEquals(CallState.ENDED, sm.state)

        // Reset back to IDLE
        sm.reset()
        assertEquals(CallState.IDLE, sm.state)
        assertNull(sm.activeCallId)
        assertNull(sm.peerIdentity)
    }

    @Test
    fun testNormalOutgoingCallFlow() {
        val callId = 987654321L
        val recipient = "ANNA1234"

        // Step 1: Outgoing Offer -> OUTGOING_RINGING
        val offerRes = sm.onOutgoingOffer(callId, recipient)
        assertTrue(offerRes is StateTransitionResult.Success)
        assertEquals(CallState.OUTGOING_RINGING, sm.state)
        assertEquals(callId, sm.activeCallId)
        assertEquals(recipient, sm.peerIdentity)

        // Step 2: Ringing from recipient
        val ringRes = sm.onRinging(callId)
        assertTrue(ringRes is StateTransitionResult.Success)

        // Step 3: Answer from recipient -> CONNECTING
        val answerRes = sm.onAnswer(callId)
        assertTrue(answerRes is StateTransitionResult.Success)
        assertEquals(CallState.CONNECTING, sm.state)

        // Step 4: WebRTC connected -> CONNECTED
        val connectRes = sm.onConnected(callId)
        assertTrue(connectRes is StateTransitionResult.Success)
        assertEquals(CallState.CONNECTED, sm.state)

        // Step 5: Hangup -> ENDED
        val hangupRes = sm.onHangup(callId)
        assertTrue(hangupRes is StateTransitionResult.Success)
        assertEquals(CallState.ENDED, sm.state)
    }

    @Test
    fun testOutgoingCallHangupBeforeAnswer() {
        val callId = 777L
        sm.onOutgoingOffer(callId, "PEER1234")
        assertEquals(CallState.OUTGOING_RINGING, sm.state)

        val hangupRes = sm.onHangup(callId)
        assertTrue(hangupRes is StateTransitionResult.Success)
        assertEquals(CallState.ENDED, sm.state)
    }

    @Test
    fun testIncomingCallHangupBeforeAnswer() {
        val callId = 888L
        sm.onIncomingOffer(callId, "CALLER99")
        assertEquals(CallState.INCOMING_RINGING, sm.state)

        val hangupRes = sm.onHangup(callId)
        assertTrue(hangupRes is StateTransitionResult.Success)
        assertEquals(CallState.ENDED, sm.state)
    }

    @Test
    fun testCallIdMismatchRejection() {
        val activeCallId = 100L
        sm.onIncomingOffer(activeCallId, "SENDER")

        val wrongCallId = 999L

        val ringRes = sm.onRinging(wrongCallId)
        assertTrue(ringRes is StateTransitionResult.Rejected)

        val answerRes = sm.onAnswer(wrongCallId)
        assertTrue(answerRes is StateTransitionResult.Rejected)

        val connectRes = sm.onConnected(wrongCallId)
        assertTrue(connectRes is StateTransitionResult.Rejected)

        val hangupRes = sm.onHangup(wrongCallId)
        assertTrue(hangupRes is StateTransitionResult.Rejected)

        // State remains INCOMING_RINGING for original activeCallId
        assertEquals(CallState.INCOMING_RINGING, sm.state)
    }

    @Test
    fun testIllegalTransitions() {
        val callId = 555L

        // Cannot answer or connect while IDLE
        val answerIdle = sm.onAnswer(callId)
        assertTrue(answerIdle is StateTransitionResult.Rejected)

        val connectIdle = sm.onConnected(callId)
        assertTrue(connectIdle is StateTransitionResult.Rejected)

        val hangupIdle = sm.onHangup(callId)
        assertTrue(hangupIdle is StateTransitionResult.Rejected)

        // Accept offer
        sm.onIncomingOffer(callId, "SENDER")

        // Cannot accept a second offer while call is in progress
        val secondOffer = sm.onIncomingOffer(666L, "ANOTHER")
        assertTrue(secondOffer is StateTransitionResult.Rejected)

        // Cannot start outgoing offer while incoming call is in progress
        val outgoingWhileBusy = sm.onOutgoingOffer(777L, "OUTGOING")
        assertTrue(outgoingWhileBusy is StateTransitionResult.Rejected)
    }
}
