package ch.heuscher.gentleconnect.telecom

import android.content.Context
import android.telecom.CallAudioState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where the sound of the current call goes, for whichever service owns the call.
 *
 * The ordinary telephone ([GentlePhoneCallService]) and Threema calls
 * ([ch.heuscher.gentleconnect.voip.GentleCallConnection]) reach Telecom through different classes
 * with the same two methods, so the part that decides lives here once: her choice from the call
 * screen, the hinge of a folding phone, [CallAudioPolicy] over the two, and the screen blanking that
 * follows the earpiece.
 *
 * @param audioState the call's current platform audio state, or null when there is no call.
 * @param setRoute asks Telecom to move the sound.
 */
class CallAudioRouting(
    context: Context,
    private val audioState: () -> CallAudioState?,
    private val setRoute: (AudioRoute) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val screenOff = EarProximity(context)
    private val hinge = FoldHinge(context)
    private var hingeJob: Job? = null

    /**
     * Whether this call is being routed yet.
     *
     * The owning service starts it once the call has stopped ringing at her end — see
     * `beginAudioHandlingUnlessRinging` there for why a ringing call must be left alone.
     */
    private var started = false

    /** What she last pressed on this call, or null if she has not pressed either. */
    private var chosen: AudioRoute? = null

    val controls = object : CallAudio.Controls {
        override fun route(to: AudioRoute) {
            chosen = to
            if (started) apply()
        }
    }

    /** Start routing. Safe to call repeatedly; only the first call does anything. */
    fun start() {
        if (started) return
        started = true
        chosen = null
        hinge.start()
        hingeJob?.cancel()
        hingeJob = scope.launch {
            hinge.closed.collect { apply() }
        }
        audioState()?.let { publish(it) }
        apply()
    }

    /** For the owner's `onCallAudioStateChanged`. */
    fun onAudioStateChanged(state: CallAudioState) {
        // Published either way, so the word on her screen is always the truth. Acted on only
        // once the call is hers.
        publish(state)
        if (started) apply(state)
    }

    /** Let the sensor and the wake lock go. Must survive being called when nothing was started. */
    fun stop() {
        started = false
        chosen = null
        CallAudio.release(controls)
        hingeJob?.cancel()
        hingeJob = null
        hinge.stop()
        screenOff.stop()
    }

    private fun publish(state: CallAudioState) {
        CallAudio.show(
            route = AudioRoute.fromPlatformRoute(state.route),
            available = AudioRoute.fromSupportedRouteMask(state.supportedRouteMask),
            controls = controls,
            byHinge = hinge.present
        )
    }

    /**
     * Re-decide and apply.
     *
     * Called whenever the platform audio state changes, whenever she presses a choice or folds the
     * phone, and when the call becomes active (Android resets routes during state transitions).
     */
    private fun apply(state: CallAudioState? = audioState()) {
        if (state == null) return
        val current = AudioRoute.fromPlatformRoute(state.route)
        val desired = CallAudioPolicy.decide(
            available = AudioRoute.fromSupportedRouteMask(state.supportedRouteMask),
            current = current,
            wanted = CallAudioPolicy.wanted(hinge.closed.value, chosen)
        )
        if (desired != null) {
            setRoute(desired)
        }
        // From the route actually in force, not the one just asked for: the request may be
        // refused, and the next audio state change comes back through here either way.
        screenOff.blankAtEar(current == AudioRoute.EARPIECE)
    }
}
