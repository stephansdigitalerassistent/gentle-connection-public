package ch.heuscher.gentleconnect.ui.components

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.DoubleTapCounter
import ch.heuscher.gentleconnect.model.Magnification
import ch.heuscher.gentleconnect.model.ScrollIndicator
import ch.heuscher.gentleconnect.util.UiTrace

/**
 * Interface exposed through [LocalMagnifier] allowing child composables to request targeted
 * magnification at a specific point on the screen.
 */
interface Magnifier {
    val isZoomed: Boolean
    fun zoomAt(rootPoint: Offset)
}

/**
 * Provides access to the enclosing [PinchToZoom] state and targeted zoom actions.
 * Defaults to a no-op implementation when no magnifier is present in the tree.
 */
val LocalMagnifier = compositionLocalOf<Magnifier> {
    object : Magnifier {
        override val isZoomed: Boolean = false
        override fun zoomAt(rootPoint: Offset) {}
    }
}

/**
 * Lets her make the whole screen bigger with two fingers, freeze the screen from accidental
 * touches while magnified, and scroll horizontally and vertically across the enlarged view.
 *
 * Wrapped around every screen at once rather than built into any of them, because "I cannot read
 * that" is not about a particular screen: it happens on the message she is reading, on the name
 * she is trying to dial, on the photograph her daughter sent. Anything that worked on only some
 * screens would be a feature she has to remember the shape of, and remembering is the thing this
 * app is for.
 *
 * ## Freezing the screen on zoom
 *
 * While the screen is unmagnified, single-finger gestures belong to the app beneath — pressing a
 * button, scrolling a list, tapping the clock. Two fingers down begin a pinch that magnifies the
 * screen.
 *
 * Once magnified, the screen is frozen: all pointer events are consumed before reaching the
 * children. Tapping or dragging anywhere on the magnified view will not place a call, send a
 * message, or scroll an inner list. Instead, single-finger dragging scrolls the entire magnified
 * surface both horizontally and vertically, allowing her to read across lines or inspect small text
 * safely. Horizontal and vertical scrollbars indicate where on the screen she has arrived.
 *
 * While frozen, a single-finger double tap resets the magnification back to normal size, matching
 * the action of the physical reset button at the bottom of the glass.
 *
 * ## The way back
 *
 * The reason this is safe to give her at all. A magnified screen can end up showing a corner of
 * itself with the part she wants outside the glass, and she has no idea what she did or how to
 * undo it — at which point the telephone is broken and somebody has to be called. So while
 * anything is magnified there is one large button, always in the same place, that puts everything
 * back exactly as it was. It sits outside the magnification, which is the whole point: it is the
 * one thing on the screen that cannot itself be zoomed away.
 *
 * @param resetButtonInsets what the reset button has to keep clear of at the bottom. Around the
 *   screens that bottom edge is the bottom of the display, where the telephone's own bar of
 *   buttons is; inside a dialog it is the lower edge of a card floating well above it, and
 *   holding the button away from a bar that is nowhere near it would push it up over the dialog's
 *   own controls.
 * @param onZoomChanged optional callback notified whenever the magnified state changes.
 */
@Composable
fun PinchToZoom(
    modifier: Modifier = Modifier,
    resetButtonInsets: WindowInsets = WindowInsets.navigationBars,
    onZoomChanged: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var scale by remember { mutableFloatStateOf(Magnification.MIN_SCALE) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(0f) }
    var pinchCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val zoomTapCounter = remember { DoubleTapCounter() }

    val zoomed = Magnification.isZoomed(scale)

    LaunchedEffect(zoomed) {
        onZoomChanged?.invoke(zoomed)
    }

    val magnifier = remember {
        object : Magnifier {
            override val isZoomed: Boolean
                get() = Magnification.isZoomed(scale)

            override fun zoomAt(rootPoint: Offset) {
                val boxCoords = pinchCoordinates?.takeIf { it.isAttached }
                // Root, not window: callers convert with localToRoot, and inside a dialog the
                // window and the root are not the same place.
                val localPoint = if (boxCoords != null) {
                    rootPoint - boxCoords.positionInRoot()
                } else {
                    rootPoint
                }
                val zoom = Magnification.zoomInAt(
                    pointX = localPoint.x,
                    pointY = localPoint.y,
                    width = width,
                    height = height
                )
                scale = zoom.scale
                offsetX = zoom.offsetX
                offsetY = zoom.offsetY
            }
        }
    }

    val vBar = remember(offsetY, scale, height) {
        ScrollIndicator.forZoom(offset = offsetY, scale = scale, viewportSize = height)
    }
    val hBar = remember(offsetX, scale, width) {
        ScrollIndicator.forZoom(offset = offsetX, scale = scale, viewportSize = width)
    }

    Box(
        // No size of its own. Around the screens it is given `fillMaxSize`; inside a dialog it
        // takes the dialog's size, so the clamping is against what she can actually see rather
        // than against the whole display.
        modifier = modifier
            .onSizeChanged {
                width = it.width.toFloat()
                height = it.height.toFloat()
            }
            .onGloballyPositioned {
                pinchCoordinates = it
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    zoomTapCounter.doubleTapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis
                    zoomTapCounter.doubleTapMinTimeMs = viewConfiguration.doubleTapMinTimeMillis
                    zoomTapCounter.longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
                    zoomTapCounter.touchSlop = viewConfiguration.touchSlop
                    zoomTapCounter.doubleTapSlop = viewConfiguration.touchSlop * 2f

                    awaitEachGesture {
                        var singleDownTime = 0L
                        var singleDownPos = Offset.Zero
                        var singleMaxDist = 0f
                        var hadSingleFinger = false

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.filter { it.pressed }

                            val currentlyZoomed = Magnification.isZoomed(scale)
                            if (!currentlyZoomed) {
                                zoomTapCounter.reset()
                                hadSingleFinger = false

                                // One finger or none. Left entirely alone — this is every gesture the
                                // app already had.
                                if (down.size < 2) {
                                    if (event.changes.none { it.pressed }) break
                                    continue
                                }

                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)

                                val newScale = Magnification.scaleBy(scale, zoom)
                                if (newScale != scale) {
                                    offsetX = Magnification.offsetAfterZoom(offsetX, centroid.x, scale, newScale)
                                    offsetY = Magnification.offsetAfterZoom(offsetY, centroid.y, scale, newScale)
                                    scale = newScale
                                }
                                offsetX = Magnification.clampOffset(offsetX + pan.x, scale, width)
                                offsetY = Magnification.clampOffset(offsetY + pan.y, scale, height)

                                // Only now, and only for the fingers that made a two-finger gesture.
                                down.forEach { it.consume() }
                            } else {
                                // Zoomed: the screen is frozen. All pointer events are consumed in
                                // Initial pass so child buttons, text, and lists never see them.
                                if (event.changes.none { it.pressed }) {
                                    if (hadSingleFinger) {
                                        val upTime = SystemClock.uptimeMillis()
                                        val resetFired = zoomTapCounter.recordTap(
                                            downMs = singleDownTime,
                                            upMs = upTime,
                                            x = singleDownPos.x,
                                            y = singleDownPos.y,
                                            movedDistance = singleMaxDist
                                        )
                                        if (resetFired) {
                                            scale = Magnification.MIN_SCALE
                                            offsetX = 0f
                                            offsetY = 0f
                                            zoomTapCounter.reset()
                                            UiTrace.event("double tap -> zoom reset")
                                        }
                                        hadSingleFinger = false
                                    }
                                    break
                                }

                                if (down.size >= 2) {
                                    hadSingleFinger = false
                                    zoomTapCounter.reset()

                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = true)

                                    val newScale = Magnification.scaleBy(scale, zoom)
                                    if (newScale != scale) {
                                        offsetX = Magnification.offsetAfterZoom(offsetX, centroid.x, scale, newScale)
                                        offsetY = Magnification.offsetAfterZoom(offsetY, centroid.y, scale, newScale)
                                        scale = newScale
                                    }
                                    offsetX = Magnification.clampOffset(offsetX + pan.x, scale, width)
                                    offsetY = Magnification.clampOffset(offsetY + pan.y, scale, height)
                                } else if (down.size == 1) {
                                    val finger = down.first()
                                    if (!hadSingleFinger) {
                                        hadSingleFinger = true
                                        singleDownTime = SystemClock.uptimeMillis()
                                        singleDownPos = finger.position
                                        singleMaxDist = 0f
                                    } else {
                                        val dist = (finger.position - singleDownPos).getDistance()
                                        if (dist > singleMaxDist) {
                                            singleMaxDist = dist
                                        }
                                    }

                                    // One finger: horizontal and vertical scrolling across the frozen screen.
                                    val pan = event.calculatePan()
                                    val newOffsetX = Magnification.clampOffset(offsetX + pan.x, scale, width)
                                    val newOffsetY = Magnification.clampOffset(offsetY + pan.y, scale, height)
                                    // The events are consumed here, so no list underneath scrolls and
                                    // nothing else tells UiTrace this drag did something. Only when
                                    // the view really moved: a drag against the edge showed her nothing.
                                    if (newOffsetX != offsetX || newOffsetY != offsetY) {
                                        UiTrace.markAnswered()
                                    }
                                    offsetX = newOffsetX
                                    offsetY = newOffsetY
                                } else {
                                    hadSingleFinger = false
                                }

                                down.forEach { it.consume() }
                            }
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                    // Top-left, so that "where does content point p end up" is offset + p * scale
                    // and nothing else. The default origin is the centre, which makes the same
                    // question depend on the size of the screen and the clamping impossible to
                    // reason about — see Magnification.
                    transformOrigin = TransformOrigin(0f, 0f)
                    // Keeps the magnified content inside the box it belongs to. Matters in a
                    // dialog, where without it a letter at four times the size would paint
                    // straight over the card's edges and across the dimmed screen behind it.
                    clip = true
                }
        ) {
            CompositionLocalProvider(LocalMagnifier provides magnifier) {
                content()
            }
        }

        if (zoomed) {
            // Vertical scrollbar along the right-hand edge
            GentleScrollbar(
                bar = vBar,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .windowInsetsPadding(resetButtonInsets)
                    .padding(top = 16.dp, bottom = 100.dp, end = 4.dp)
            )

            // Horizontal scrollbar along bottom edge, clear of the reset button
            GentleHorizontalScrollbar(
                bar = hBar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(resetButtonInsets)
                    .padding(bottom = 100.dp, start = 24.dp, end = 24.dp)
            )

            GentleKey(
                onClick = {
                    scale = Magnification.MIN_SCALE
                    offsetX = 0f
                    offsetY = 0f
                },
                face = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    // Bottom centre: the top of the screen holds the clock, whose five taps open
                    // the caregiver dialog, and a button that lands under a finger going for that
                    // would be found by accident.
                    .align(Alignment.BottomCenter)
                    // Clear of the telephone's own bar of buttons, not merely near it. 28.dp on
                    // its own left part of this button underneath a 48.dp navigation bar — found
                    // on the phone — which is the worst button in the app to have half of: it is
                    // the one that undoes a magnification she cannot read her way out of, and the
                    // half she can reach is next to Home. The inset first and the gap after it,
                    // so there is 28.dp of clear space above the bar whatever height it is.
                    .windowInsetsPadding(resetButtonInsets)
                    .padding(bottom = 28.dp),
                minHeight = 64.dp
            ) {
                Text(
                    text = stringResource(R.string.zoom_reset),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
