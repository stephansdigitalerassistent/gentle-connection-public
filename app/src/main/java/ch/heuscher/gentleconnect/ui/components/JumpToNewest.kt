package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import ch.heuscher.gentleconnect.R
import kotlinx.coroutines.launch

/** Which end of this list the newest thing is at. */
enum class NewestEnd { Top, Bottom }

/**
 * The way back to the newest, in the line the title is on, and only while she is somewhere else.
 *
 * A list of her post going back months is a list she cannot get out of by pushing it: it takes a
 * dozen strokes, each of which can be mistaken for a tap on whatever is under her finger, and the
 * thing she came for is at the end she has just spent a minute scrolling away from. That way back
 * is one press here.
 *
 * **One direction, not two.** This began as a pair of arrows, "newest" and "oldest". Only one of
 * them is ever live — at the newest end the newest is where she already is — and the other leads
 * to something months old that nobody has ever asked to see. A button that does nothing is worse
 * than no button: she presses it and concludes the screen is broken.
 *
 * **Which direction depends on the screen, so it is asked for and never assumed.** Her post and
 * her message list are newest-first, so the newest is *up*; an open conversation is read downwards
 * like a letter, so the newest is *down*. An arrow pointing the wrong way is worse here than no
 * arrow at all, because it is an instruction she would follow.
 *
 * **In the title's place.** Three earlier homes were wrong. Floating in the bottom corner, it
 * covered the right-hand end of the last rows, so a press there opened nothing at all. Given its
 * own strip under the list, it cost a line of every screen — a line taken from what she came to
 * read. In the header beside the "?" it fitted, but only by squeezing the title until "Ansehen"
 * was drawn as "A…" on the folded phone. The title and the way back are never both wanted at once:
 * at the newest end the title names the screen, and the moment she scrolls away from it — when she
 * is reading and no longer needs to be told where she is — the title steps aside for a wide,
 * labelled button she cannot miss. The "?" and the way back never move, on any screen.
 *
 * It jumps rather than animating there. Scrolling a hundred photographs past her at speed is not
 * an explanation of where she has arrived, it is a blur that ends somewhere; the newest thing is a
 * place she recognises the moment it is on the screen.
 *
 * One thing this costs: [caregiverTaps], the five fast taps on the title that open Advanced Mode,
 * is not on the screen while the button is. It comes back at the newest end, which is one press
 * away — and the gesture is a deliberate act by somebody who knows it is there, not something she
 * has to reach in a hurry.
 */
@Composable
fun jumpToNewestOrNull(
    listState: LazyListState,
    itemCount: Int,
    newestAt: NewestEnd
): (@Composable () -> Unit)? {
    // Away from the newest end — the only state in which this means anything. Read from the list
    // rather than from a remembered index, so it is also gone the moment she arrives by pushing
    // the list there herself.
    //
    // Away by more than [NEAR_ENOUGH], not by a pixel. Read as "can scroll at all", the title and
    // this button traded places while a list settled, while a picture finished loading and grew
    // the last bubble, and whenever she stopped a few pixels short of the end — the header
    // blinking under her finger, and a press landing on whichever of the two was there.
    val slack = with(LocalDensity.current) { NEAR_ENOUGH.roundToPx() }
    val awayFromNewest by remember(listState, newestAt, slack) {
        derivedStateOf { distanceFromNewest(listState, newestAt) > slack }
    }
    // Null and not an empty composable, because the caller hands this to the title's place: the
    // title must come back, not be replaced by nothing. See [GentleScreenHeader].
    if (itemCount <= 1 || !awayFromNewest) return null

    return { JumpToNewestButton(listState, itemCount, newestAt) }
}

/**
 * How far, in pixels, the newest end of the list is from being on the screen — 0 when it is.
 *
 * Only exact near the end; anywhere past the next item it is simply "far", which is all the
 * caller asks.
 */
private fun distanceFromNewest(listState: LazyListState, newestAt: NewestEnd): Int {
    val info = listState.layoutInfo
    if (info.totalItemsCount == 0) return 0
    return when (newestAt) {
        NewestEnd.Top ->
            if (listState.firstVisibleItemIndex > 0) Int.MAX_VALUE
            else listState.firstVisibleItemScrollOffset
        NewestEnd.Bottom -> {
            val last = info.visibleItemsInfo.lastOrNull() ?: return 0
            if (last.index < info.totalItemsCount - 1) return Int.MAX_VALUE
            val visibleEnd = info.viewportEndOffset - info.afterContentPadding
            (last.offset + last.size - visibleEnd).coerceAtLeast(0)
        }
    }
}

@Composable
private fun JumpToNewestButton(
    listState: LazyListState,
    itemCount: Int,
    newestAt: NewestEnd,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.jump_newest)
    val spoken = stringResource(R.string.jump_to_newest)

    BoxWithConstraints(modifier = modifier) {
        val style = fitToWidth(
            texts = listOf(label),
            available = (maxWidth - ARROW - BESIDE_THE_ARROW - INSIDE * 2).coerceAtLeast(0.dp),
            base = MaterialTheme.typography.titleLarge,
            minSize = MIN_LABEL
        )

        GentleKey(
            onClick = {
                scope.launch {
                    when (newestAt) {
                        NewestEnd.Top -> listState.scrollToItem(0)
                        // Its end, not its start: the last bubble of a conversation is often
                        // taller than the screen, and stopping at its top would hide the very
                        // words she pressed this to see. See ConversationScreen.
                        NewestEnd.Bottom -> listState.scrollToItem(itemCount - 1, Int.MAX_VALUE)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                // Read out as the whole phrase rather than as the one word that fits on it: the
                // button says "Neuste" because the line is narrow, but nothing about a screen
                // being read aloud is narrow.
                .semantics { contentDescription = spoken },
            face = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = CircleShape,
            minHeight = HEIGHT,
            contentPadding = PaddingValues(horizontal = INSIDE),
            fillWidth = true
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BESIDE_THE_ARROW)
            ) {
                Icon(
                    imageVector = when (newestAt) {
                        NewestEnd.Top -> Icons.Default.KeyboardArrowUp
                        NewestEnd.Bottom -> Icons.Default.KeyboardArrowDown
                    },
                    // The button above says where it goes; a second reading of the same thing
                    // is noise to anybody listening to the screen.
                    contentDescription = null,
                    modifier = Modifier.size(ARROW)
                )
                Text(
                    text = label,
                    style = style,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** As tall as the round buttons at either end of the header, so the line stays a line. */
private val HEIGHT = 56.dp

private val ARROW = 30.dp
private val BESIDE_THE_ARROW = 8.dp
private val INSIDE = 16.dp

/**
 * How far from the newest end still counts as at it: about a line of text. Less, and the header
 * flickers as a list settles; much more, and the newest line can be off the screen with no button.
 */
private val NEAR_ENOUGH = 48.dp

/** Below this the word stops being a word she can read across the room. */
private val MIN_LABEL = 18.sp
