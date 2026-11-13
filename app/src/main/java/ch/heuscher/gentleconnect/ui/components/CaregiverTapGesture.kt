package ch.heuscher.gentleconnect.ui.components

import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput

/** How many fast taps reveal caregiver affordances or toggle advanced mode. */
const val CAREGIVER_TAP_COUNT = 5

/** All [CAREGIVER_TAP_COUNT] taps must land within this window, measured from the first. */
const val CAREGIVER_TAP_WINDOW_MS = 1_500L

/**
 * Pure arithmetic counter for caregiver tap gestures.
 *
 * Extracted so that the timing and threshold rules can be tested in pure JVM unit tests
 * without instantiating Compose pointer input pipelines.
 */
class CaregiverTapCounter(
    val targetCount: Int = CAREGIVER_TAP_COUNT,
    val windowMs: Long = CAREGIVER_TAP_WINDOW_MS
) {
    var tapCount: Int = 0
        private set
    var windowStartMs: Long = 0L
        private set

    /**
     * Records a tap occurring at [nowMs].
     *
     * Returns true if this tap completes the gesture ([targetCount] taps within [windowMs]).
     * When completed, the counter is reset to 0.
     */
    fun recordTap(nowMs: Long): Boolean {
        if (nowMs - windowStartMs > windowMs) {
            windowStartMs = nowMs
            tapCount = 1
        } else {
            tapCount++
        }
        return if (tapCount >= targetCount) {
            reset()
            true
        } else {
            false
        }
    }

    fun reset() {
        tapCount = 0
        windowStartMs = 0L
    }
}

/**
 * Whether the touch in progress landed on a caregiver tap zone, for the restless count.
 *
 * The caregiver gesture is five taps inside 1.5 s, and the restless count lights the beacon at
 * four inside 2.5 s — so every caregiver opening the settings by the clock also filed "schnelles
 * Tippen" in her log, took three screenshots and lit the beacon for five minutes. So taps on
 * these zones are not counted at all. That costs her own tapping on the clock or a title, which
 * is a small loss: they are not buttons, and the burst count is for a button that does not
 * answer. Discarding the alarm after the gesture completed was the other way, and worse — by
 * the fifth tap the log entry, the screenshots and the website alert have already gone out.
 *
 * [MainActivity.dispatchTouchEvent][ch.heuscher.gentleconnect.MainActivity] calls [touchStarted]
 * before handing a finger-down to Compose, which delivers it to [caregiverTaps] synchronously
 * inside that same dispatch; at the lift, [landedOnZone] says whether it was one of ours.
 * Main thread only, like the touches themselves.
 */
object CaregiverTapZone {
    private var hit = false

    /** A new touch begins; nothing has claimed it yet. */
    fun touchStarted() {
        hit = false
    }

    /** The touch in progress came down on a caregiver zone. */
    internal fun markHit() {
        hit = true
    }

    /** True when the touch that just ended came down on a caregiver zone. */
    fun landedOnZone(): Boolean = hit
}

/**
 * Reusable modifier for the 5-taps-within-1500ms caregiver gesture.
 *
 * ## Why pointer-DOWN rather than a tap gesture
 *
 * `detectTapGestures` was tried here first and never fired inside scrolling containers: it needs
 * an unconsumed down and an uncancelled up, and a `verticalScroll` or `LazyColumn` claims either
 * the moment her finger wobbles. Counting pointer-DOWN events via `awaitFirstDown(requireUnconsumed = false)`
 * is unambiguous and ignores scroll interference.
 *
 * ## Why 5 taps at speed
 *
 * A hold is deliberately avoided because resting a finger on the screen happens accidentally.
 * Five deliberate taps at speed are something an elderly user with cognitive decline will not
 * produce while reading or navigating, but a visiting carer can trigger reliably.
 */
fun Modifier.caregiverTaps(onFired: () -> Unit): Modifier = composed {
    // Keyed on Unit, and the callback reached through `rememberUpdatedState`. Keying the pointer
    // input on the lambda instead looks tidier and silently breaks the gesture: the clock header
    // recomposes once a second, which hands this a new lambda every second, which tears down the
    // pointer-input coroutine and takes the half-finished tap count with it. Five taps spanning a
    // second boundary would then never add up, and the gesture would work only by luck.
    val fire by rememberUpdatedState(onFired)
    val counter = remember { CaregiverTapCounter() }

    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            CaregiverTapZone.markHit()
            if (counter.recordTap(System.currentTimeMillis())) {
                Log.d("CaregiverTapGesture", "caregiver tap gesture completed")
                fire()
            }
        }
    }
}
