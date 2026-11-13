package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Whether the touch in progress landed on the dialpad (keypad keys or backspace), for the restless count.
 *
 * Dialing a telephone number (e.g. "0315550127") on the keypad is naturally a rapid succession of taps:
 * typing 4 or more digits within 2.5 seconds is completely normal, fluent dialing. The restless detector
 * counts four taps within 2.5 seconds as [ch.heuscher.gentleconnect.model.AgitationTrigger.BURST_TAPS]
 * ("schnelles Tippen"), which falsely triggered alarms, captured snapshots, and alerted the caregiver
 * every time she dialed briskly and accurately.
 *
 * Taps on the keypad keys and the backspace button are therefore excluded from [ch.heuscher.gentleconnect.MainActivity]'s
 * `recordTouch` tap count.
 *
 * Note: Keypad taps that are successfully handled remain answered touches for [ch.heuscher.gentleconnect.util.UiTrace],
 * resetting the unanswered touch streak. Any tapping on dead space or disabled buttons remains subject to
 * [ch.heuscher.gentleconnect.model.AgitationTrigger.UNANSWERED_TOUCHES].
 *
 * Main thread only, like the touches themselves.
 */
object KeypadTapZone {
    private var hit = false

    /** A new touch begins; nothing has claimed it yet. */
    fun touchStarted() {
        hit = false
    }

    /** The touch in progress came down on a keypad zone. */
    internal fun markHit() {
        hit = true
    }

    /** True when the touch that just ended came down on a keypad zone. */
    fun landedOnZone(): Boolean = hit
}

/**
 * Marks this composable as part of the keypad tap zone so rapid dialing keystrokes do not count toward
 * burst-tap agitation alarms.
 */
fun Modifier.keypadZone(): Modifier = composed {
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            KeypadTapZone.markHit()
        }
    }
}
