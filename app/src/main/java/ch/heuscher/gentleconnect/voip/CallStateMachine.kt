package ch.heuscher.gentleconnect.voip

/**
 * State of an ongoing, incoming, or outgoing call.
 */
enum class CallState {
    IDLE,
    INCOMING_RINGING,
    OUTGOING_RINGING,
    CONNECTING,
    CONNECTED,
    ENDED
}

/**
 * Result of attempting a call state transition.
 */
sealed class StateTransitionResult {
    data class Success(val newState: CallState) : StateTransitionResult()
    data class Rejected(val reason: String) : StateTransitionResult()
}

/**
 * Android-free call state machine enforcing offer -> ringing -> answer -> connected -> hangup transitions,
 * checking callId correlation, and rejecting illegal transitions or callId mismatches.
 */
class CallStateMachine {
    var state: CallState = CallState.IDLE
        private set

    var activeCallId: Long? = null
        private set

    var peerIdentity: String? = null
        private set

    /**
     * Attempts to start an incoming call offer.
     */
    fun onIncomingOffer(callId: Long, sender: String): StateTransitionResult {
        if (state != CallState.IDLE) {
            return StateTransitionResult.Rejected("Cannot accept incoming offer while in state $state")
        }
        if (callId < 0) {
            return StateTransitionResult.Rejected("Invalid callId: $callId")
        }
        state = CallState.INCOMING_RINGING
        activeCallId = callId
        peerIdentity = sender
        return StateTransitionResult.Success(state)
    }

    /**
     * Attempts to start an outgoing call.
     */
    fun onOutgoingOffer(callId: Long, recipient: String): StateTransitionResult {
        if (state != CallState.IDLE) {
            return StateTransitionResult.Rejected("Cannot initiate call while in state $state")
        }
        if (callId < 0) {
            return StateTransitionResult.Rejected("Invalid callId: $callId")
        }
        state = CallState.OUTGOING_RINGING
        activeCallId = callId
        peerIdentity = recipient
        return StateTransitionResult.Success(state)
    }

    /**
     * Handles ringing signal received for the active call.
     */
    fun onRinging(callId: Long): StateTransitionResult {
        if (!verifyCallId(callId)) {
            return StateTransitionResult.Rejected("CallId mismatch: expected $activeCallId, got $callId")
        }
        if (state != CallState.OUTGOING_RINGING && state != CallState.INCOMING_RINGING) {
            return StateTransitionResult.Rejected("Cannot transition to ringing from state $state")
        }
        return StateTransitionResult.Success(state)
    }

    /**
     * Handles call answer (accept) for the active call.
     */
    fun onAnswer(callId: Long): StateTransitionResult {
        if (!verifyCallId(callId)) {
            return StateTransitionResult.Rejected("CallId mismatch: expected $activeCallId, got $callId")
        }
        if (state != CallState.INCOMING_RINGING && state != CallState.OUTGOING_RINGING) {
            return StateTransitionResult.Rejected("Cannot answer call in state $state")
        }
        state = CallState.CONNECTING
        return StateTransitionResult.Success(state)
    }

    /**
     * Handles WebRTC connection establishment.
     */
    fun onConnected(callId: Long): StateTransitionResult {
        if (!verifyCallId(callId)) {
            return StateTransitionResult.Rejected("CallId mismatch: expected $activeCallId, got $callId")
        }
        if (state != CallState.CONNECTING && state != CallState.INCOMING_RINGING && state != CallState.OUTGOING_RINGING) {
            return StateTransitionResult.Rejected("Cannot connect call in state $state")
        }
        state = CallState.CONNECTED
        return StateTransitionResult.Success(state)
    }

    /**
     * Handles call hangup or termination.
     */
    fun onHangup(callId: Long): StateTransitionResult {
        if (!verifyCallId(callId)) {
            return StateTransitionResult.Rejected("CallId mismatch: expected $activeCallId, got $callId")
        }
        if (state == CallState.IDLE) {
            return StateTransitionResult.Rejected("Cannot hangup when call state is IDLE")
        }
        state = CallState.ENDED
        return StateTransitionResult.Success(state)
    }

    /**
     * Resets the state machine back to IDLE.
     */
    fun reset() {
        state = CallState.IDLE
        activeCallId = null
        peerIdentity = null
    }

    private fun verifyCallId(callId: Long): Boolean {
        return activeCallId != null && activeCallId == callId
    }
}
