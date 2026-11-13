package ch.heuscher.gentleconnect.ui.components

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import ch.heuscher.gentleconnect.model.DoubleTapCounter
import ch.heuscher.gentleconnect.util.UiTrace

private class PositionHolder {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Reusable modifier that magnifies the screen at the spot tapped when she double taps.
 *
 * ## Why an observer that never consumes
 *
 * Single touches on message text, letters or photo captions must still do whatever they were
 * already doing: scrolling the conversation, triggering read-aloud, or allowing text selection.
 * An active gesture detector that claims touches claims them from the scrolling container or
 * child elements.
 *
 * By listening with `requireUnconsumed = false` and NEVER calling `consume()`, single taps,
 * long presses, and drags pass untouched to whatever is underneath. Only when two short, still
 * taps land within the double-tap window at the same location does this trigger magnification.
 */
fun Modifier.doubleTapToMagnify(): Modifier = composed {
    val currentMagnifier by rememberUpdatedState(LocalMagnifier.current)
    val holder = remember { PositionHolder() }
    val counter = remember { DoubleTapCounter() }

    this
        .onGloballyPositioned { holder.coordinates = it }
        .pointerInput(Unit) {
            counter.doubleTapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis
            counter.doubleTapMinTimeMs = viewConfiguration.doubleTapMinTimeMillis
            counter.longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
            counter.touchSlop = viewConfiguration.touchSlop
            counter.doubleTapSlop = viewConfiguration.touchSlop * 2f

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Consumed already means PinchToZoom has the screen frozen and is magnified. Its own
                // double tap puts the screen back; counting the same two taps here as well would
                // magnify it again the instant it had been reset.
                if (down.isConsumed) {
                    counter.reset()
                    return@awaitEachGesture
                }
                val downTime = SystemClock.uptimeMillis()
                val downPos = down.position
                var maxDistance = 0f
                var upTime = 0L

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val dist = (change.position - downPos).getDistance()
                    if (dist > maxDistance) {
                        maxDistance = dist
                    }
                    if (!change.pressed) {
                        upTime = SystemClock.uptimeMillis()
                        break
                    }
                }

                if (upTime > 0L) {
                    val completed = counter.recordTap(
                        downMs = downTime,
                        upMs = upTime,
                        x = downPos.x,
                        y = downPos.y,
                        movedDistance = maxDistance
                    )
                    if (completed) {
                        val coords = holder.coordinates?.takeIf { it.isAttached }
                        val rootPos = coords?.localToRoot(downPos) ?: downPos
                        currentMagnifier.zoomAt(rootPos)
                        UiTrace.event("double tap -> magnify")
                    }
                }
            }
        }
}

/**
 * The same magnification, for a picture that already listens for taps of its own.
 *
 * A photograph in a conversation opens full screen on one tap, and a page in the album viewer turns
 * on one tap. Both already run `detectTapGestures`, which tells a single tap from a double one by
 * itself, so there the observer above would count the same taps twice. They take this instead:
 * [modifier] goes on the picture so it knows where on the screen it is, and [zoomAt] is what they
 * hand `detectTapGestures` as its `onDoubleTap`.
 */
class ZoomAtTap internal constructor(private val magnifier: Magnifier) {
    private var coordinates: LayoutCoordinates? = null

    val modifier: Modifier = Modifier.onGloballyPositioned { coordinates = it }

    /** [local] is where the double tap landed, in the picture's own coordinates. */
    fun zoomAt(local: Offset) {
        val inRoot = coordinates?.takeIf { it.isAttached }?.localToRoot(local) ?: local
        magnifier.zoomAt(inRoot)
        UiTrace.event("double tap -> magnify")
    }
}

@Composable
fun rememberZoomAtTap(): ZoomAtTap {
    val magnifier = LocalMagnifier.current
    return remember(magnifier) { ZoomAtTap(magnifier) }
}
