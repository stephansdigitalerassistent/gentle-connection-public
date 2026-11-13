package ch.heuscher.gentleconnect.ui.components

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalViewConfiguration
import ch.heuscher.gentleconnect.model.ForgivingTap
import ch.heuscher.gentleconnect.util.UiTrace

/**
 * Provides a provider lambda returning an integer/hash representing the current scroll position
 * of the enclosing list. If the value changes between down and up, the list moved.
 */
val LocalScrollPositionProvider = compositionLocalOf<(() -> Int)?> { null }

/**
 * A click modifier that treats short touches (< 800 ms) as clicks even if the finger travelled
 * further than the touch slop, provided the enclosing list did NOT actually scroll.
 *
 * Designed for photo cards and keys inside scrollable lists ([ch.heuscher.gentleconnect.ui.LookScreen],
 * [ch.heuscher.gentleconnect.ui.HomeScreen]).
 *
 * When standard click handlers (e.g. [androidx.compose.foundation.clickable] or [combinedClickable])
 * cancel a touch because her trembling finger wobbled past the slop (~20 dp), this modifier
 * checks [ForgivingTap.shouldClick] on the `Final` pass. If the touch was not already answered
 * and the list did not move, it rescues the click — at any travel when the finger came up inside
 * this element, otherwise only up to [ForgivingTap.MAX_TRAVEL_SLOP_MULTIPLIER] times the slop.
 */
fun Modifier.forgivingClick(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    if (!enabled) return@composed this

    val currentOnClick by rememberUpdatedState(onClick)
    val viewConfig = LocalViewConfiguration.current
    val scrollProvider = LocalScrollPositionProvider.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    this
        .onGloballyPositioned { coordinates = it }
        .pointerInput(enabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val startTime = SystemClock.uptimeMillis()
                val startPos = down.position
                val startScroll = scrollProvider?.invoke()
                val startRootPos = coordinates?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                var maxTravelPx = 0f

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) break
                    val dist = (change.position - startPos).getDistance()
                    if (dist > maxTravelPx) maxTravelPx = dist
                    if (!change.pressed) break
                }

                // Await Final pass so standard clickables and lists have processed the up event
                val upEvent = awaitPointerEvent(PointerEventPass.Final)
                val upChange = upEvent.changes.firstOrNull { it.id == down.id }
                val durationMs = SystemClock.uptimeMillis() - startTime
                val endScroll = scrollProvider?.invoke()
                val endRootPos = coordinates?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero

                val didScroll = (startScroll != null && endScroll != null && startScroll != endScroll) ||
                    ((endRootPos - startRootPos).getDistance() > 1f)

                // If touch was not already answered by a standard click or scroll:
                if (!UiTrace.isTouchAnswered && upChange != null && !upChange.isConsumed) {
                    val up = upChange.position
                    val endedInside = up.x in 0f..size.width.toFloat() && up.y in 0f..size.height.toFloat()
                    val shouldClick = ForgivingTap.isForgivingClick(
                        durationMs = durationMs,
                        travelPx = maxTravelPx,
                        slopPx = viewConfig.touchSlop,
                        didListScroll = didScroll,
                        endedInside = endedInside
                    )
                    if (shouldClick) {
                        UiTrace.markAnswered()
                        upChange.consume()
                        currentOnClick()
                    }
                }
            }
        }
}
