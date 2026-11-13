package ch.heuscher.gentleconnect.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.heuscher.gentleconnect.R

/**
 * How long "Shall I end the call with Karolina?" waits. Half again as long as the ringing one.
 *
 * The difference is where the phone is. When she is asked whether to ring somebody she is
 * already looking at the screen; when she is asked whether to hang up, the phone is against her
 * ear and the screen is dark under the proximity sensor. She has to notice nothing happened,
 * take the phone down, find the question and read it — and if it has gone by the time she looks,
 * she is left pressing the red circle again in the middle of a conversation, which is the
 * fumbling this dialog was added to stop.
 *
 * Thirty seconds is unhurried for that, and costs nothing when she is quick: answering early
 * ends the wait, and the only thing the full thirty buys is a conversation that carries on.
 */
private const val HANG_UP_QUESTION_TIMEOUT_MILLIS = 30_000

/**
 * "Shall I end the call with Karolina?" — [ConfirmCallDialog] read the other way round.
 *
 * The red circle sits under her thumb for the whole conversation, on a screen she is holding
 * against her ear, and one brush against it ends the call with no way back: she cannot tell
 * whether her daughter hung up or she did, and getting back means starting the call again,
 * which is the harder half.
 *
 * Only while they are actually talking. A ringing telephone she does not want is the one moment
 * the red circle must stay a single press — see the `askBeforeHangingUp` parameter on
 * [ch.heuscher.gentleconnect.ui.GentleCallScreen] — because making it harder to stop a call she
 * did not want is the opposite of the point.
 *
 * A question, not a warning, for the same reason the ringing one is: nothing here has gone
 * wrong. Ending is the coloured button because ending is what she pressed; "Keep talking" sits
 * beside it and is just as easy to hit, and says what happens rather than merely declining.
 *
 * Running out keeps them talking, which is also what the quiet button does — see
 * [GentleConfirmDialog] for why a clock is never allowed to be the one that hangs up.
 */
@Composable
fun ConfirmHangUpDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    GentleConfirmDialog(
        question = stringResource(R.string.call_hangup_confirm_question, name),
        confirmLabel = stringResource(R.string.call_hangup_confirm_yes),
        dismissLabel = stringResource(R.string.call_hangup_confirm_no),
        timeoutMillis = HANG_UP_QUESTION_TIMEOUT_MILLIS,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}
