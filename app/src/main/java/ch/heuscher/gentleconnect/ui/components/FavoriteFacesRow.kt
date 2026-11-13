package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.Contact

/**
 * The people she calls, one under the other.
 *
 * This was a horizontal strip, and the strip was quietly losing her family. Measured on the phone:
 * three faces fit, the row held eight, and the four people she can actually write to — Johann,
 * Laura, Sabine, Simon — were all off the right-hand edge. Nothing on screen said there was more
 * to see, and sideways scrolling is not a gesture she reaches for. Somebody could be in her
 * favourites for months without ever appearing on her phone.
 *
 * Down the screen instead: everybody is on the list, the list scrolls the way the rest of the
 * screen already scrolls, and each row has the whole width for a name rather than the width of a
 * face. The faces are smaller for it. That is the trade — a slightly smaller photograph of someone
 * against a photograph of someone she never sees.
 */
@Composable
fun FavoriteFacesRow(
    contacts: List<Contact>,
    onContactClicked: (Contact) -> Unit,
    modifier: Modifier = Modifier,
    photoSizeMultiplier: Float = 1.0f,
    hasMore: Boolean = false,
    onMoreClicked: () -> Unit = {}
) {
    if (contacts.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionHeading(
            text = stringResource(R.string.favorites_title)
        )

        contacts.forEach { contact ->
            // Everyone on this list is a favourite, so every row is starred. The same row the
            // address book draws — see ContactRow, which exists because these two had drifted.
            ContactRow(
                contact = contact,
                isFavourite = true,
                onCall = { onContactClicked(contact) },
                photoSizeMultiplier = photoSizeMultiplier
            )
        }

        // A couple of faces, and the rest under Call. Without this row a home screen showing two
        // people looks exactly like the home screen of somebody who knows two people.
        if (hasMore) {
            MoreRow(
                label = stringResource(R.string.show_more_people),
                onClick = onMoreClicked
            )
        }
    }
}
