package ch.heuscher.gentleconnect.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * What a section on the home screen is called.
 *
 * A word, and nothing else. It was briefly a 48dp row with a green arrow on the end, standing in
 * for the section's door; the door is a small "More" button at the foot of the section again —
 * see [MoreRow] — and a heading with nothing to press on it has no reason to be a touch target.
 * So it is back to the height of its own line. There are three of these on the screen at once,
 * and two dozen millimetres of unpressable air apiece is somebody's face at the bottom of it.
 */
@Composable
fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
