package ch.heuscher.gentleconnect.model

import kotlin.math.roundToInt

/**
 * Bounds and scaling arithmetic for photographs she takes herself.
 *
 * Phone cameras produce enormous 12 to 50 megapixel images. Sending those raw over mobile
 * data takes a long time, burns bandwidth, and easily exhausts memory. Scaling the longer side
 * down to [LONG_EDGE] keeps the picture completely sharp on any phone, tablet or television
 * her family views it on, while reducing the transfer size to a few hundred kilobytes.
 *
 * Kept strictly pure with no Android dependencies so that all arithmetic can be verified by
 * unit tests on the host JVM.
 */
object OwnPhotoSize {
    const val LONG_EDGE: Int = 1600

    /**
     * Calculates the target (width, height) scaled so that the longer edge is at most [longEdge].
     *
     * - Scales so the longer side is at most [longEdge].
     * - Never upscales if the photo is already within [longEdge].
     * - Preserves aspect ratio.
     * - Never returns 0 for either dimension, even for extreme aspects.
     */
    fun target(width: Int, height: Int, longEdge: Int = LONG_EDGE): Pair<Int, Int> {
        val safeW = maxOf(1, width)
        val safeH = maxOf(1, height)
        val safeLongEdge = maxOf(1, longEdge)
        val maxDimension = maxOf(safeW, safeH)

        if (maxDimension <= safeLongEdge) {
            return safeW to safeH
        }

        val factor = safeLongEdge.toDouble() / maxDimension
        val targetW = (safeW * factor).roundToInt().coerceAtLeast(1)
        val targetH = (safeH * factor).roundToInt().coerceAtLeast(1)
        return targetW to targetH
    }
}
