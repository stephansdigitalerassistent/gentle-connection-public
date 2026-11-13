package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R

/**
 * Somebody's voice, and the one control that starts it.
 *
 * The most valuable thing either messenger carries for her, and until now the emptiest: a voice
 * message was rendered as the four bracketed syllables "[Sprachnachricht]". It is the one kind of
 * message that needs no reading at all — which on a phone for somebody whose sight is going makes
 * it worth more than everything else here put together.
 *
 * One control, the width of the bubble, that starts it and stops it. Deliberately not offered:
 *
 * - **A scrubber.** Landing on a thin line and dragging it accurately is a fine-motor task, and
 *   getting it wrong throws away the place she had got to in her daughter's message.
 * - **Pause and resume.** A control that means "stop" one moment and "carry on from where you
 *   were" the next is two controls wearing one face. This one always means the same thing.
 * - **A duration, or a waveform.** Neither answers a question she has. "1:47" is a number to
 *   interpret before she has heard anything.
 * - **Recording a reply.** A separate question and a much harder one — a button that records
 *   while held and sends on release will send a great deal of silence.
 *
 * The label carries the state: press to listen, press again to stop. Nothing is said about a file
 * that has been pruned or never arrived; the control is simply absent, and the message keeps the
 * text it always had.
 */
@Composable
fun MessageVoice(
    path: String?,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (path.isNullOrBlank()) return

    Spacer(modifier = Modifier.height(12.dp))
    GentleKey(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth(),
        // While it is sounding it takes the louder colour, so that across a conversation of
        // several voice messages it is obvious which one she is hearing.
        face = if (isPlaying) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (isPlaying) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        shape = RoundedCornerShape(16.dp),
        minHeight = 64.dp,
        fillWidth = true
    ) {
        Text(
            text = stringResource(
                if (isPlaying) R.string.voice_message_stop else R.string.voice_message_play
            ),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}
