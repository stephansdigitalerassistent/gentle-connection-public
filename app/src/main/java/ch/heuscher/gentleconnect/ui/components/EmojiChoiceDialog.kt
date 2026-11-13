package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.util.traceTaps

/**
 * How many faces are on a line. Four across leaves each of them the same size as the five under a
 * message, which is the size they were made for a thumb at arm's length. Five or six across would
 * fit more of them on the screen and be a grid she has to aim at.
 */
private const val CHOICES_PER_ROW = 4

/**
 * All the faces, for when the five are not the one she means.
 *
 * Opened from the last slot of [ReplyControls] where somebody looking after her has turned the
 * chooser on — see [ch.heuscher.gentleconnect.model.RemoteSettings.enableEmojiChoice].
 *
 * **What is offered is what the phone can also say out loud.** The list comes from the same table
 * the reading-aloud voice uses, so nothing here can end up on a message that read-aloud then has
 * to pass over in silence — see [ch.heuscher.gentleconnect.speech.EmojiNames] for why that table
 * is short and hand-picked rather than every emoji there is.
 *
 * One press sends and closes. There is no chosen-but-not-yet-sent state to be left in and no
 * second button to find: pressing a face here is the same act as pressing one in the row, which is
 * the thing she already knows how to do.
 */
@Composable
fun EmojiChoiceDialog(
    choices: List<String>,
    onChosen: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .traceTaps("emoji")
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
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.emoji_choice_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Capped and scrolling rather than as tall as it likes: the list is short today,
                // but a card that grows past the screen loses its Close button off the bottom
                // edge, and a dialog she cannot leave is the worst thing on this phone.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    choices.chunked(CHOICES_PER_ROW).forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            line.forEach { emoji ->
                                GentleKey(
                                    onClick = {
                                        onChosen(emoji)
                                        onDismiss()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f),
                                    face = MaterialTheme.colorScheme.primaryContainer,
                                    shape = CircleShape,
                                    minHeight = 0.dp,
                                    contentPadding = PaddingValues(0.dp),
                                    contentAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(text = emoji, fontSize = 32.sp)
                                }
                            }
                            // A last line of three faces would otherwise stretch them to a size
                            // the others are not, and the row she is looking at would change
                            // shape as she scrolled.
                            repeat(CHOICES_PER_ROW - line.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

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
