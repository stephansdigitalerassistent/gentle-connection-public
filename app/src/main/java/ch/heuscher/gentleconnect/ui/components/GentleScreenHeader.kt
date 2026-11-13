package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.ui.AdvancedMode
import ch.heuscher.gentleconnect.ui.theme.isWideWindow

/**
 * The top of every screen that is not the home screen: a way back, the name of the screen, and
 * the "?" — in that order, in the same place, at the same size.
 *
 * Shared because three screens had begun to grow their own. Two round 56.dp buttons at the two
 * ends and the title between them is the only arrangement she has to learn.
 *
 * Tapping the title five times in 1.5 seconds toggles [AdvancedMode]. Attached to the title [Text]
 * only — never to the enclosing row or sibling buttons, so mis-aimed taps aimed at "?" or back
 * never count towards the hidden gesture.
 *
 * @param trailing anything belonging between the title and the "?" — the Post screen's
 *   read-only badge, for instance. Nothing by default.
 * @param insteadOfTitle drawn in the title's place when it is there at all, taking the whole width
 *   between the two round buttons. For the one thing that is wanted exactly when the title is not:
 *   the way back to the newest of a list she has scrolled away from — see [JumpToNewest]. A screen
 *   passing this keeps the "?" and the way back where they are on every other screen, and pays for
 *   the button with the only thing on the line that is spare.
 * @param onCaregiverGesture what happens when five fast taps land on the title. Defaults to
 *   toggling Advanced Mode. Unreachable while [insteadOfTitle] has the line, which is why what
 *   goes in there must be something that is gone again at rest.
 * @param spaceBelow the gap between this row and whatever the screen puts under it. Sixteen
 *   points everywhere by default; the conversation asks for less, because every point of band
 *   above the messages is a point of photograph she does not get to see.
 */
/** The title never shrinks past this: below it, it stops reading as the name of the screen. */
private val MinTitleSize = 22.sp

@Composable
fun GentleScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    support: SupportButtonState = SupportButtonState.None,
    onSupportPressed: () -> Unit = {},
    isRestless: Boolean = false,
    onCaregiverGesture: () -> Unit = { AdvancedMode.toggle() },
    spaceBelow: Dp = 16.dp,
    trailing: @Composable () -> Unit = {},
    insteadOfTitle: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            // Above the screen's content: the support button's "?" hangs below this row.
            .zIndex(1f)
            .fillMaxWidth()
            .padding(bottom = spaceBelow),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Green because it acts, and round like the support button at the other end. A white
        // rounded square floating on cream beside a round button read as two unrelated things.
        // A circular physical key with a lip so it clearly stands out as pressable.
        GentleKey(
            onClick = onBack,
            modifier = Modifier.width(56.dp),
            face = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = CircleShape,
            minHeight = 56.dp,
            contentPadding = PaddingValues(0.dp),
            contentAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back_to_home),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        // The title takes the room between the two round buttons and is fitted to it rather than
        // cut to it. Folded, on the cover screen of a folding phone, this header read
        // "Nachric…" — the word that says which screen she is on, ending in an ellipsis. A
        // smaller word she can read beats a bigger one she cannot.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (insteadOfTitle != null) {
                insteadOfTitle()
            } else Text(
                text = title,
                style = fitToWidth(
                    texts = listOf(title),
                    available = maxWidth,
                    // Measured in the weight it is drawn in. Measured regular and drawn bold, a
                    // title that fitted on paper came out a few letters too wide and was cut:
                    // "Monika and 7 oth…" at the top of her group, measured on a 384dp phone.
                    base = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                    minSize = MinTitleSize
                ),
                color = MaterialTheme.colorScheme.onBackground,
                // Two lines once the smallest size is reached: fitted to one line, the title
                // shrinks first; a name of her group, "Monika and 6 others", is still too wide at
                // the floor on a 384dp phone, and "Monika and / 6 others" reads where "Monika and 6
                // oth…" did not. Only a single word too long for a line is still cut.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.caregiverTaps(onCaregiverGesture)
            )
        }

        val gripMargin = if (isWideWindow()) WideScreenGripMargin else 0.dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            trailing()

            SupportButton(
                state = support,
                onClick = onSupportPressed,
                isRestless = isRestless
            )

            if (gripMargin > 0.dp) {
                Spacer(modifier = Modifier.width(gripMargin))
            }
        }
    }
}

