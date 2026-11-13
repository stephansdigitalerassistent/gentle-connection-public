package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.model.ScrollIndicator

/**
 * How far down something she is, drawn beside it.
 *
 * Not the system's scroll bar, which is a hairline that fades out a second after you stop
 * moving — by the time she has looked away from her thumb and up at the screen it is gone, and
 * something that is only visible while you are already moving cannot tell you where you have
 * arrived. This one is always there while there is anything to scroll, and it is wide enough to
 * see without looking for it.
 *
 * It does not accept a drag. A scroll bar you can grab is a small target with a large effect,
 * and getting it slightly wrong throws her a thousand names from where she was. She scrolls by
 * pushing the list, which she already does; this only tells her where that has got her.
 */
@Composable
fun GentleScrollbar(
    bar: ScrollIndicator.Bar?,
    modifier: Modifier = Modifier,
    width: Dp = BAR_WIDTH
) {
    // Nothing to scroll, nothing drawn — not an empty track, which would be a permanent mark on
    // the screen that never means anything.
    if (bar == null) return

    BoxWithConstraints(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .offset(y = maxHeight * bar.startFraction)
                .height(maxHeight * bar.thumbFraction)
                .fillMaxWidth()
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.secondary)
        )
    }
}

/**
 * Horizontal counterpart of [GentleScrollbar], showing horizontal position across a widened or magnified canvas.
 */
@Composable
fun GentleHorizontalScrollbar(
    bar: ScrollIndicator.Bar?,
    modifier: Modifier = Modifier,
    height: Dp = BAR_WIDTH
) {
    if (bar == null) return

    BoxWithConstraints(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .offset(x = maxWidth * bar.startFraction)
                .width(maxWidth * bar.thumbFraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.secondary)
        )
    }
}

/** The bar for a block of text, or null when the text fits. */
@Composable
fun scrollbarFor(state: ScrollState): ScrollIndicator.Bar? =
    ScrollIndicator.forText(
        scrollValue = state.value,
        maxValue = state.maxValue,
        viewportSize = state.viewportSize
    )

/** The bar for a list, or null when the list fits. */
@Composable
fun scrollbarFor(state: LazyListState): ScrollIndicator.Bar? {
    val info = state.layoutInfo
    return ScrollIndicator.forList(
        firstVisibleIndex = state.firstVisibleItemIndex,
        visibleCount = info.visibleItemsInfo.size,
        totalCount = info.totalItemsCount
    )
}

/**
 * Wide enough to see at arm's length. The system bar is about 4.dp and this is not for someone
 * who is looking for it.
 */
private val BAR_WIDTH = 8.dp

/**
 * The strip down the right-hand edge that belongs to the scroll bar, and the padding everything
 * else puts back so that only the bar lives in it.
 *
 * A screen sets its end padding to [SCROLLBAR_LANE] and gives [OUTSIDE_THE_LANE] back to its
 * header, its search box and anything else that is not the scrolling thing. The rows then end up
 * exactly where they were before there was a bar — this costs no width at all — and the bar sits
 * the same distance from the edge on every screen, so it reads as one thing rather than as a
 * different mark in a different place each time.
 */
val SCROLLBAR_LANE = 6.dp
val OUTSIDE_THE_LANE = 14.dp
