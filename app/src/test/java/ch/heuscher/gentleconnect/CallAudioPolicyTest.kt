package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.telecom.AudioRoute
import ch.heuscher.gentleconnect.telecom.CallAudioPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Call audio routing rules.
 *
 * - Bluetooth hearing aids or headsets take precedence over everything.
 * - A plugged wired headset is an unambiguous physical action and takes precedence over her choice.
 * - Otherwise the sound goes where she chose, or where the hinge of a folding phone says.
 * - Never fight the platform by re-requesting the route it is already on.
 * - Never request a route unsupported by the current hardware (such as earpieces on tablets).
 */
class CallAudioPolicyTest {

    private val allStandardRoutes = setOf(
        AudioRoute.EARPIECE,
        AudioRoute.LOUDSPEAKER
    )

    private val withBluetooth = setOf(
        AudioRoute.BLUETOOTH,
        AudioRoute.EARPIECE,
        AudioRoute.LOUDSPEAKER
    )

    private val withWiredHeadset = setOf(
        AudioRoute.HEADSET,
        AudioRoute.EARPIECE,
        AudioRoute.LOUDSPEAKER
    )

    private val withBothHeadsets = setOf(
        AudioRoute.BLUETOOTH,
        AudioRoute.HEADSET,
        AudioRoute.EARPIECE,
        AudioRoute.LOUDSPEAKER
    )

    @Test
    fun `Bluetooth is selected when available, even if she chose the earpiece`() {
        // She put her hearing aids or Bluetooth headset on deliberately; that intention wins
        // over a button pressed earlier.
        val decision = CallAudioPolicy.decide(
            available = withBluetooth,
            current = AudioRoute.EARPIECE,
            wanted = AudioRoute.EARPIECE
        )
        assertEquals(AudioRoute.BLUETOOTH, decision)
    }

    @Test
    fun `Bluetooth is selected when available, when she chose the loudspeaker`() {
        val decision = CallAudioPolicy.decide(
            available = withBluetooth,
            current = AudioRoute.LOUDSPEAKER,
            wanted = AudioRoute.LOUDSPEAKER
        )
        assertEquals(AudioRoute.BLUETOOTH, decision)
    }

    @Test
    fun `Bluetooth wins over wired headset when both are available`() {
        val decision = CallAudioPolicy.decide(
            available = withBothHeadsets,
            current = AudioRoute.HEADSET,
            wanted = AudioRoute.LOUDSPEAKER
        )
        assertEquals(AudioRoute.BLUETOOTH, decision)
    }

    @Test
    fun `Wired headset is selected when available, even if she chose the earpiece`() {
        // Plugging a cable in is an unambiguous intention to listen through headphones.
        val decision = CallAudioPolicy.decide(
            available = withWiredHeadset,
            current = AudioRoute.EARPIECE,
            wanted = AudioRoute.EARPIECE
        )
        assertEquals(AudioRoute.HEADSET, decision)
    }

    @Test
    fun `Wired headset is selected when available, when she chose the loudspeaker`() {
        val decision = CallAudioPolicy.decide(
            available = withWiredHeadset,
            current = AudioRoute.LOUDSPEAKER,
            wanted = AudioRoute.LOUDSPEAKER
        )
        assertEquals(AudioRoute.HEADSET, decision)
    }

    @Test
    fun `Earpiece is selected when she chose it and no headset is present`() {
        val decision = CallAudioPolicy.decide(
            available = allStandardRoutes,
            current = AudioRoute.LOUDSPEAKER,
            wanted = AudioRoute.EARPIECE
        )
        assertEquals(AudioRoute.EARPIECE, decision)
    }

    @Test
    fun `Loudspeaker is selected when she chose it and no headset is present`() {
        val decision = CallAudioPolicy.decide(
            available = allStandardRoutes,
            current = AudioRoute.EARPIECE,
            wanted = AudioRoute.LOUDSPEAKER
        )
        assertEquals(AudioRoute.LOUDSPEAKER, decision)
    }

    @Test
    fun `Starting unrouted call wanting the earpiece selects earpiece`() {
        val decision = CallAudioPolicy.decide(
            available = allStandardRoutes,
            current = null,
            wanted = AudioRoute.EARPIECE
        )
        assertEquals(AudioRoute.EARPIECE, decision)
    }

    @Test
    fun `Starting unrouted call wanting the loudspeaker selects loudspeaker`() {
        val decision = CallAudioPolicy.decide(
            available = allStandardRoutes,
            current = null,
            wanted = AudioRoute.LOUDSPEAKER
        )
        assertEquals(AudioRoute.LOUDSPEAKER, decision)
    }

    @Test
    fun `Starting unrouted call with bluetooth available selects bluetooth`() {
        val decision = CallAudioPolicy.decide(
            available = withBluetooth,
            current = null,
            wanted = AudioRoute.EARPIECE
        )
        assertEquals(AudioRoute.BLUETOOTH, decision)
    }

    @Test
    fun `No route change is returned when the desired route is already the current route`() {
        // Never fight Telecom when the audio is already where it should be.
        assertNull(
            CallAudioPolicy.decide(
                available = allStandardRoutes,
                current = AudioRoute.EARPIECE,
                wanted = AudioRoute.EARPIECE
            )
        )
        assertNull(
            CallAudioPolicy.decide(
                available = allStandardRoutes,
                current = AudioRoute.LOUDSPEAKER,
                wanted = AudioRoute.LOUDSPEAKER
            )
        )
        assertNull(
            CallAudioPolicy.decide(
                available = withBluetooth,
                current = AudioRoute.BLUETOOTH,
                wanted = AudioRoute.LOUDSPEAKER
            )
        )
        assertNull(
            CallAudioPolicy.decide(
                available = withWiredHeadset,
                current = AudioRoute.HEADSET,
                wanted = AudioRoute.EARPIECE
            )
        )
    }

    @Test
    fun `Empty available set returns null and does not crash`() {
        val decision = CallAudioPolicy.decide(
            available = emptySet(),
            current = null,
            wanted = AudioRoute.LOUDSPEAKER
        )
        assertNull(decision)
    }

    @Test
    fun `Route not in available is never returned when tablet has no earpiece`() {
        // Tablets have only a loudspeaker and no earpiece hardware.
        val tabletRoutes = setOf(AudioRoute.LOUDSPEAKER)
        val decision = CallAudioPolicy.decide(
            available = tabletRoutes,
            current = null,
            wanted = AudioRoute.EARPIECE
        )
        assertEquals(AudioRoute.LOUDSPEAKER, decision)

        // Already on loudspeaker -> null
        assertNull(
            CallAudioPolicy.decide(
                available = tabletRoutes,
                current = AudioRoute.LOUDSPEAKER,
                wanted = AudioRoute.EARPIECE
            )
        )
    }

    @Test
    fun `Falls back to earpiece if loudspeaker is unavailable on device and loudspeaker is wanted`() {
        val earpieceOnly = setOf(AudioRoute.EARPIECE)
        val decision = CallAudioPolicy.decide(
            available = earpieceOnly,
            current = null,
            wanted = AudioRoute.LOUDSPEAKER
        )
        assertEquals(AudioRoute.EARPIECE, decision)
    }

    @Test
    fun `Falls back to first available route if standard outputs are missing`() {
        val customRoutes = setOf(AudioRoute.HEADSET)
        val decision = CallAudioPolicy.decide(
            available = customRoutes,
            current = null,
            wanted = AudioRoute.LOUDSPEAKER
        )
        assertEquals(AudioRoute.HEADSET, decision)
    }

    @Test
    fun `A call starts on the loudspeaker until she or the hinge says otherwise`() {
        assertEquals(AudioRoute.LOUDSPEAKER, CallAudioPolicy.wanted(hingeClosed = null, chosen = null))
    }

    @Test
    fun `Her choice holds on a phone without a hinge`() {
        assertEquals(AudioRoute.EARPIECE, CallAudioPolicy.wanted(hingeClosed = null, chosen = AudioRoute.EARPIECE))
        assertEquals(AudioRoute.LOUDSPEAKER, CallAudioPolicy.wanted(hingeClosed = null, chosen = AudioRoute.LOUDSPEAKER))
    }

    @Test
    fun `Folded shut is the earpiece and opened up is the loudspeaker`() {
        assertEquals(AudioRoute.EARPIECE, CallAudioPolicy.wanted(hingeClosed = true, chosen = null))
        assertEquals(AudioRoute.LOUDSPEAKER, CallAudioPolicy.wanted(hingeClosed = false, chosen = null))
    }

    @Test
    fun `Choosing the route already in force changes nothing, however often she presses`() {
        // Her finger often lands twice: the second landing must not undo the first.
        repeat(3) {
            assertNull(
                CallAudioPolicy.decide(
                    available = allStandardRoutes,
                    current = AudioRoute.EARPIECE,
                    wanted = CallAudioPolicy.wanted(hingeClosed = null, chosen = AudioRoute.EARPIECE)
                )
            )
        }
    }

    @Test
    fun `Hinge shut and flat are read the obvious way`() {
        assertEquals(true, CallAudioPolicy.hingeClosed(0f, wasClosed = null))
        assertEquals(false, CallAudioPolicy.hingeClosed(180f, wasClosed = null))
        assertEquals(false, CallAudioPolicy.hingeClosed(90f, wasClosed = true))
    }

    @Test
    fun `A hinge between the two thresholds keeps what it was`() {
        assertEquals(true, CallAudioPolicy.hingeClosed(45f, wasClosed = true))
        assertEquals(false, CallAudioPolicy.hingeClosed(45f, wasClosed = false))
        // Being opened: still shut at 50 degrees, open past 60.
        assertEquals(true, CallAudioPolicy.hingeClosed(50f, wasClosed = true))
        assertEquals(false, CallAudioPolicy.hingeClosed(61f, wasClosed = true))
        // Being closed: still open at 35 degrees, shut below 30.
        assertEquals(false, CallAudioPolicy.hingeClosed(35f, wasClosed = false))
        assertEquals(true, CallAudioPolicy.hingeClosed(29f, wasClosed = false))
    }

    @Test
    fun `The two choices are offered on an ordinary phone with the sound on either`() {
        assertTrue(CallAudioPolicy.offersChoice(allStandardRoutes, AudioRoute.EARPIECE, byHinge = false))
        assertTrue(CallAudioPolicy.offersChoice(allStandardRoutes, AudioRoute.LOUDSPEAKER, byHinge = false))
    }

    @Test
    fun `No choice is offered where pressing it could not work`() {
        // The hinge decides on a folding phone.
        assertFalse(CallAudioPolicy.offersChoice(allStandardRoutes, AudioRoute.LOUDSPEAKER, byHinge = true))
        // A headset would overrule either press at once.
        assertFalse(CallAudioPolicy.offersChoice(withBluetooth, AudioRoute.BLUETOOTH, byHinge = false))
        assertFalse(CallAudioPolicy.offersChoice(withWiredHeadset, AudioRoute.HEADSET, byHinge = false))
        // A tablet has nothing to choose between.
        assertFalse(CallAudioPolicy.offersChoice(setOf(AudioRoute.LOUDSPEAKER), AudioRoute.LOUDSPEAKER, byHinge = false))
        // Not routed yet.
        assertFalse(CallAudioPolicy.offersChoice(allStandardRoutes, null, byHinge = false))
    }
}
