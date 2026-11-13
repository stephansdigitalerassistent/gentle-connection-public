package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.telecom.CallStage
import ch.heuscher.gentleconnect.telecom.SecondCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which calls are turned away for arriving while she is already on the telephone.
 *
 * Written against the evening of 2026-09-17, when a second call arrived mid-conversation, was
 * answered, and left one live call, one held call and an app that has room for neither.
 *
 * The rule has to be right in both directions. Turning away too much means a telephone that
 * refuses her family for reasons she will never be told; turning away too little means the two-call
 * confusion this exists to prevent — and the one call that must never be refused is the emergency
 * services ringing back.
 */
class SecondCallTest {

    @Test
    fun `somebody ringing while she is talking is turned away`() {
        assertTrue(
            SecondCall.shouldEnd(
                ongoing = CallStage.TALKING,
                arriving = CallStage.RINGING,
                arrivingIsEmergency = false
            )
        )
    }

    @Test
    fun `somebody ringing while her own call is still dialling is turned away`() {
        assertTrue(
            SecondCall.shouldEnd(
                ongoing = CallStage.DIALLING,
                arriving = CallStage.RINGING,
                arrivingIsEmergency = false
            )
        )
    }

    @Test
    fun `somebody ringing while another call is already ringing is turned away`() {
        assertTrue(
            SecondCall.shouldEnd(
                ongoing = CallStage.RINGING,
                arriving = CallStage.RINGING,
                arrivingIsEmergency = false
            )
        )
    }

    @Test
    fun `the first call of all rings normally`() {
        assertFalse(
            SecondCall.shouldEnd(
                ongoing = null,
                arriving = CallStage.RINGING,
                arrivingIsEmergency = false
            )
        )
    }

    @Test
    fun `a call arriving as the last one ends is her next call, not a second one`() {
        assertFalse(
            SecondCall.shouldEnd(
                ongoing = CallStage.OVER,
                arriving = CallStage.RINGING,
                arrivingIsEmergency = false
            )
        )
    }

    @Test
    fun `an emergency callback always gets through`() {
        assertFalse(
            SecondCall.shouldEnd(
                ongoing = CallStage.TALKING,
                arriving = CallStage.RINGING,
                arrivingIsEmergency = true
            )
        )
    }

    @Test
    fun `a call she places herself is never turned away`() {
        for (stage in listOf(CallStage.DIALLING, CallStage.TALKING)) {
            assertFalse(
                "an outgoing call at stage $stage belongs to whoever placed it",
                SecondCall.shouldEnd(
                    ongoing = CallStage.TALKING,
                    arriving = stage,
                    arrivingIsEmergency = false
                )
            )
        }
    }

    @Test
    fun `a call already over is not turned away, there is nothing to turn away`() {
        assertFalse(
            SecondCall.shouldEnd(
                ongoing = CallStage.TALKING,
                arriving = CallStage.OVER,
                arrivingIsEmergency = false
            )
        )
    }
}
