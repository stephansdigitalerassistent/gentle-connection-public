package ch.heuscher.gentleconnect.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The colour of "not opened yet": the frame round a picture, and the badge counting them.
 *
 * Amber because it is the one warm colour nothing else on her screens uses — green is every button
 * she presses and red is a call being ended — so it can mean this and only this.
 */
val UnseenAmber = Color(0xFFFFB300)

/**
 * An amber frame and a soft amber halo round a picture she has not opened in the viewer.
 *
 * The first version was a coloured shadow, and on the phone it was barely there: elevation shadows
 * are drawn faint on purpose, and below Android 9 their colour is ignored altogether. So this paints
 * its own — widening rings of amber outside the edge, fading as they go, and a solid line on the
 * edge itself, drawn over the picture so that no photograph, however bright, can swallow it. The
 * same on every telephone.
 *
 * Still nothing that moves: a frame that is there, or is not. Stored only on this phone and never
 * reported to whoever posted.
 *
 * @param corner the radius the picture itself is clipped to, so the frame follows its edge exactly
 *   rather than running a few pixels beside it.
 */
fun Modifier.unseenGlow(unseen: Boolean, corner: Dp): Modifier =
    if (!unseen) this else drawWithContent {
        // A picture that has not been read off the disk yet is nothing wide, and a frame round
        // nothing is an amber dot.
        if (size.minDimension <= 0f) {
            drawContent()
            return@drawWithContent
        }

        val radius = corner.toPx()
        HALO.forEach { (spread, alpha) ->
            val out = spread.toPx()
            drawRoundRect(
                color = UnseenAmber.copy(alpha = alpha),
                topLeft = Offset(-out, -out),
                size = Size(size.width + 2 * out, size.height + 2 * out),
                cornerRadius = CornerRadius(radius + out)
            )
        }

        drawContent()

        val line = FRAME.toPx()
        drawRoundRect(
            color = UnseenAmber,
            topLeft = Offset(line / 2, line / 2),
            size = Size(size.width - line, size.height - line),
            cornerRadius = CornerRadius((radius - line / 2).coerceAtLeast(0f)),
            style = Stroke(width = line)
        )
    }

/** The solid line on the picture's edge. */
private val FRAME = 4.dp

/**
 * The halo outside it: how far each ring reaches past the edge, and how strongly it is painted.
 * They overlap, so the amber is densest at the edge and thins out to nothing ten dp away — which
 * fits inside the sixteen dp of card round every picture, so nothing clips it.
 */
private val HALO = listOf(10.dp to 0.10f, 6.dp to 0.16f, 3.dp to 0.24f)
