package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R

/**
 * The two other ways of making a telephone call, above her address book: the keys, and who has
 * rung.
 *
 * They live here rather than on the bar at the bottom because the bar has four buttons and will
 * go on having four buttons — it is the one part of this phone she has learnt, and a fifth
 * entry would change the position of the other four. Everything about telephoning is behind the
 * Call button; the address book is what that button opens, and these two sit at the top of it.
 *
 * Both are labelled in words as well as drawn. A keypad glyph means "keypad" to somebody who has
 * seen a hundred apps use it, and means a pattern of dots to somebody who has not.
 *
 * Small on purpose — the height of one contact row between them, not two. The people are what
 * this screen is for, and these must not become the first thing on it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallToolsRow(
    onKeypad: () -> Unit,
    onRecentCalls: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Held down: offer to make this app the telephone.
     *
     * A caregiver's door, hidden behind the app's established caregiver gesture — the same
     * press-and-hold that corrects a name. It has to exist somewhere: the system asks the question
     * once, and a caregiver who says "not now" by accident has otherwise no way back to it short
     * of reinstalling. It is behind the keys rather than anywhere else because the keys are the
     * most telephone-like thing on the screen, and because she will never hold a button down.
     */
    onHoldKeypad: (() -> Unit)? = null,
    /**
     * Somebody rang whom her address book does not know — a call that only her list of calls
     * can show, so that is the button wearing the missed-call amber. See
     * [ch.heuscher.gentleconnect.model.MissedCallNotice.anyFromStrangers].
     */
    recentCallsMarked: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CallTool(
            icon = Icons.Default.Dialpad,
            label = stringResource(R.string.keypad_title),
            onClick = onKeypad,
            onHold = onHoldKeypad
        )
        CallTool(
            icon = Icons.Default.History,
            label = stringResource(R.string.recent_calls_title),
            onClick = onRecentCalls,
            marked = recentCallsMarked
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.CallTool(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    onHold: (() -> Unit)? = null,
    marked: Boolean = false
) {
    val content = if (marked) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onHold),
        shape = RoundedCornerShape(16.dp),
        // The container pair: in this app that is what "a thing to press" looks like, and the
        // rows below are things to read.
        colors = CardDefaults.cardColors(
            containerColor = if (marked) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        ),
        border = if (marked) BorderStroke(3.dp, MaterialTheme.colorScheme.tertiary) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(30.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = content,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
