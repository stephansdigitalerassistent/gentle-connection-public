package ch.heuscher.gentleconnect.model

/**
 * Widens the touch slop so her tremor does not cancel clicks and turn taps into accidental drags.
 *
 * ## Why (evidence from her phone, 2026-09-28 19:42-19:44)
 *
 * [ch.heuscher.gentleconnect.util.UiTrace] labels a touch "drag" when the finger moved more than
 * `viewConfiguration.touchSlop`. In two minutes she tried at least 8 times to open a conversation
 * from the Messages list and from Home. Every one was logged as a short "drag" (47-600 ms) on the row,
 * and NONE opened anything: the scrolling list claimed the touch as the start of a scroll because her
 * finger wobbled past the platform touch slop (~8 dp), which cancels the row's click. The ones logged
 * as "tap" opened fine. That silent failure is what drives her into bursts of tapping.
 *
 * ## The cost
 *
 * A scroll starts ~12 dp later, which she will not notice, and pager swipes need a bit more travel.
 * That is a trivial trade-off against a phone that appears deaf to her touch.
 */
object TouchTolerance {

    /**
     * How much we expand the platform touch slop.
     *
     * Two and a half times turns the platform's default ~8 dp slop into ~20 dp. That gives her
     * wobbling finger enough room to touch down and lift without crossing the drag threshold
     * that cancels the row click and begins a scroll.
     */
    const val SLOP_FACTOR = 2.5f

    /**
     * Calculates the expanded touch slop in pixels for a given [platformSlopPx].
     *
     * Returns `platformSlopPx * SLOP_FACTOR`, never less than the platform value itself.
     */
    fun slopFor(platformSlopPx: Float): Float {
        if (!platformSlopPx.isFinite() || platformSlopPx <= 0f) return platformSlopPx
        return maxOf(platformSlopPx, platformSlopPx * SLOP_FACTOR)
    }
}
