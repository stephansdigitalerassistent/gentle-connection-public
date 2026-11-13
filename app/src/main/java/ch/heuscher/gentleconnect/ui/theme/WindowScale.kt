package ch.heuscher.gentleconnect.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Physical size scaling for large foldable inner displays (e.g. Samsung Galaxy Z Fold).
 *
 * Sizes only, for a long time: the layout, element order and element count were not allowed to
 * change with window size, because she relies on things being in the place she learned. Faces
 * grow larger so she can recognize her family across the room, while text grows much less so
 * that messages and contact lists still fit on the screen without forcing her to scroll.
 *
 * That rule now has one deliberate exception, decided by the maintainer on 2026-09-05: on a window wide
 * enough for two columns ([isWide]) the home screen and the call screen use both of them. The
 * reason it is worth the re-learning is that the wide window is not the bigger one. Measured on
 * a foldable display with large zoom: open is 640 x 745 dp, folded 334 x 819 dp — opening
 * the phone *loses* 74 dp of height and gains 306 dp of width. A layout that only stacks
 * downwards therefore fits less on the screen she opened the phone to see more on.
 *
 * Ordinary phone windows and phones held sideways stay at exactly 1.0f on both scales.
 */
object GentleWindowScale {
    const val REFERENCE_WIDTH_DP = 400f

    /**
     * The window is wide enough to be worth laying out in two columns.
     *
     * The same threshold the scaling uses, on purpose: the two panes appear exactly when the
     * faces start growing, so there is one size of window she has to know about, not two. Both
     * sides are checked, so a phone held sideways — wide but short — stays one column.
     */
    fun isWide(widthDp: Float, heightDp: Float): Boolean = widthDp >= 600f && heightDp >= 600f

    /**
     * How much to scale faces and photographs on large windows.
     *
     * Capped at 1.6f so portraits stay clear and balanced on screen rather than blowing up to
     * dominate the layout.
     */
    fun faceScale(widthDp: Float, heightDp: Float): Float {
        if (widthDp < 600f || heightDp < 600f) return 1.0f
        val raw = widthDp / REFERENCE_WIDTH_DP
        return raw.coerceIn(1.0f, 1.6f)
    }

    /**
     * How much to scale typography on large windows.
     *
     * Scales far less than faces on purpose: scaling text as hard as photos means fewer lines fit
     * per screen and she has to scroll more, and scrolling is a real cost for her. Faces are the
     * thing worth making bigger; the extra width pays for them.
     */
    fun textScale(widthDp: Float, heightDp: Float): Float {
        if (widthDp < 600f || heightDp < 600f) return 1.0f
        val face = faceScale(widthDp, heightDp)
        return (1.0f + (face - 1.0f) * 0.4f).coerceIn(1.0f, 1.25f)
    }
}

/**
 * CompositionLocal providing the window-dependent face scale factor.
 */
val LocalGentleFaceScale = compositionLocalOf { 1.0f }

/**
 * Scales computed for the current window geometry.
 */
data class GentleWindowScales(
    val faceScale: Float,
    val textScale: Float
)

/**
 * Whether the current window has room for the two-column layouts. See [GentleWindowScale.isWide].
 */
@Composable
fun isWideWindow(): Boolean {
    val configuration = LocalConfiguration.current
    return GentleWindowScale.isWide(
        configuration.screenWidthDp.toFloat(),
        configuration.screenHeightDp.toFloat()
    )
}

/**
 * Reads the current screen configuration and returns the corresponding face and text scales.
 */
@Composable
fun currentWindowScales(): GentleWindowScales {
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp.toFloat()
    val heightDp = configuration.screenHeightDp.toFloat()
    return GentleWindowScales(
        faceScale = GentleWindowScale.faceScale(widthDp, heightDp),
        textScale = GentleWindowScale.textScale(widthDp, heightDp)
    )
}
