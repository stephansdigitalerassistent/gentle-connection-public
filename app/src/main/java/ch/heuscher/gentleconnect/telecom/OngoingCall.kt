package ch.heuscher.gentleconnect.telecom

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The call that is happening, and the two things that can be done to it.
 *
 * A process-wide object rather than something handed down through the app, because the two ends
 * of it are started independently by Android and never see each other: Telecom binds
 * [GentlePhoneCallService], and the notification's full-screen intent starts
 * [PhoneCallActivity]. There is at most one call on this phone at a time — this app has no
 * concept of a second line, no hold and no swapping — so a single slot is not a simplification
 * that will need undoing later, it is the design.
 *
 * [answer] and [hangUp] do nothing when no service is bound. That is the honest behaviour for a
 * screen that has outlived its call: the alternative is a crash at the moment she presses the red
 * circle, which is the moment she least wants one.
 */
object OngoingCall {

    private val _current = MutableStateFlow<PhoneCall?>(null)

    /** Null when the telephone is not doing anything. */
    val current: StateFlow<PhoneCall?> = _current.asStateFlow()

    /**
     * What the buttons actually do, supplied by whichever service is holding the real call.
     *
     * An interface rather than the platform's `Call`, so that the screen has no way to reach any
     * of the other twenty things a `Call` can be told to do.
     */
    interface Controls {
        fun answer()
        fun hangUp()
        fun silence()
    }

    private var controls: Controls? = null

    fun answer() {
        controls?.answer()
    }

    fun hangUp() {
        controls?.hangUp()
    }
    
    fun silence() {
        controls?.silence()
    }

    /** Called by the service as the call moves; null when it is over and the screen should go. */
    internal fun show(call: PhoneCall?, controls: Controls?) {
        this.controls = controls
        _current.value = call
    }

    /**
     * Let go, but only if this is still the same owner.
     *
     * Guarded because a call ending and the next one starting can overlap by a few milliseconds,
     * and an unguarded clear would let the finished call take the new one's screen down with it.
     */
    internal fun release(owner: Controls) {
        if (controls === owner) {
            controls = null
            _current.value = null
        }
    }
}
