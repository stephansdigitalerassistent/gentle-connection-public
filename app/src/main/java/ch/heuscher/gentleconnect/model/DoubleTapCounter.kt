package ch.heuscher.gentleconnect.model

/**
 * Tells two deliberate taps on the same spot apart from a tremor, a drag or a resting finger.
 *
 * ## Why (evidence from her phone, 2026-09-28 19:42 and 19:46)
 *
 * Twice in five minutes she tapped a photograph 3–6 times trying to make it bigger, and every tap
 * landed on nothing. A double tap now magnifies whatever picture or words it lands on, and a second
 * one puts the screen back. Because an accidental double touch from her tremor can only ever
 * magnify or unmagnify, it is harmless — which is the reason it is allowed to do nothing else.
 *
 * The timing and distance rules live here rather than in the gesture code so that they can be
 * tested on the JVM, without Compose:
 * - A tap is short: lifted within [longPressTimeoutMs], never further than [touchSlop] from where
 *   it went down.
 * - The second tap goes down within [doubleTapTimeoutMs] of the first lifting, and not sooner than
 *   [doubleTapMinTimeMs], which is contact bounce rather than a second tap.
 * - Both land within [doubleTapSlop] of each other.
 */
class DoubleTapCounter(
    var doubleTapTimeoutMs: Long = DEFAULT_DOUBLE_TAP_TIMEOUT_MS,
    var doubleTapMinTimeMs: Long = DEFAULT_DOUBLE_TAP_MIN_TIME_MS,
    var longPressTimeoutMs: Long = DEFAULT_LONG_PRESS_TIMEOUT_MS,
    var doubleTapSlop: Float = DEFAULT_DOUBLE_TAP_SLOP,
    var touchSlop: Float = DEFAULT_TOUCH_SLOP
) {
    companion object {
        const val DEFAULT_DOUBLE_TAP_TIMEOUT_MS = 300L
        const val DEFAULT_DOUBLE_TAP_MIN_TIME_MS = 40L
        const val DEFAULT_LONG_PRESS_TIMEOUT_MS = 400L
        const val DEFAULT_TOUCH_SLOP = 18f
        const val DEFAULT_DOUBLE_TAP_SLOP = 36f
    }

    data class Tap(
        val downMs: Long,
        val upMs: Long,
        val x: Float,
        val y: Float
    )

    var firstTap: Tap? = null
        private set

    /**
     * Records a tap that went down at [downMs] and up at [upMs] at ([x], [y]), having moved at most
     * [movedDistance] pixels on the way.
     *
     * Returns true when this tap completes a double tap; the counter is then empty again.
     */
    fun recordTap(
        downMs: Long,
        upMs: Long,
        x: Float,
        y: Float,
        movedDistance: Float = 0f
    ): Boolean {
        // A finger left resting on the glass, or one that slid, is not a tap at all.
        if (upMs - downMs > longPressTimeoutMs || movedDistance > touchSlop) {
            reset()
            return false
        }

        val tap = Tap(downMs = downMs, upMs = upMs, x = x, y = y)
        val prev = firstTap
        if (prev == null) {
            firstTap = tap
            return false
        }

        val gap = downMs - prev.upMs

        // Contact bounce: one touch that the glass reported twice. Neither starts nor ends a pair.
        if (gap in 0 until doubleTapMinTimeMs) return false

        val dx = x - prev.x
        val dy = y - prev.y
        val tooFar = dx * dx + dy * dy > doubleTapSlop * doubleTapSlop

        // Too late or somewhere else: this tap becomes the first of a possible new pair.
        if (gap > doubleTapTimeoutMs || gap < 0 || tooFar) {
            firstTap = tap
            return false
        }

        reset()
        return true
    }

    fun reset() {
        firstTap = null
    }
}
