package ch.heuscher.gentleconnect.telecom

/**
 * Whether a call arriving while she is already on the telephone should be turned away.
 *
 * Pure, and separated from everything that touches Telecom, because — like
 * [UnknownCallers.shouldEnd] — it is a decision that hangs up on people, and the only honest way
 * to check it is without a telephone network in the room.
 *
 * The rule exists because this app has one call slot and no second line (see [OngoingCall]). Call
 * waiting asks the person holding the phone to keep two conversations apart, decide between them
 * under a beeping tone, and remember that the first one is still there. That is the one thing this
 * telephone is built not to ask of her.
 */
object SecondCall {

    /**
     * @param ongoing the stage of the call she is already on, or null when there is none.
     * @param arriving the stage of the call that has just turned up.
     * @param arrivingIsEmergency whether the platform marked the arriving call as an emergency
     *   call or an emergency callback. Passed in rather than read here so the decision can be
     *   tested without a `Call`.
     */
    fun shouldEnd(
        ongoing: CallStage?,
        arriving: CallStage,
        arrivingIsEmergency: Boolean
    ): Boolean {
        // Nothing to protect: no call, or one that is already being torn down. A call arriving as
        // the last one ends is her next call, not a second one, and turning it away would make the
        // telephone briefly refuse to ring after every conversation.
        if (ongoing == null || ongoing == CallStage.OVER) return false

        // Only somebody ringing her. An outgoing call placed while another is up belongs to
        // whoever placed it — ending one here would let this rule hang up a call she just asked
        // for, including the one case where that is unforgivable: a second attempt at a number
        // that did not connect.
        if (arriving != CallStage.RINGING) return false

        // The emergency services ringing back is the one second call that must always get
        // through, whatever she is in the middle of.
        if (arrivingIsEmergency) return false

        return true
    }
}
