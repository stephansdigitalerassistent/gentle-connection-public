package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.data.SendRetryPolicy
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holding something for five seconds to send it again.
 *
 * There is no button. A message that did not go is already the one thing on her screen allowed
 * to look wrong, and putting a control on it would mean explaining, beside her own words, that
 * something failed — which is exactly the error copy her screens do not carry. So the message
 * itself is the control: she holds the thing she wants to happen again.
 *
 * Five seconds rather than a normal long press, because the cost of an accident here is her
 * family receiving the same words twice with no explanation, and a thumb resting on a screen is
 * not a decision. The bar makes the length usable instead of merely safe: it starts filling
 * under her finger straight away, so a hold she did not mean shows her what it is about to do
 * with four seconds still left to lift off, and a hold she did mean tells her it is working
 * rather than leaving her guessing whether the phone noticed.
 *
 * @param enabled whether this message may be sent again at all — see
 *   [SendRetryPolicy.mayResend]. When false there is no gesture, rather than a gesture that does
 *   nothing.
 */
@Composable
fun HoldToResend(
    enabled: Boolean,
    onResend: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var holding by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    // Measured against the clock, one frame at a time — not with animateFloatAsState.
    //
    // Found on the phone: the bar filled instantly and then nothing happened for five seconds.
    // The device has its animator duration scale at zero, so every Compose animation completes
    // in one frame, while the gesture goes on waiting the real five seconds it was asked for.
    // A bar that says "done" over a control that has not done anything is the exact thing this
    // whole app keeps arguing against, and it is not a quirk of one test phone — the scale is a
    // system setting, and "remove animations" is a reasonable thing for her to have switched on.
    //
    // The clock cannot be scaled away. What the bar shows is now the same quantity the gesture
    // measures, so they cannot disagree.
    LaunchedEffect(holding) {
        if (!holding) {
            progress = 0f
            return@LaunchedEffect
        }
        val startedAt = withFrameMillis { it }
        while (progress < 1f) {
            val frameAt = withFrameMillis { it }
            progress = SendRetryPolicy.holdProgress(frameAt - startedAt)
        }
    }

    Box(
        modifier = modifier.then(
            if (!enabled) {
                Modifier
            } else {
                Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        holding = true
                        // Null means she was still holding when the time ran out, which is the
                        // whole gesture. Anything else — lifting off, the gesture being taken
                        // over by the list scrolling — is her not asking for this.
                        val liftedEarly = withTimeoutOrNull(SendRetryPolicy.HOLD_TO_RESEND_MS) {
                            waitForUpOrCancellation()
                        }
                        holding = false
                        if (liftedEarly == null) onResend()
                    }
                }
            }
        )
    ) {
        content()

        if (enabled && progress > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
