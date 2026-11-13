package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A status that is only words, drawn as words.
 *
 * In the first album it sat in the row of thumbnails as an empty grey square — a picture-shaped box
 * with no picture in it — which reads as something that failed to load. People who post words post
 * them to be read, so they get the room a picture would have had: large type on a soft colour,
 * centred, the way the messenger itself shows a status of words.
 *
 * No shape of its own: the album clips it to the corners of the pictures beside it, and the viewer
 * to its page.
 */
@Composable
fun StatusWords(
    text: String,
    modifier: Modifier = Modifier,
    /** The viewer's size, where the words are the whole page rather than one card of a few. */
    large: Boolean = false,
    maxLines: Int = Int.MAX_VALUE
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = if (large) {
                MaterialTheme.typography.headlineMedium
            } else {
                MaterialTheme.typography.titleLarge
            },
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
