package ch.heuscher.gentleconnect.model

/**
 * Pure decision logic for rescuing short touches whose finger moved beyond the touch slop
 * without actually causing the enclosing list to scroll.
 *
 * ## Why (evidence from her Galaxy Fold, 2026-10-01 12:29-12:30)
 *
 * On the Ansehen (LookScreen) album card she made 13 touches in under a minute that UiTrace
 * logged as "drag" (170-750 ms, same spot). None opened the viewer because her tremor moved
 * her finger slightly further than TouchTolerance's expanded 2.5x slop (~20 dp), causing the
 * enclosing scrolling list (or Compose's clickable) to interpret the gesture as a scroll start
 * and cancel the card's click.
 *
 * However, the list did NOT actually scroll (scroll position was identical between touch-down
 * and touch-up). The phone appeared deaf and broken: 13 presses on a picture card produced
 * zero response, driving her into agitated tapping.
 *
 * ## The Decision Rule
 *
 * A touch under [MAX_DURATION_MS] (800 ms) where the finger travelled beyond the slop, but where
 * the enclosing list did not move, is a tap with tremor wobble — not a scroll. We treat it as
 * a valid click.
 *
 * Crucially, if the list *did* scroll ([didListScroll] is true), this is a genuine scroll gesture
 * and must never be intercepted as a click, preserving normal scrolling behavior.
 *
 * ## Lifted on the thing it came down on (2026-11-01, her Fold, 16:40)
 *
 * The travel cap below is not enough on its own. At the bottom of a conversation she pressed
 * "▶ Ansehen" under a film three times in six seconds, each press 120–180 ms long and each one
 * sliding 100–305 dp, while the list, already at its end, never moved. Every one of them was over
 * the cap and so was a "swipe", and the film never opened.
 *
 * So the cap only applies when the finger came up *outside* the thing it went down on. A short
 * press that comes down on a key and comes up on the same key, with nothing on the screen having
 * moved, was aimed at that key however far it slid on the way — which is also how a classic
 * Android button has always behaved.
 */
object ForgivingTap {

    /**
     * Maximum gesture duration in milliseconds to be considered a forgiving tap.
     * Touches held longer than 800 ms are deliberate drags, slow scrolls, or long presses,
     * not trembling taps.
     */
    const val MAX_DURATION_MS = 800L

    /**
     * Maximum travel distance allowed as a multiple of the touch slop threshold.
     * At most 3x the slop (~60 dp with the widened slop). Beyond that it is a swipe,
     * never a click.
     */
    const val MAX_TRAVEL_SLOP_MULTIPLIER = 3f

    /**
     * Evaluates whether a touch gesture should trigger a click.
     *
     * @param durationMs Duration of the gesture from touch-down to touch-up in milliseconds.
     * @param travelPx Maximum distance travelled by the touch point in pixels.
     * @param slopPx The touch slop threshold in pixels.
     * @param didListScroll Whether the enclosing list moved / changed its scroll position.
     * @param endedInside Whether the finger came up inside the element it went down on. Lifts the
     *   travel cap; the duration and no-scroll rules still apply.
     * @return `true` if this gesture should be treated as a click, `false` otherwise.
     */
    fun shouldClick(
        durationMs: Long,
        travelPx: Float,
        slopPx: Float,
        didListScroll: Boolean,
        endedInside: Boolean = false
    ): Boolean {
        // If the list actually moved, it is a scroll, never a click.
        if (didListScroll) return false

        // Touches longer than 800 ms or negative durations are not clicks.
        if (durationMs !in 0..MAX_DURATION_MS) return false

        // Negative travel distance or invalid slop is invalid.
        if (travelPx < 0f || slopPx < 0f) return false

        // Touches with travel beyond 3x slop are swipes — unless they ended where they began.
        if (!endedInside && travelPx > MAX_TRAVEL_SLOP_MULTIPLIER * slopPx) return false

        // Gesture did not scroll the list, duration is within tap window: treat as a click.
        return true
    }

    /**
     * Specifically checks whether this gesture was a "forgiving tap" — meaning the finger
     * travelled further than [slopPx] (so standard click handlers cancelled it), but the list
     * did not actually scroll.
     */
    fun isForgivingClick(
        durationMs: Long,
        travelPx: Float,
        slopPx: Float,
        didListScroll: Boolean,
        endedInside: Boolean = false
    ): Boolean {
        return travelPx > slopPx && shouldClick(durationMs, travelPx, slopPx, didListScroll, endedInside)
    }
}
