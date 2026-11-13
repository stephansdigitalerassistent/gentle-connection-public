package ch.heuscher.gentleconnect.telecom

import android.telecom.CallAudioState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The physical audio route an active call is directed to.
 *
 * Distinct from Android's integer bitmasks so that the screen and routing policies never touch
 * platform constants or SDK-version quirks.
 */
enum class AudioRoute {
    EARPIECE,
    LOUDSPEAKER,
    HEADSET,
    BLUETOOTH;

    companion object {
        /**
         * Convert a platform [CallAudioState] route integer to an [AudioRoute], or null if unrecognised.
         */
        fun fromPlatformRoute(route: Int): AudioRoute? = when (route) {
            CallAudioState.ROUTE_EARPIECE -> EARPIECE
            CallAudioState.ROUTE_BLUETOOTH -> BLUETOOTH
            CallAudioState.ROUTE_WIRED_HEADSET -> HEADSET
            CallAudioState.ROUTE_SPEAKER -> LOUDSPEAKER
            else -> null
        }

        /**
         * Extract all supported [AudioRoute]s from a platform [CallAudioState.getSupportedRouteMask].
         */
        fun fromSupportedRouteMask(mask: Int): Set<AudioRoute> {
            val routes = mutableSetOf<AudioRoute>()
            if ((mask and CallAudioState.ROUTE_EARPIECE) != 0) routes.add(EARPIECE)
            if ((mask and CallAudioState.ROUTE_BLUETOOTH) != 0) routes.add(BLUETOOTH)
            if ((mask and CallAudioState.ROUTE_WIRED_HEADSET) != 0) routes.add(HEADSET)
            if ((mask and CallAudioState.ROUTE_SPEAKER) != 0) routes.add(LOUDSPEAKER)
            return routes
        }
    }
}

/**
 * Convert this [AudioRoute] to the corresponding [CallAudioState] constant.
 */
fun AudioRoute.toPlatformRoute(): Int = when (this) {
    AudioRoute.EARPIECE -> CallAudioState.ROUTE_EARPIECE
    AudioRoute.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
    AudioRoute.HEADSET -> CallAudioState.ROUTE_WIRED_HEADSET
    AudioRoute.LOUDSPEAKER -> CallAudioState.ROUTE_SPEAKER
}

/**
 * The call audio route that is currently active, and the set of routes available on the device.
 *
 * A process-wide object matching [OngoingCall], because the screen ([ch.heuscher.gentleconnect.ui.GentleCallScreen])
 * is separated from whichever service owns the call by an Android process boundary: Telecom binds
 * [GentlePhoneCallService] or [ch.heuscher.gentleconnect.voip.GentleConnectionService], while the
 * activity draws the UI.
 *
 * The screen never interacts with a platform object or executes routing commands directly: it
 * observes [current] and [offersChoice], and passes her presses to [choose]; whichever service owns
 * the call supplies a [Controls] instance that does the rest.
 *
 * There is at most one active call on this phone at any time.
 */
object CallAudio {

    private val _current = MutableStateFlow<AudioRoute?>(null)

    /** Null when there is no active call or audio has not yet been routed. */
    val current: StateFlow<AudioRoute?> = _current.asStateFlow()

    private val _available = MutableStateFlow<Set<AudioRoute>>(emptySet())

    /** The routes currently supported and available on this hardware/accessory configuration. */
    val available: StateFlow<Set<AudioRoute>> = _available.asStateFlow()

    private val _offersChoice = MutableStateFlow(false)

    /**
     * Whether the screen shows the two choices, Hörer and Lautsprecher, rather than just naming
     * the route. See [CallAudioPolicy.offersChoice].
     */
    val offersChoice: StateFlow<Boolean> = _offersChoice.asStateFlow()

    /**
     * What changes to the audio route actually do, supplied by whichever service owns the call.
     *
     * An interface rather than Telecom's `setAudioRoute(int)` directly, keeping the screen free
     * from platform telephony dependencies.
     */
    interface Controls {
        /** She chose where the sound goes. Choosing the route already in force changes nothing. */
        fun route(to: AudioRoute)
    }

    private var controls: Controls? = null

    /**
     * She pressed Hörer or Lautsprecher.
     *
     * Goes through the owning service rather than straight to Telecom, because a choice is not a
     * command: a headset put on afterwards still wins, and the choice still holds once it is
     * taken off again. Nothing happens when no call owns the audio.
     */
    fun choose(route: AudioRoute) {
        controls?.route(route)
    }

    /**
     * Called by the active call service as platform audio state changes.
     *
     * @param byHinge whether a folding phone's hinge decides the route, in which case there is no
     *   choice to offer.
     */
    internal fun show(route: AudioRoute?, available: Set<AudioRoute>, controls: Controls?, byHinge: Boolean) {
        this.controls = controls
        _current.value = route
        _available.value = available
        _offersChoice.value = CallAudioPolicy.offersChoice(available, route, byHinge)
    }

    /**
     * Let go, but only if this is still the same owner.
     *
     * Guarded with the same identity check [OngoingCall.release] uses, so an ending call tearing
     * down its audio cannot clear the route state of the next call starting immediately after.
     */
    internal fun release(owner: Controls) {
        if (controls === owner) {
            controls = null
            _current.value = null
            _available.value = emptySet()
            _offersChoice.value = false
        }
    }
}
