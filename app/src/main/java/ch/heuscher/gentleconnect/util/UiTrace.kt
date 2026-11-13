package ch.heuscher.gentleconnect.util

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes what she was looking at and what she pressed into the log, and so into app.log.
 *
 * Built after an "Etwas ist komisch" alert she did not remember sending: logcat had 84 touches
 * with coordinates and nothing about which screen they were on or what was under them, so the
 * question "what did she press" could only be guessed at. One line per screen change and two per
 * touch (down with the thing under the finger, up with how long and whether it moved) answer it.
 *
 * Labels are cut short: a message bubble's text is its label, and the log does not need more than
 * enough of it to recognise which one.
 */
object UiTrace {

    private const val TAG = "UiTrace"
    private const val LABEL_MAX = 40
    private const val BUFFER_CAPACITY = 200

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    private data class TraceEntry(val timestamp: Long, val line: String)
    private val ringBuffer = ArrayDeque<TraceEntry>()

    enum class TouchResult {
        ANSWERED,
        UNANSWERED,
        NEITHER
    }

    /** The screen currently shown in MainActivity, so a touch in a dialog still says where it was. */
    @Volatile
    var screen: String = "-"
        private set

    /**
     * Whether the current touch interaction produced a visible effect, click, or scroll.
     * Reset on every touch down in MainActivity.dispatchTouchEvent; set when a screen change occurs,
     * any button/card click handles it, or a list scrolls.
     */
    @Volatile
    var isTouchAnswered: Boolean = false
        private set

    @Volatile
    private var isCancelled: Boolean = false

    @Volatile
    private var wasMoved: Boolean = false

    @Volatile
    private var landedOnEnabledClickable: Boolean = false

    @Volatile
    private var touchCompleted: Boolean = false

    fun touchStarted() {
        isTouchAnswered = false
        isCancelled = false
        wasMoved = false
        landedOnEnabledClickable = false
        touchCompleted = false
    }

    fun touchCancelled() {
        isCancelled = true
    }

    fun setLandedOnEnabledClickable(enabled: Boolean) {
        if (enabled) {
            landedOnEnabledClickable = true
        }
    }

    fun markAnswered() {
        isTouchAnswered = true
    }

    /**
     * The outcome of the most recent touch gesture:
     * - [TouchResult.NEITHER] if multi-finger, cancelled, touch did not complete, or a drag/scroll
     *   gesture that did not trigger an explicit interactive event (drags are not counted as dead taps).
     * - [TouchResult.UNANSWERED] if a "tap" (not moved) landed on no clickable or on a disabled one,
     *   and was not otherwise marked answered.
     * - [TouchResult.ANSWERED] if:
     *     - a "tap" on an enabled clickable (or markAnswered was called), OR
     *     - it "moved" and markAnswered was called (e.g. click fired or explicit event).
     */
    val lastTouchResult: TouchResult
        get() {
            if (isCancelled || !touchCompleted) {
                return TouchResult.NEITHER
            }
            return if (!wasMoved) {
                if (landedOnEnabledClickable || isTouchAnswered) {
                    TouchResult.ANSWERED
                } else {
                    TouchResult.UNANSWERED
                }
            } else {
                if (isTouchAnswered) {
                    TouchResult.ANSWERED
                } else {
                    TouchResult.NEITHER
                }
            }
        }

    fun screen(name: String) {
        if (name == screen) return
        markAnswered()
        screen = name
        val line = "screen $name"
        Log.i(TAG, line)
        recordLine(line)
    }

    /** A thing happening that no touch shows, such as a dialog opening or timing out. */
    fun event(what: String) {
        // Something visibly happened (a magnify, a viewer opening), so the touch that caused it,
        // if any, was answered. Between touches this is wiped by the next touchStarted().
        markAnswered()
        val line = "event [$screen] $what"
        Log.i(TAG, line)
        recordLine(line)
    }

    internal fun touchDown(area: String, x: Float, y: Float, density: Float, target: String) {
        val line = "down [$screen/$area] ${dp(x, density)},${dp(y, density)}dp -> $target"
        Log.i(TAG, line)
        recordLine(line)
    }

    internal fun touchUp(
        area: String,
        millis: Long,
        moved: Boolean,
        cancelled: Boolean,
        travelDp: Int,
        endDxDp: Int = 0,
        endDyDp: Int = 0
    ) {
        if (cancelled) {
            isCancelled = true
        } else {
            touchCompleted = true
        }
        if (moved) {
            wasMoved = true
        }
        val kind = when {
            cancelled -> "cancelled"
            moved -> "drag"
            else -> "tap"
        }
        // Where a drag let go, relative to where it came down: the farthest point alone cannot
        // tell a finger sliding along a wide key from one swiping off it.
        val end = if (moved && !cancelled) " to ${signed(endDxDp)},${signed(endDyDp)}dp" else ""
        val line = "up   [$screen/$area] $kind ${millis}ms ${travelDp}dp$end"
        Log.i(TAG, line)
        recordLine(line)
    }

    private fun recordLine(line: String) {
        val now = System.currentTimeMillis()
        val timestamped = synchronized(timeFormat) {
            "${timeFormat.format(Date(now))} $line"
        }
        synchronized(ringBuffer) {
            if (ringBuffer.size >= BUFFER_CAPACITY) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(TraceEntry(now, timestamped))
        }
    }

    /**
     * Returns the trace lines recorded within the last [windowMs] milliseconds.
     */
    fun recentLines(windowMs: Long = 30_000L, now: Long = System.currentTimeMillis()): List<String> {
        val cutoff = now - windowMs
        return synchronized(ringBuffer) {
            ringBuffer.filter { it.timestamp >= cutoff }.map { it.line }
        }
    }

    /**
     * Formats recent trace lines as a newline-separated string for the restless snapshot trail sidecar.
     */
    fun recentTrail(windowMs: Long = 30_000L, now: Long = System.currentTimeMillis()): String =
        recentLines(windowMs, now).joinToString("\n")

    private fun dp(px: Float, density: Float) = (px / density).toInt()

    private fun signed(value: Int) = if (value >= 0) "+$value" else "$value"

    internal data class SemanticsTarget(
        val description: String,
        val isEnabledClickable: Boolean
    )

    /**
     * The deepest clickable thing under [point] (in root coordinates), or failing that the deepest
     * thing with a label. Uses the merged tree, so a button reads as its text.
     */
    internal fun describeTarget(root: SemanticsNode, point: Offset): SemanticsTarget {
        var clickable: SemanticsNode? = null
        var labelled: SemanticsNode? = null
        fun walk(node: SemanticsNode) {
            if (!node.boundsInRoot.contains(point)) return
            if (node.config.getOrNull(SemanticsActions.OnClick) != null) clickable = node
            if (label(node) != null) labelled = node
            node.children.forEach { walk(it) }
        }
        walk(root)
        val isEnabledClickable = clickable?.let {
            it.config.getOrNull(SemanticsActions.OnClick) != null &&
                it.config.getOrNull(SemanticsProperties.Disabled) == null
        } ?: false

        val node = clickable ?: labelled
        val description = if (node == null) {
            "(nothing)"
        } else {
            val kind = if (node === clickable) "click" else "view"
            "$kind \"${label(node) ?: ""}\""
        }
        return SemanticsTarget(description, isEnabledClickable)
    }

    internal fun describe(root: SemanticsNode, point: Offset): String =
        describeTarget(root, point).description

    private fun label(node: SemanticsNode): String? {
        val config = node.config
        val text = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            ?.takeIf { it.isNotBlank() }
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?.takeIf { it.isNotBlank() }
            ?: config.getOrNull(SemanticsProperties.TestTag)
            ?: return null
        val flat = text.replace('\n', ' ').trim()
        return if (flat.length <= LABEL_MAX) flat else flat.take(LABEL_MAX) + "…"
    }
}

/**
 * Logs every touch that lands inside this element, and what it landed on, without taking it:
 * it listens on the Initial pass and consumes nothing, so the screens underneath behave exactly
 * as they did.
 *
 * Every dialog is its own window with its own root, so a dialog needs this on its own content;
 * the one on MainActivity's root does not see touches in a dialog.
 */
fun Modifier.traceTaps(area: String): Modifier = composed {
    val view = LocalView.current
    // Remembered, so the gesture loop (started once) and the latest layout see the same holder.
    val placed = remember { Placed() }
    val nestedScrollConn = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (consumed.getDistance() > 1f) {
                    UiTrace.markAnswered()
                }
                return Offset.Zero
            }
        }
    }
    this
        .nestedScroll(nestedScrollConn)
        .onGloballyPositioned { placed.coordinates = it }
        .pointerInput(area) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val startedAt = SystemClock.uptimeMillis()
                try {
                    val inRoot = placed.coordinates?.takeIf { it.isAttached }?.localToRoot(down.position)
                        ?: down.position
                    val semantics = try {
                        val root = (view as? RootForTest)?.semanticsOwner?.rootSemanticsNode
                        if (root != null) UiTrace.describeTarget(root, inRoot) else null
                    } catch (e: Exception) {
                        null
                    }
                    if (semantics?.isEnabledClickable == true) {
                        UiTrace.setLandedOnEnabledClickable(true)
                    }
                    val target = semantics?.description ?: "(no semantics)"
                    UiTrace.touchDown(area, inRoot.x, inRoot.y, density, target)
                } catch (e: Exception) {
                    // The log must never be what breaks a touch.
                }

                var moved = false
                var cancelled = true
                var maxTravelPx = 0f
                var lastOffset = Offset.Zero
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) break
                    lastOffset = change.position - down.position
                    val dist = lastOffset.getDistance()
                    if (dist > maxTravelPx) {
                        maxTravelPx = dist
                    }
                    if (dist > viewConfiguration.touchSlop) {
                        moved = true
                    }
                    if (!change.pressed) {
                        cancelled = false
                        break
                    }
                }
                val travelDp = (maxTravelPx / density).toInt()
                UiTrace.touchUp(
                    area,
                    SystemClock.uptimeMillis() - startedAt,
                    moved,
                    cancelled,
                    travelDp,
                    (lastOffset.x / density).toInt(),
                    (lastOffset.y / density).toInt()
                )
            }
        }
}

private class Placed {
    var coordinates: LayoutCoordinates? = null
}
