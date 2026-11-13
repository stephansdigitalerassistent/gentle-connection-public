package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Whether the touch in progress landed on a face in the reaction row, for the restless count.
 *
 * Tapping a reaction (e.g. laughing face 😂 or heart ❤️) repeatedly in quick succession is a natural
 * expression of joy, humor, or emphasis. The restless detector counts four taps within 2.5 seconds as
 * [ch.heuscher.gentleconnect.model.AgitationTrigger.BURST_TAPS] ("schnelles Tippen"), which falsely
 * triggered alarms, took snapshots, and alerted the caregiver when she simply tapped a face
 * repeatedly in amusement.
 *
 * Taps on the faces are therefore excluded from [ch.heuscher.gentleconnect.MainActivity]'s
 * `recordTouch` tap count.
 *
 * Note: Handled reaction taps remain answered touches for [ch.heuscher.gentleconnect.util.UiTrace],
 * resetting any unanswered touch streak. Any tapping on dead space or disabled buttons remains subject
 * to [ch.heuscher.gentleconnect.model.AgitationTrigger.UNANSWERED_TOUCHES].
 *
 * [EmojiChoiceDialog] is not marked. A dialog is its own window, so its touches never reach
 * `MainActivity.dispatchTouchEvent` and are not counted anyway, and one press closes it.
 *
 * Main thread only, like the touches themselves.
 */
object ReactionTapZone {
    private var hit = false

    /** A new touch begins; nothing has claimed it yet. */
    fun touchStarted() {
        hit = false
    }

    /** The touch in progress came down on a reaction emoji zone. */
    internal fun markHit() {
        hit = true
    }

    /** True when the touch that just ended came down on a reaction emoji zone. */
    fun landedOnZone(): Boolean = hit
}

/**
 * Marks this composable as part of the reaction tap zone so rapid emoji taps do not count toward
 * burst-tap agitation alarms.
 */
fun Modifier.reactionZone(): Modifier = composed {
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            ReactionTapZone.markHit()
        }
    }
}
