package ch.heuscher.gentleconnect.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * The largest type size, at or below the size [base] asks for, that fits every one of [texts] on
 * a single line in [available] width — measured, not guessed.
 *
 * Words on this phone are large on purpose and the screens they sit on are not all the same
 * width: a folding phone is 640 points across open and about 330 closed, and her system font
 * scale multiplies whatever the app asks for on top of that. A single word that does not fit
 * either wraps in the middle of itself ("Zuhaus/e") or is cut off with an ellipsis
 * ("Nachric…") — both were on the cover screen, and both take away the one word that says
 * where she is or where she is going. Shrinking the word to fit is the only one of the three
 * that still tells her.
 *
 * It is measured with a real [androidx.compose.ui.text.TextMeasurer] rather than estimated from
 * character counts, because the thing that decides whether it fits — her font scale, the
 * translation, the typeface — is not knowable from the string.
 *
 * @param texts every string that has to fit in this width; they all come back at one size, so
 *   that four buttons beside each other cannot end up with four different sizes of word on them.
 * @param minSize the size below which shrinking stops and the text is allowed to be cut instead.
 */
@Composable
fun fitToWidth(
    texts: List<String>,
    available: Dp,
    base: TextStyle,
    minSize: TextUnit
): TextStyle {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val budgetPx = with(density) { available.coerceAtLeast(0.dp).toPx() }

    return remember(texts, base, budgetPx, minSize, density, measurer) {
        if (budgetPx <= 0f || !base.fontSize.isSp) return@remember base
        val widest = texts.maxOfOrNull { text ->
            measurer.measure(
                text = AnnotatedString(text),
                style = base,
                maxLines = 1,
                softWrap = false
            ).size.width
        } ?: 0
        if (widest <= 0 || widest <= budgetPx) return@remember base

        // In sp as plain numbers: TextUnit's own coerceAtLeast is a different import away and
        // reads no better than max() does.
        val factor = budgetPx / widest
        val floorSp = minSize.value
        base.copy(
            fontSize = max(base.fontSize.value * factor, floorSp).sp,
            // The line box comes down with the type. Left at its full height it would go on
            // reserving room for a size of word that is no longer there.
            lineHeight = if (base.lineHeight.isSp) {
                max(base.lineHeight.value * factor, floorSp).sp
            } else {
                base.lineHeight
            }
        )
    }
}
