package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The green circle that rings somebody. One definition, used on the home screen and in the
 * address book alike.
 *
 * The two screens had drifted into two different buttons: home drew a 48.dp sage circle with the
 * `Call` handset at 26.dp, the address book a 52.dp dark-green circle with the `Phone` handset at
 * its natural size. Different colour, different size, different glyph — for the identical act,
 * on her son's row in both places. She has no way to know those are the same button, and every
 * difference invites the question of what the difference means.
 *
 * The dark pair won on contrast: white on `primary` reads at arm's length where a dark handset on
 * a pale sage disc does not, and 52.dp is the larger target of the two.
 *
 * Drawn as a circular physical key with a lip below it, sinking on press so the action registers.
 */
@Composable
fun GentleCallButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GentlePrimaryKey(
        onClick = onClick,
        modifier = modifier.width(SIZE),
        shape = CircleShape,
        minHeight = SIZE,
        contentPadding = PaddingValues(0.dp),
        contentAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Phone,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}

private val SIZE = 52.dp
