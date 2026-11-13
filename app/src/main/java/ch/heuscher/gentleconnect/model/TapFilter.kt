package ch.heuscher.gentleconnect.model

import kotlin.math.hypot

/**
 * Decides which touches were taps, for [AgitationTracker.onTap].
 *
 * A touch used to count the moment the finger landed. Scrolling through her messages or her
 * pictures is a string of such landings — a flick every half second — and four of them lit the
 * restless beacon for somebody who was only reading. A tap is now a finger that comes down and
 * lifts again without having travelled further than [slopPx], with no second finger joining in
 * (a pinch is not a tap either). Everything else — a drag, a scroll, a fling, a cancelled
 * touch — is left out.
 *
 * Counted at the lift rather than the landing, so a tap is timed by when it finished; for
 * counting four within 2.5 s that difference is a fraction of a tap.
 */
class TapFilter(private val slopPx: Float) {
    private var downX = 0f
    private var downY = 0f
    private var candidate = false

    /** A first finger landed. */
    fun onDown(x: Float, y: Float) {
        downX = x
        downY = y
        candidate = true
    }

    /** The finger moved; beyond [slopPx] from where it landed, this touch is no longer a tap. */
    fun onMove(x: Float, y: Float) {
        if (candidate && hypot(x - downX, y - downY) > slopPx) candidate = false
    }

    /** A second finger joined, or the system took the touch away. Not a tap. */
    fun onCancel() {
        candidate = false
    }

    /** The last finger lifted. True when the whole touch was a tap. */
    fun onUp(x: Float, y: Float): Boolean {
        onMove(x, y)
        val wasTap = candidate
        candidate = false
        return wasTap
    }
}
