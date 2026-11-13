package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.util.traceTaps

/**
 * The home screen photo key, offered in one of several caregiver-chosen placements.
 *
 * Sits in [secondaryContainer] colours — the same calm appearance as the reply row camera key —
 * so taking a picture feels like a supportive tool rather than an urgent prompt.
 *
 * @param onClick action when tapped, opening the camera screen in photo-first mode.
 * @param modifier modifier for layout and alignment.
 * @param clockTile whether this key is formatted as a 56dp square tile in the clock row,
 *   opposite the support button, rather than a full-width key.
 */
@Composable
fun PhotoHomeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    clockTile: Boolean = false
) {
    if (clockTile) {
        GentleKey(
            onClick = onClick,
            modifier = modifier
                .size(SupportButtonSize)
                .traceTaps("photo-home"),
            face = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(SupportButtonSize / 2),
            minHeight = SupportButtonSize,
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = stringResource(R.string.photo_home_button),
                modifier = Modifier.size(28.dp)
            )
        }
    } else {
        GentleKey(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .traceTaps("photo-home"),
            face = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(16.dp),
            minHeight = 64.dp,
            fillWidth = true
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.photo_home_button),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
