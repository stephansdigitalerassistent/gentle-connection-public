package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import ch.heuscher.gentleconnect.model.LookItem
import ch.heuscher.gentleconnect.model.Mail
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.ui.MailItemCard
import ch.heuscher.gentleconnect.ui.messageInWords
import ch.heuscher.gentleconnect.ui.personName

/**
 * The Look section on her home screen: the newest few things that have arrived to look at.
 *
 * This was a single card reading "3 new letters". A count tells her something has arrived
 * without telling her whether it is from her sister or from an electricity company — which is
 * the only part she needs in order to decide whether to cross the room. It also made post the
 * one thing on the screen she could not see, only be told about, and the door it opened looked
 * the same whether it led to something or to nothing.
 *
 * Letters are laid out exactly as they are laid out on the Look screen. A picture is not: here
 * it is a face and a name, and the photograph itself waits on the tab. Three photographs at card
 * width would be most of her home screen, and home is meant to be the short version of
 * everything — see [ch.heuscher.gentleconnect.model.Home].
 */
@Composable
fun LookSection(
    items: List<LookItem>,
    onLetterClicked: (Mail) -> Unit,
    onOpenLookClicked: () -> Unit,
    modifier: Modifier = Modifier,
    hasMore: Boolean = false,
    /**
     * The picture she pressed, opened.
     *
     * A letter on this screen opens that letter, and a picture opened the tab and left her to
     * find it again in a list going back months — the one row on her home screen that answered a
     * press with somewhere to look rather than with the thing itself.
     */
    onPictureClicked: (Message) -> Unit = { onOpenLookClicked() }
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        SectionHeading(
            text = stringResource(R.string.look_title),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (items.isEmpty()) {
            // Still a card, and still the door to the Look screen — nothing having arrived today
            // is a fact about today, not a reason to take the room away.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Text(
                    text = stringResource(R.string.look_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items.forEach { item ->
                    when (item) {
                        is LookItem.Letter -> MailItemCard(
                            mail = item.mail,
                            photoUri = item.photoUri,
                            onClick = { onLetterClicked(item.mail) }
                        )

                        is LookItem.Picture -> PictureLine(
                            message = item.message,
                            count = item.count,
                            photoUri = item.photoUri,
                            onClick = { onPictureClicked(item.message) }
                        )
                    }
                }
            }
        }

        // Outside the two branches, like the one under her messages: nothing having come today is
        // exactly when the letters that came last week are what she is after.
        if (hasMore) {
            MoreRow(
                label = stringResource(R.string.show_more_look),
                onClick = onOpenLookClicked,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * One picture on the home screen, laid out exactly as a message is.
 *
 * Avatar, name, when it arrived, and one line of what it was. It used to read "Katrin Brunner
 * posted a picture" — three quarters of a line spent saying what the heading above it already
 * says, repeated once per row, so three photographs waiting for her were three copies of one
 * sentence with a different name buried in each.
 *
 * The shape is [MessageRow]'s, down to the size of the name, because the two sit one section
 * apart on the same screen and were visibly two different kinds of row: "Katrin Brunner" at 24sp
 * over "Clara Meier" at 20sp, which reads as a difference in importance rather than a difference
 * in what the row is.
 *
 * The second line is what she gets instead of the sentence: her sister's caption if there was
 * one, and the word for the thing itself — "Photo", "Video" — if there was not. That word is
 * carrying real weight here and nowhere else: on the Look screen the caption is dropped when the
 * photograph is on the screen beside it, because a picture does not need to be labelled "Photo"
 * while she is looking at it. Home never draws the picture, so here the word is the only thing
 * saying what is waiting on the tab. See [messageInWords] and [ch.heuscher.gentleconnect.model.Message.showsWords].
 *
 * Not [MessageRow] itself, for one reason: a message row goes amber when it is unread, and a
 * status has no unread state and must never be shown with one. Nobody is waiting for her to
 * look at these — see the Look screen, which is where that promise is kept.
 *
 * Still never "sent you". Nobody sent it to her, and with the verb gone there is no sentence
 * left to get wrong. The full wording survives where it is about something on the screen: the
 * picture's own screen, which is what a tap opens.
 */
@Composable
private fun PictureLine(
    message: Message,
    count: Int = 1,
    photoUri: String? = null,
    onClick: () -> Unit
) {
    GentleKey(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        face = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        minHeight = 72.dp,
        contentPadding = PaddingValues(16.dp),
        contentAlignment = Alignment.Start,
        fillWidth = true,
        spacing = 0.dp
    ) {
        ContactAvatar(
            photoUri = photoUri,
            name = personName(message.senderName),
            modifier = Modifier.size(48.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp)
        ) {
            // The name yields and the day is kept whole, for the reason [MessageRow] gives:
            // a name can be cut and still be recognised, and a date missing its second half
            // is worse than a name missing its tail.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = personName(message.senderName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Text(
                    text = formatTimeInWords(message.timestamp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Text(
                // How many, once there is more than one — and the caption is dropped for it.
                // A burst is thirty-five pictures with one caption somewhere in it at best;
                // showing that caption here would make the row a promise about a photograph
                // she has no way to pick out of the run behind it. What she needs on home is
                // that Markus put up a great many, which is the fact that decides whether she
                // crosses the room. Every caption is still there, on the tab, under the
                // picture it was written on.
                text = if (count > 1) {
                    pluralStringResource(R.plurals.status_picture_count, count, count)
                } else {
                    messageInWords(message.text).preview()
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
