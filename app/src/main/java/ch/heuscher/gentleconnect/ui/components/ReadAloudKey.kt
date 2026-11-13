package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R

/**
 * What the voice is reading at this moment, or null in silence.
 *
 * Handed down the tree rather than through every screen's parameters: the buttons that care are
 * five screens deep in five different places, and nothing between them and the top has any use
 * for it.
 */
val LocalReadingAloud = compositionLocalOf<String?> { null }

/**
 * The "read aloud" button, which while it is reading says so.
 *
 * It behaves like the voice message key, because to her they are the same thing — a button that
 * makes the phone talk. While the voice is reading [text] the key takes the louder colour and
 * its label becomes the word for stopping; pressing it then is silence, not the same sentence
 * again from the top. Before this the key looked the same whether or not anything was
 * happening, and on a phone turned down that is a button that appears to be broken.
 */
@Composable
fun ReadAloudKey(
    text: String,
    onSpeakRequested: (String) -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 56.dp,
    contentPadding: PaddingValues = GentleKeyDefaults.ContentPadding
) {
    val reading = LocalReadingAloud.current == text
    GentleKey(
        onClick = { onSpeakRequested(text) },
        modifier = modifier,
        face = if (reading) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (reading) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        shape = RoundedCornerShape(16.dp),
        minHeight = minHeight,
        contentPadding = contentPadding,
        fillWidth = true
    ) {
        Text(
            text = stringResource(if (reading) R.string.read_aloud_stop else R.string.read_aloud),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}
