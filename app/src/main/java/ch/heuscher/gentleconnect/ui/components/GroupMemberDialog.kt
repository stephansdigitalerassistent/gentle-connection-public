package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.GroupMemberRoute
import ch.heuscher.gentleconnect.ui.personName
import ch.heuscher.gentleconnect.util.traceTaps

/**
 * How long one person out of several waits to be chosen.
 *
 * Half again as long as "Shall I ring Karolina?" ([ConfirmCallDialog]), because there is more
 * here to read: a face, a name and two things that could be done with them, rather than five
 * words and a yes. Running out does nothing at all — see [gentleDialogOpacity].
 */
private const val MEMBER_QUESTION_TIMEOUT_MILLIS = 30_000

/**
 * One person out of several, and the two things she can do with them.
 *
 * ## Why this exists
 *
 * A group is where she most often wants *one* person. Somebody asked her something in the family
 * conversation, and what she wants is to answer that person — usually by telephone, because
 * speaking is what she is best at. Until this dialog, the list of everybody in the group was a
 * list and nothing else: faces, names, and a button that closed it.
 *
 * ## Why a dialog and not two buttons on every row
 *
 * A telephone and a pencil down the right-hand edge of a scrolling list of people is the exact
 * arrangement [ContactRow] refuses, and for two separate reasons. One row means one thing, so
 * that a tap anywhere on somebody does the usual thing and nothing else is competing for her
 * thumb. And a small control at the edge of a list she is scrolling is what put a call through
 * to a stranger in her address book — the reason nothing in this app dials on a single tap.
 *
 * So: press the person, and then choose, on a screen that holds nothing but them.
 *
 * ## Why there is no second question before it rings
 *
 * This *is* the question [ConfirmCallDialog] asks. It shows her the face, it names the person in
 * the button itself, and it waits and then leaves the same way. A third screen between her and
 * her daughter would be one more thing to get through for somebody who has already said, twice,
 * who she means.
 *
 * ## Going back, and being walked away from
 *
 * The button goes back to everybody, because that is where she pressed from and she may have
 * pressed the wrong face. The clock does not: it closes the lot. An unanswered question here
 * means the phone has been put down, and landing that on a list of eight people with no clock of
 * its own would leave the phone modal and unusable until somebody else picked it up — which is
 * the whole of what [gentleDialogOpacity] exists to prevent.
 *
 * ## What is offered
 *
 * Only what will actually work. [GroupMemberRoute] says whether there is a number or an identity
 * to ring and whether anything can carry a message, and a button that is not backed by one of
 * those is not drawn — a name she can press that then fails is worse than one she cannot press.
 * Somebody with neither never reaches this dialog at all; their row is not pressable.
 */
@Composable
fun GroupMemberDialog(
    route: GroupMemberRoute,
    onRing: () -> Unit,
    onWrite: () -> Unit,
    onPhoto: (() -> Unit)? = null,
    onBack: () -> Unit,
    onWalkedAway: () -> Unit
) {
    val opacity = gentleDialogOpacity(MEMBER_QUESTION_TIMEOUT_MILLIS, onWalkedAway)
    val name = personName(route.name)

    AlertDialog(
        // The whole of it fades as one, like every other question this app asks.
        modifier = Modifier.graphicsLayer { alpha = opacity }.traceTaps("member"),
        onDismissRequest = onBack,
        // Her face above her name: the same square she was pressed on, at the size a face wants
        // to be for eyes that are no longer good. It is the answer to "is this the right person?"
        // and it has to be answerable at a glance.
        icon = {
            GroupMemberFace(
                face = route.face.copy(name = route.name, photoUri = route.photoUri),
                size = 88.dp,
                edgeColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        },
        title = {
            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Ringing first and in the fuller colour: it is what she came for nine times out
                // of ten, and the one of the two she can do without reading or writing anything.
                if (route.canRing) {
                    MemberChoice(
                        label = stringResource(R.string.group_member_ring, name),
                        onClick = onRing,
                        face = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                }
                if (route.canWrite) {
                    MemberChoice(
                        label = stringResource(R.string.group_member_write, name),
                        onClick = onWrite,
                        face = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                if (onPhoto != null) {
                    MemberChoice(
                        label = stringResource(R.string.group_member_photo, name),
                        onClick = onPhoto,
                        face = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        },
        // The way out, in the place and the words every other dialog here puts it.
        confirmButton = {
            GentleSecondaryKey(onClick = onBack, minHeight = 48.dp) {
                Text(
                    text = stringResource(R.string.close),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    )
}

/**
 * One of the two things, across the whole width.
 *
 * Full width and tall, because these are the only two targets on the screen and there is no
 * reason for either to be small. Two lines allowed: a name and a verb in German is longer than
 * it is in English, and a name cut in half is the one thing on here she cannot afford to misread.
 */
@Composable
private fun MemberChoice(
    label: String,
    onClick: () -> Unit,
    face: Color,
    contentColor: Color
) {
    GentleKey(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        face = face,
        contentColor = contentColor,
        minHeight = 64.dp,
        fillWidth = true,
        contentAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}
