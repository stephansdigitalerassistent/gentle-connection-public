package ch.heuscher.gentleconnect.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.heuscher.gentleconnect.R

/**
 * How long "Shall I ring Karolina?" waits before deciding she is not going to answer it.
 *
 * Twenty seconds. She is looking at the screen — she has just pressed a face — so this only has
 * to cover reading five words, deciding, and finding a button, at her pace and not a stopwatch's.
 * The usual four-to-ten seconds an app gives an undo bar would expire while she is still reaching,
 * which is worse than never asking: the question would move out from under her hand.
 *
 * Twenty is also the floor WCAG 2.2.1 puts under any time limit before it counts as rushing
 * somebody, and there is nothing to be won by going below it.
 */
private const val RING_QUESTION_TIMEOUT_MILLIS = 20_000

/**
 * "Shall I ring Karolina?" — with her name in it, and two plain answers.
 *
 * Nothing dials on a single tap any more, on either screen that offers a telephone. Her address
 * book is 1254 people sorted by first name with 48dp telephones down the right-hand edge of a
 * scrolling list, so a thumb landing slightly wrong while she is looking for somebody rings a
 * stranger — and once it is ringing she has to work out how to stop it, which is a great deal
 * harder than starting it was.
 *
 * A question, not a warning. No red, no exclamation mark, nothing about being careful: she has
 * done nothing wrong and is being asked to confirm something entirely ordinary. Ringing is the
 * larger, coloured button because ringing is what she meant; the way out sits beside it and is
 * just as easy to hit.
 *
 * Running out rings nobody — see [GentleConfirmDialog].
 */
@Composable
fun ConfirmCallDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    GentleConfirmDialog(
        question = stringResource(R.string.call_confirm_question, name),
        confirmLabel = stringResource(R.string.call_confirm_yes),
        dismissLabel = stringResource(R.string.call_confirm_no),
        timeoutMillis = RING_QUESTION_TIMEOUT_MILLIS,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}
