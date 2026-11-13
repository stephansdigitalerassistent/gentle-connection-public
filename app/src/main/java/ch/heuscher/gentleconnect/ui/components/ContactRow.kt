package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.ui.personName
import ch.heuscher.gentleconnect.ui.theme.LocalGentleFaceScale

/**
 * A person, with a way to ring them. The same row on the home screen and in the address book.
 *
 * The two had drifted apart in every dimension that has one — corner radius, elevation, padding,
 * how big the face is, whether the name may wrap. Her son's row looked like two different things
 * depending on which screen she was standing on, which is a difference that must mean something
 * and does not.
 *
 * This is the home screen's version, which is the better of the two: a bigger face, a lighter
 * card, less padding around a row that is mostly a photograph and a name. The address book keeps
 * only the one thing that is genuinely its own — the star is not on every row there, because
 * that screen holds everybody and not only the favourites.
 *
 * @param isFavourite marks the row twice, with an amber circle and a star, because either alone
 *   is easy to miss: the circle is hidden entirely behind a contact photo, and a star on its own
 *   is a small mark on a busy row.
 * @param photoSizeMultiplier her caregiver's setting for how large faces should be.
 * @param onHold what holding the row does, when anything does. Nothing on the home screen; in
 *   the address book it opens the person in the telephone's own contact editor. Deliberately
 *   invisible: correcting a spelling is the caregiver's business, and an "edit" button on all
 *   1254 rows would put a second control on every row of a list whose whole point is that each
 *   row means one thing. See [ch.heuscher.gentleconnect.data.ContactEditing].
 * @param rang whether this person rang and the call was missed. The row then wears the amber of
 *   the missed-call card on her home screen and says so in words, so that the card and the row
 *   are recognisably the same news. See [ch.heuscher.gentleconnect.model.MissedCallNotice.rang].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactRow(
    contact: Contact,
    isFavourite: Boolean,
    onCall: () -> Unit,
    modifier: Modifier = Modifier,
    photoSizeMultiplier: Float = 1.0f,
    onHold: (() -> Unit)? = null,
    rang: Boolean = false
) {
    val displayName = personName(contact.name)
    val faceSize = (FACE_SIZE * photoSizeMultiplier.coerceIn(0.7f, 1.6f) * LocalGentleFaceScale.current).dp

    GentleKey(
        onClick = onCall,
        onLongClick = onHold,
        modifier = modifier.fillMaxWidth(),
        face = if (rang) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(16.dp),
        border = if (rang) BorderStroke(3.dp, MaterialTheme.colorScheme.tertiary) else null,
        fillWidth = true,
        contentAlignment = Alignment.Start,
        contentPadding = PaddingValues(12.dp)
    ) {
        // The whole row is a physical key that calls, so a tap anywhere on her does the usual thing.
        Box(modifier = Modifier.size(faceSize), contentAlignment = Alignment.Center) {
            ContactAvatar(
                photoUri = contact.photoUri,
                name = displayName,
                modifier = Modifier.size(faceSize),
                containerColor = if (isFavourite) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                contentColor = if (isFavourite) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }
            )

            if (isFavourite) {
                // On the corner of the circle rather than beside the name: the name is the
                // one thing on this row that tells her who the person is, and it has had to
                // be defended twice already — once when five people all rendered as
                // "Adrian …", once when the badge quietly took 8.dp of its width.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(faceSize * 0.34f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(faceSize * 0.28f)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp)
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (rang) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                // Two lines rather than one. A long name wrapping costs a little height; a long
                // name cut off costs her the ability to tell two people apart, which is what
                // happened when five different people all came out as "Adrian …".
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (rang) {
                // In words as well as in colour: amber on its own is a row that looks
                // different, and she would have to work out why.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CallMissed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(R.string.phonebook_rang),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }

        // The telephone stays a picture of a telephone rather than becoming a small badge on
        // the corner of her daughter's face. It is what the row does.
        GentleCallButton(
            contentDescription = stringResource(R.string.call_action, displayName),
            onClick = onCall
        )
    }
}

/** How big a face is before her caregiver's multiplier. The home screen's size, which won. */
private const val FACE_SIZE = 64f
