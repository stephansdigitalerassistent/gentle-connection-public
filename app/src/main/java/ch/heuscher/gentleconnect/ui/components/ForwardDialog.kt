package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.ForwardableMedia
import ch.heuscher.gentleconnect.data.Forwarding
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.Recipient
import ch.heuscher.gentleconnect.ui.personName
import ch.heuscher.gentleconnect.util.traceTaps

/**
 * Where she is in sending something on: nowhere, choosing, being asked, or being told.
 *
 * One object rather than three booleans on each screen, because the three steps are one act and
 * only one of them can be happening. It is held by the screen so that the dialogs outlive the row
 * she pressed — a list scrolling under her must not take the question with it.
 */
@Stable
class ForwardFlowState {
    internal var choosing by mutableStateOf<Message?>(null)
    internal var confirming by mutableStateOf<Pair<Message, Recipient>?>(null)
    internal var outcome by mutableStateOf<Pair<Recipient, Boolean>?>(null)

    /** She has pressed "send on" under this message. */
    fun begin(message: Message) {
        choosing = message
    }
}

@Composable
fun rememberForwardFlow(): ForwardFlowState = remember { ForwardFlowState() }

/**
 * The three questions between pressing "send on" and it having gone, in order.
 *
 * Shared by the conversation and the View screen so that sending a photograph on is the same act
 * in both places — the same faces, the same question, the same answer afterwards. A picture her
 * family posted and a picture her sister sent her are the same picture to her, and the difference
 * between the two screens is not something she should have to learn twice.
 *
 * Draws nothing until [ForwardFlowState.begin] has been called.
 */
@Composable
fun ForwardFlow(
    state: ForwardFlowState,
    recipients: List<Recipient>,
    onForwardRequested: ((Message, Recipient, (Boolean) -> Unit) -> Unit)?
) {
    state.choosing?.let { message ->
        ForwardPickerDialog(
            recipients = recipients,
            onChosen = { recipient ->
                state.choosing = null
                state.confirming = message to recipient
            },
            onDismiss = { state.choosing = null }
        )
    }

    state.confirming?.let { (message, recipient) ->
        val media = Forwarding.mediaOf(message)
        if (media == null) {
            // The attachment has been pruned out from under her since she tapped. Nothing to send
            // and nothing to explain: the question simply does not appear.
            state.confirming = null
        } else {
            ConfirmForwardDialog(
                kind = media.kind,
                recipient = recipient,
                onConfirm = {
                    state.confirming = null
                    onForwardRequested?.invoke(message, recipient) { sent ->
                        state.outcome = recipient to sent
                    }
                },
                onDismiss = { state.confirming = null }
            )
        }
    }

    state.outcome?.let { (recipient, sent) ->
        ForwardOutcomeDialog(
            succeeded = sent,
            recipient = recipient,
            onDismiss = { state.outcome = null }
        )
    }
}

/**
 * "Who shall I send this to?" — the people she last wrote to, with their faces.
 *
 * Faces rather than a list of names, like every other way this app offers her a person: she
 * recognises her son before she reads his name, and sending a photograph to the wrong person is
 * the mistake this screen exists to make hard.
 *
 * Everybody she has written to, newest first, and the list scrolls — see
 * `Forwarding.recentRecipients`. It was five while five fitted, and the person she writes to
 * every fortnight was simply absent with no way to reach her at all. The order does the work the
 * cut used to: whoever she wrote to last is under her thumb, and the rest are below.
 */
@Composable
fun ForwardPickerDialog(
    recipients: List<Recipient>,
    onChosen: (Recipient) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .traceTaps("forward")
                .padding(8.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.forward_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Weighted, and that is not decoration: the list is everybody she has written to
                // now, and unweighted it grew past the bottom of the card and pushed the way out
                // off the screen with it. The only way back was the phone's own back gesture,
                // which is the one way out of anything this app never asks her to know.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    recipients.forEach { recipient ->
                        GentleKey(
                            onClick = { onChosen(recipient) },
                            modifier = Modifier.fillMaxWidth(),
                            face = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(16.dp),
                            minHeight = 72.dp,
                            fillWidth = true,
                            contentAlignment = Alignment.Start
                        ) {
                            ContactAvatar(
                                photoUri = recipient.photoUri,
                                name = personName(recipient.name),
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                text = personName(recipient.name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                GentleSecondaryKey(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                    minHeight = 48.dp
                ) {
                    Text(
                        text = stringResource(R.string.close),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/**
 * The question asked before anything leaves the phone: shall this go to this person?
 *
 * The same shape as "shall I ring her" ([GentleConfirmDialog]), for the same reason — a
 * photograph sent to the wrong person cannot be taken back, and one tap is not enough of a
 * decision for that. It names both the thing and the person, because "Are you sure?" is a
 * question about nothing.
 */
@Composable
fun ConfirmForwardDialog(
    kind: ForwardableMedia.Kind,
    recipient: Recipient,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val question = stringResource(
        when (kind) {
            ForwardableMedia.Kind.PHOTO -> R.string.forward_confirm_photo
            ForwardableMedia.Kind.VIDEO -> R.string.forward_confirm_video
            ForwardableMedia.Kind.VOICE -> R.string.forward_confirm_voice
        },
        personName(recipient.name)
    )

    GentleConfirmDialog(
        question = question,
        confirmLabel = stringResource(R.string.forward_confirm_yes),
        dismissLabel = stringResource(R.string.forward_confirm_no),
        // As long as the call question waits. She is being asked to decide about a photograph of
        // her family, and reading it twice is the point.
        timeoutMillis = FORWARD_QUESTION_MILLIS,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/**
 * What happened, said once and plainly.
 *
 * Her own copy lands in the conversation with the person she sent it to — which she is not
 * looking at, because she is here. So this is the only place she learns that it went, and the
 * only place she learns that it did not.
 */
@Composable
fun ForwardOutcomeDialog(
    succeeded: Boolean,
    recipient: Recipient,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .traceTaps("forward")
                .padding(8.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = if (succeeded) {
                        stringResource(R.string.forward_sent, personName(recipient.name))
                    } else {
                        stringResource(R.string.forward_failed)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                GentlePrimaryKey(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    minHeight = 56.dp,
                    fillWidth = true
                ) {
                    Text(
                        text = stringResource(R.string.forward_outcome_understood),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** How long the "shall I send this" question waits before leaving of its own accord. */
internal const val FORWARD_QUESTION_MILLIS = 30_000
