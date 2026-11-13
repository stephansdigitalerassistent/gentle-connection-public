package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.Recipient
import ch.heuscher.gentleconnect.ui.personName
import ch.heuscher.gentleconnect.util.traceTaps

/**
 * "Who do you want to write to?" — the same shape as the call dialog, because it is the same
 * question about the same people.
 *
 * No transport is named anywhere in it. Whether the message will leave as a Threema message, a
 * WhatsApp message or an SMS is [ch.heuscher.gentleconnect.data.MessageRouter]'s business; she
 * is choosing a person.
 */
@Composable
fun RecipientPickerDialog(
    recipients: List<Recipient>,
    onRecipientChosen: (Recipient) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .traceTaps("write")
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
                    text = stringResource(R.string.write_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (recipients.isEmpty()) {
                    Text(
                        text = stringResource(R.string.write_no_recipients),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        recipients.forEach { recipient ->
                            GentleKey(
                                onClick = { onRecipientChosen(recipient) },
                                modifier = Modifier.fillMaxWidth(),
                                face = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                shape = RoundedCornerShape(16.dp),
                                minHeight = 56.dp,
                                fillWidth = true,
                                contentAlignment = Alignment.CenterHorizontally
                            ) {
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
 * Write to the person she just chose.
 *
 * ## Straight into the writing
 *
 * The cursor is put in the line and the keyboard comes up as the dialog opens. She did not arrive
 * here by accident: she pressed a person and then pressed "write to them", which is two presses
 * that both say the same thing. Making her find the line and tap it a third time is asking her to
 * say it again.
 *
 * ## No emoji here
 *
 * It used to open with the five big faces [ReplyControls] offers, as the route to writing that
 * needs no keyboard. They are gone from this dialog by the owner's decision: she comes here
 * having chosen the harder of the two things, and a row of faces above the line is the other
 * answer offered again in the one place she has already turned it down — with the keyboard
 * already up and covering the bottom of the card. The faces are still under every conversation,
 * which is where a message that needs no keyboard belongs.
 */
// LocalSoftwareKeyboardController is still experimental at this Compose version, and the reply
// controls opt in to it the same way.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ComposeMessageDialog(
    recipient: Recipient,
    onSend: (text: String) -> Unit,
    onDismiss: () -> Unit
) {
    var messageText by remember { mutableStateOf("") }

    val keyboard = LocalSoftwareKeyboardController.current
    val box = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // One frame first. This is a window of its own and the writing line does not exist yet at
        // the moment this effect runs; asking a box that is not on the screen for focus throws,
        // and a launcher that falls over when she presses "write" is the worst failure this app
        // has. Caught as well as waited for, because the cost of failing is that she taps the
        // line herself — which is what she used to have to do anyway.
        withFrameNanos { }
        runCatching { box.requestFocus() }
        keyboard?.show()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .traceTaps("write")
                .padding(8.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = personName(recipient.name),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(24.dp))

                val readyToSend = messageText.isNotBlank()

                // On the writing line, for the reason given in [ReplyControls]: below it is
                // where the keyboard covers it, and here it cannot be hidden without hiding the
                // box she is typing into as well. Always drawn, greyed until there is something
                // to send.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        placeholder = { Text(stringResource(R.string.reply_placeholder)) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(box),
                        shape = RoundedCornerShape(16.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    GentlePrimaryKey(
                        onClick = {
                            onSend(messageText)
                            onDismiss()
                        },
                        enabled = readyToSend,
                        modifier = Modifier.width(56.dp),
                        shape = CircleShape,
                        minHeight = 56.dp,
                        contentPadding = PaddingValues(0.dp),
                        contentAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send_reply),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                GentleSecondaryKey(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.Start),
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
