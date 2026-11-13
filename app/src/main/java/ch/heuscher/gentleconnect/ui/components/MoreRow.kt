package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The last row of a section on the home screen, when the section is holding some back.
 *
 * Home shows a few of each thing. Without this the next person or message simply is not there,
 * and there is no way to tell a short list from a truncated one: a home screen with three faces
 * looks exactly like a home screen for somebody who knows three people. She has fifteen.
 *
 * It says what it leads to and no more than that — "More people", never "12 more people", because
 * a count is a number to read and then to feel something about. The destination is named because
 * the home screen draws several of these, leading to different places, and the only way to find
 * out which one she had pressed was to arrive somewhere.
 *
 * Drawn only when [ch.heuscher.gentleconnect.model.Home.hasMore] says so. A "More" that opens
 * onto the same rows is a door onto the room she is standing in.
 *
 * @param label what is on the other side of it, from `strings.xml`.
 * @param onClick where the rest of the section is.
 */
@Composable
fun MoreRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        // A physical key with the container pair rather than a plain surface: this is a thing to press, and the
        // rows above it are things to read.
        GentleKey(
            onClick = onClick,
            face = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(22.dp),
            minHeight = 48.dp,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
