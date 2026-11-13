package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.util.traceTaps

/**
 * How long the question spends leaving, at the very end of its wait.
 *
 * Long enough to be a departure she can see out of the corner of her eye, short enough that the
 * words are readable for all but the last moment of a wait that was never hers to meet.
 */
private const val FADE_MILLIS = 2_000

/**
 * A question with her name in it, two plain answers, and a patience of its own.
 *
 * Both questions this app asks — shall I ring her, shall I end this — are the same shape, so
 * they are the same dialog with different words. See [ConfirmCallDialog] and
 * [ConfirmHangUpDialog] for why each is asked at all.
 *
 * ## Why it goes away by itself
 *
 * A question she does not answer is a phone that has stopped working. She taps a face, does not
 * recognise what has appeared, and puts the phone down — and it sits on a modal dialog until
 * somebody else picks it up, with her home screen behind it and nothing reachable. Left long
 * enough that is a phone that never rings again for the person holding it.
 *
 * ## What running out means
 *
 * Nothing. Always nothing. Running out is the same as pressing the quiet button: the call is
 * not placed, or the conversation carries on. A timer that pressed the coloured button instead
 * would ring a stranger she never chose, or hang up on her daughter after half a minute of
 * saying nothing — which is the exact harm each of these dialogs exists to prevent. The clock
 * must never be able to do something she did not.
 *
 * ## How it leaves
 *
 * It fades, over the last two seconds and not before. There is no number and no longer a bar:
 * both of those count at her the whole time she is reading, and turn an ordinary question into a
 * test she can fail. This waits at full strength for as long as she has, and then goes — she
 * sees a question leaving rather than a deadline approaching.
 *
 * Two seconds, and only at the end, because the fade costs contrast. Anything spread across the
 * whole twenty would have the question and both buttons washed out halfway through, for exactly
 * the person the twenty seconds exist for: someone still reading, still reaching. At a fifth of
 * a percent per frame nobody would see it moving anyway — it would not read as "this will not
 * wait forever", it would read as a phone going dim.
 *
 * The fade is not a picture of the timer — it *is* the timer, driven by the same clock that
 * decides when the question goes, so it can never finish while the question stays nor the
 * question vanish at full strength.
 *
 * That clock is the frame clock and real elapsed nanoseconds, deliberately, rather than an
 * `Animatable` running a `tween`. Compose scales animation durations by the system's animator
 * duration scale, and at zero a twenty-second tween finishes on the frame it starts: the dialog
 * appeared and dismissed itself in the same breath, so pressing a face did nothing at all.
 * Nobody has to visit developer options for that — battery saver sets the scale to zero on its
 * own, which is where the test phone was when this was found at 24% charge. A question that
 * vanishes faster the flatter her battery gets is not a timeout, it is a phone that stops
 * working in the evening.
 *
 * @param timeoutMillis how long the question waits. Long, and different per question — see the
 *   two callers. Erring long is nearly free: an unanswered dialog costs her the phone only
 *   after minutes, while one that expires mid-reach costs her the thing she was doing every
 *   single time.
 */
@Composable
fun GentleConfirmDialog(
    question: String,
    confirmLabel: String,
    dismissLabel: String,
    timeoutMillis: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean = false
) {
    val opacity = gentleDialogOpacity(timeoutMillis, onDismiss)

    AlertDialog(
        // The whole question fades as one — words, buttons and the card they sit on. Nothing to
        // announce to a screen reader either way: a spoken "two seconds left" would put back the
        // pressure that leaving the number out took away.
        modifier = Modifier.graphicsLayer { alpha = opacity }.traceTaps("confirm"),
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = question,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        confirmButton = {
            GentlePrimaryKey(
                onClick = onConfirm,
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp
                    )
                } else {
                    Text(
                        text = confirmLabel,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        },
        dismissButton = {
            GentleSecondaryKey(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text(
                    text = dismissLabel,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    )
}

/**
 * The wait, and the fade that is the wait — for any dialog that must not sit there for ever.
 *
 * Lifted out of [GentleConfirmDialog] when the people in a group became pressable: choosing
 * between ringing somebody and writing to them is the same kind of question, asked of the same
 * person, and a question she walks away from must leave by itself there too. Everything above
 * about what running out means applies wherever this is used — it dismisses, and dismissing is
 * always the thing that does nothing.
 *
 * @return the opacity to draw the whole dialog at, full strength until the last [FADE_MILLIS].
 */
@Composable
fun gentleDialogOpacity(timeoutMillis: Int, onTimeout: () -> Unit): Float {
    // rememberUpdatedState so the timer keeps pointing at the current callback: the animation
    // below is started once and outlives recomposition, and capturing the first callback would
    // mean a question that eventually dismisses something that has since moved on.
    val timedOut by rememberUpdatedState(onTimeout)
    var opacity by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(timeoutMillis) {
        val startedAt = withFrameNanos { it }
        while (true) {
            // Real nanoseconds between real frames. `withFrameNanos` suspends until the next one
            // is drawn, so this costs nothing while nothing is happening and stops dead when the
            // dialog leaves the screen.
            val elapsedMillis = (withFrameNanos { it } - startedAt) / 1_000_000L
            if (elapsedMillis >= timeoutMillis) break
            // Full strength until the fade begins, so nothing is harder to read while she still
            // has time to answer. Straight proportion once it does: two seconds is short enough
            // that easing it would only make the last moment lurch.
            val fadingFor = elapsedMillis - (timeoutMillis - FADE_MILLIS)
            opacity = if (fadingFor <= 0) 1f else 1f - fadingFor.toFloat() / FADE_MILLIS
        }
        opacity = 0f
        timedOut()
    }

    return opacity
}
