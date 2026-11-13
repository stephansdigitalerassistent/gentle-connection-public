package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.GroupFaces
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.GroupPicture
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.Reactions
import ch.heuscher.gentleconnect.model.SenderKind
import ch.heuscher.gentleconnect.model.senderKind
import ch.heuscher.gentleconnect.ui.DayName
import ch.heuscher.gentleconnect.ui.dayInWords
import ch.heuscher.gentleconnect.ui.dayInWordsShort
import ch.heuscher.gentleconnect.ui.dayNameFor
import ch.heuscher.gentleconnect.ui.messageInWords
import ch.heuscher.gentleconnect.ui.personName

/**
 * Section presenting unified incoming messages with zero app branding.
 */
@Composable
fun UnreadMessagesSection(
    messages: List<Message>,
    onMessageClicked: (Message) -> Unit,
    modifier: Modifier = Modifier,
    /** A face per message id, where there is one — see [ch.heuscher.gentleconnect.model.MessagePortrait]. */
    photos: Map<String, String> = emptyMap(),
    /** Each group's picture and people — see [ch.heuscher.gentleconnect.model.GroupFaces]. */
    groups: Map<GroupIdentity, GroupPicture> = emptyMap(),
    hasMore: Boolean = false,
    onMoreClicked: () -> Unit = {}
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        SectionHeading(
            text = stringResource(R.string.messages_title),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (messages.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Text(
                    text = stringResource(R.string.no_messages),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                messages.forEach { message ->
                    MessageRow(
                        message = message,
                        photoUri = photos[message.id],
                        group = message.groupIdentity?.let { groups[it] },
                        onClick = { onMessageClicked(message) }
                    )
                }
            }
        }

        // Outside the two branches above, so that it is there whichever of them drew. It used to
        // live with the list, which meant that on a morning with nothing unread the card said "no
        // messages" and the way to the ones she had already read was gone — the one morning she is
        // most likely to go looking for them.
        if (hasMore) {
            MoreRow(
                label = stringResource(R.string.show_more_messages),
                onClick = onMoreClicked,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * One message, as it looks everywhere: the home screen's short list and the full Messages
 * screen. Shared so the same message cannot look like two different things depending on how
 * far she has scrolled.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    message: Message,
    photoUri: String? = null,
    /** The group's picture and people, when this row is a several-people conversation. */
    group: GroupPicture? = null,
    onClick: () -> Unit,
    onHold: (() -> Unit)? = null
) {
    GentleKey(
        onClick = onClick,
        onLongClick = onHold,
        modifier = Modifier.fillMaxWidth(),
        // Unread is amber, which the palette already means by it: "other people — their faces,
        // and the card saying one of them wrote". It used to be surfaceVariant, #F1F5F9 against
        // a #FFFFFF card, and on the phone the two were indistinguishable — the top of the screen
        // said her daughter had written while the row for it looked exactly like the ones she had
        // already read. The nudge and the row it belongs to are now the same colour, which is
        // also what makes them recognisable as the same event.
        face = if (message.isUnread) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (message.isUnread) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = RoundedCornerShape(20.dp),
        minHeight = 72.dp,
        contentPadding = PaddingValues(16.dp),
        contentAlignment = Alignment.Start,
        fillWidth = true,
        spacing = 0.dp
    ) {
        // A face for a person, and something plainly not a face for a company. Her laundry
        // and her mobile operator were arriving with the same portrait as her daughter, and
        // the picture is what she reads first.
        //
        // And a square for several people: see [GroupAvatar]. The square is the part that
        // matters — a reply here goes to all of them — and the bare card edges behind it say
        // there are more than one. Who they are is at the top of the conversation.
        val isPerson = message.senderKind == SenderKind.PERSON

        if (message.isGroupMessage) {
            GroupAvatar(
                picture = group ?: GroupPicture(photoUri = photoUri, faces = emptyList()),
                size = 56.dp,
                edgeColor = if (message.isUnread) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
        } else if (isPerson) {
            ContactAvatar(
                photoUri = photoUri,
                name = personName(message.displayName),
                modifier = Modifier.size(48.dp)
            )
        } else {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp)
        ) {
            // Neither of these may wrap into the other. Once "Yesterday" was allowed to
            // become "Wednesday, July 22" the date was long enough that, with nothing
            // holding the two apart, it broke across two lines and printed itself against
            // the end of the name: "Reinigung!Wednesday, July 22". The name yields — it can
            // be cut and still be recognised — and the day is kept whole, because a date
            // missing its second half is worse than a name missing its tail.
            // A group with no name of its own is called after whoever wrote last and how many
            // more — the same words as the top of the conversation it opens.
            val rowTitle = if (message.isGroupMessage && message.groupName == null) {
                unnamedGroupTitle(group) ?: personName(message.displayName)
            } else {
                personName(message.displayName)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // For several people, the several people — so that what she taps and
                    // what opens agree. Who wrote is inside; which conversation it is, is
                    // what she needs out here.
                    text = rowTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (message.isUnread) FontWeight.Bold else FontWeight.Medium,
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

            // Flattened, and two lines rather than one. Her carrier's three roaming texts
            // all begin with the same sentence of boilerplate and only name the country on
            // the third line, so with the line breaks left in and one line to show, three
            // different messages read as "Surfen im Ausland mit vol…" three times over and
            // she had no way to tell them apart without opening each one.
            //
            // A row now shows the newest thing said in the conversation, which is sometimes
            // something she said — and with the other person's name above it, her own words
            // would read as theirs. So hers are introduced. Not "You:", which is the label
            // her own bubbles are forbidden; a short sentence in her own language, in the
            // same shape as "Reaction: ❤️" beneath it.
            //
            // And in a group, whose. The row is titled by the several people, so without a
            // name "Kommst du morgen?" had no author — see [GroupFaces.previewSpeaker].
            val words = messageInWords(message.text).preview()
            val speaker = GroupFaces.previewSpeaker(message)
            Text(
                text = when {
                    message.isOutgoing -> stringResource(R.string.message_preview_yours, words)
                    speaker != null -> stringResource(
                        R.string.message_preview_theirs,
                        personName(speaker),
                        words
                    )
                    else -> words
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )

            // Every reaction on the message, not the newest one: the same rule the bubble
            // follows, so a row and the conversation it opens never disagree about what
            // somebody did. Spaced apart, because at this size two emoji run together.
            // Hers first, the same order the bubble uses, so a row and the conversation it
            // opens never disagree about what she did.
            val others = Reactions.othersOf(message.reaction)
            val own = Reactions.ownOf(message.reaction)
            val reactions = if (own != null) listOf(own) + others.filter { it != own } else others
            if (reactions.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.reaction_prefix,
                        reactions.joinToString("\u2009")
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * The message as a single run of text, for the one or two lines a list row can show.
 *
 * Line breaks inside an SMS are the sender's layout, not hers; kept, they waste a whole row on
 * whatever happened to be on the first line. The same is true of a caption written across a
 * photograph, so the picture rows in [LookSection] flatten theirs through here rather than
 * keeping a second copy of the rule.
 */
internal fun String.preview(): String = trim().replace(WHITESPACE_RUN, " ")

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * How long ago, in her words.
 *
 * Past a day it says which day, rather than "Yesterday" for everything ever received. A message
 * from March reading "Yesterday" is the phone stating something false with confidence, to the one
 * person here least able to contradict it.
 *
 * "How many hours ago" is only ever said about today. It used to run to a full 24, which meant a
 * message sent yesterday evening read "23 hrs ago" in the list and "Yesterday" on the separator
 * above it in the conversation the row opened — the phone saying two different things about one
 * event, which is the disagreement [dayInWords] exists to prevent. It also made "21 hrs ago" and
 * "23 hrs ago" look like the same kind of thing when one was today and the other was not.
 *
 * The date is the short form. This is only ever drawn in the corner of a row beside a name, and
 * "Donnerstag, 13. August" there does not fit beside anything — see [dayInWordsShort].
 */
@Composable
fun formatTimeInWords(timestamp: Long): String {
    val diffMs = System.currentTimeMillis() - timestamp
    val diffMins = diffMs / (60 * 1000)
    val diffHours = diffMins / 60

    // The day decides first, so that the list and the conversation can never name it differently.
    if (dayNameFor(timestamp) != DayName.TODAY) return dayInWordsShort(timestamp)

    return when {
        diffMins < 2 -> stringResource(R.string.time_just_now)
        diffMins < 60 -> stringResource(R.string.time_minutes_ago, diffMins)
        else -> stringResource(R.string.time_hours_ago, diffHours)
    }
}
