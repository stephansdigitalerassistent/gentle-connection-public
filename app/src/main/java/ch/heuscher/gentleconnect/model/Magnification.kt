package ch.heuscher.gentleconnect.model

/**
 * Making the whole screen bigger, for the days when she cannot read it.
 *
 * The text size in the caregiver settings is the right answer for a permanent change: it reflows,
 * so nothing goes off the edge. This is the other thing — a temporary, physical magnification of
 * whatever is in front of her, for one word on one afternoon, that she can do herself and that
 * nobody has to be telephoned about. It magnifies rather than reflows, which is exactly why it
 * needs a way back: what she is looking at can end up outside the screen, and a screen showing a
 * corner of itself is a broken telephone as far as she is concerned.
 *
 * All the arithmetic is here rather than in the gesture handler, because it is the part that can
 * be wrong in a way that traps her — a clamp that lets the content slide off, or a zoom that
 * drifts away from the finger — and CI runs no UI tests at all.
 */
object Magnification {

    /** Not zoomed. Also the floor: pinching outwards can never make the screen smaller. */
    const val MIN_SCALE = 1f

    /**
     * Four times, which is a great deal.
     *
     * Chosen high on purpose. Somebody who reaches for this is not fine-tuning a layout, they are
     * trying to read one word, and a ceiling that stops just short of readable is the same as no
     * feature. Panning and the way back are what make a large ceiling safe.
     */
    const val MAX_SCALE = 4f

    /**
     * How much a double tap magnifies the screen.
     *
     * Two and a half times is enough to read small print or inspect a face without blowing the
     * whole screen so wide that the rest of the text disappears off into the distance. It is
     * smaller than [MAX_SCALE] (which is four times, for the hardest words), but large enough to
     * be immediately, noticeably larger under her thumb.
     */
    const val DOUBLE_TAP_SCALE = 2.5f

    /**
     * Below this the screen counts as not zoomed: the way back disappears and panning stops.
     *
     * Slightly above 1 rather than exactly 1, because a pinch almost never lands on a round
     * number, and a screen left at 1.004 would keep offering to undo a magnification that is not
     * visibly there.
     */
    const val ZOOMED_THRESHOLD = 1.02f

    /** Whether anything is magnified — whether the way back needs to be on the screen. */
    fun isZoomed(scale: Float): Boolean = scale > ZOOMED_THRESHOLD

    /** A pinch, clamped. Gestures produce wild factors when a finger is lost mid-way. */
    fun scaleBy(scale: Float, factor: Float): Float {
        if (!factor.isFinite() || factor <= 0f) return scale
        return (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /**
     * Computes the scale and offsets to zoom straight to [DOUBLE_TAP_SCALE] centered on the spot she tapped.
     *
     * Without centering on what she tapped, a double tap would zoom into the top-left corner of the
     * screen and throw the word or picture she was looking at far out of view. We calculate where
     * the content must shift so that the tapped point stays directly under her finger, starting
     * from an unzoomed screen (scale 1, offset 0), and then clamp the offsets so no empty borders
     * are exposed.
     */
    fun zoomInAt(
        pointX: Float,
        pointY: Float,
        width: Float,
        height: Float
    ): ZoomState {
        val targetScale = DOUBLE_TAP_SCALE
        val rawX = offsetAfterZoom(offset = 0f, centroid = pointX, fromScale = MIN_SCALE, toScale = targetScale)
        val rawY = offsetAfterZoom(offset = 0f, centroid = pointY, fromScale = MIN_SCALE, toScale = targetScale)
        val clampedX = clampOffset(rawX, targetScale, width)
        val clampedY = clampOffset(rawY, targetScale, height)
        return ZoomState(scale = targetScale, offsetX = clampedX, offsetY = clampedY)
    }

    /**
     * Where the content has to move so that the point under her fingers stays under her fingers.
     *
     * Without this the screen zooms towards its top-left corner, and what she was looking at
     * slides away from her while she is still pinching it — which reads as the phone fighting her
     * rather than as a zoom.
     *
     * The content is drawn scaled about its top-left corner and then translated, so a content
     * point `p` appears at `offset + p * scale`. Holding the point under `centroid` fixed across a
     * scale change gives the expression below.
     */
    fun offsetAfterZoom(
        offset: Float,
        centroid: Float,
        fromScale: Float,
        toScale: Float
    ): Float {
        if (fromScale <= 0f) return offset
        return centroid - (centroid - offset) * (toScale / fromScale)
    }

    /**
     * Keeps the magnified screen covering the screen.
     *
     * The one rule that matters: she must never be able to push the content far enough that she is
     * looking at nothing. At scale 1 the only legal offset is 0 — a screen that is not magnified
     * cannot be dragged around at all, so an accidental two-finger swipe leaves everything where
     * it was.
     */
    fun clampOffset(offset: Float, scale: Float, viewport: Float): Float {
        if (viewport <= 0f) return 0f
        val slack = viewport * (scale - 1f)
        if (slack <= 0f) return 0f
        return offset.coerceIn(-slack, 0f)
    }
}

/**
 * The scale factor and viewport offsets resulting from a zoom operation.
 */
data class ZoomState(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float
)

