package ch.heuscher.gentleconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.GroupFace
import ch.heuscher.gentleconnect.model.GroupFaces
import ch.heuscher.gentleconnect.model.GroupMemberRoute
import ch.heuscher.gentleconnect.model.GroupPicture
import ch.heuscher.gentleconnect.ui.personName
import ch.heuscher.gentleconnect.util.traceTaps

/**
 * The shape of anything that belongs to several people.
 *
 * A person is a circle everywhere in this app — her favourites, her calls, a private message.
 * A group, and every face drawn *inside* a group, is a square with softened corners. The rule is
 * the whole point: the shape says "this goes to all of them" before she has read a word, and it
 * only says it if nothing outside a group is ever square.
 */
val GroupShape = RoundedCornerShape(percent = 18)

/** The thin gap drawn round a face that sits on top of another, so the two do not run together. */
private val FaceSeparation = 2.dp

/**
 * One person, as a face inside a group: square, with a thin edge in the colour behind it.
 *
 * The edge is what keeps a stack readable. Two photographs overlapping with nothing between
 * them read as one strange picture.
 */
@Composable
fun GroupMemberFace(
    face: GroupFace,
    size: Dp,
    modifier: Modifier = Modifier,
    edgeColor: Color = MaterialTheme.colorScheme.surface
) {
    Box(
        modifier = modifier
            .size(size)
            .background(edgeColor, GroupShape)
            .padding(FaceSeparation)
    ) {
        ContactAvatar(
            photoUri = face.photoUri,
            name = personName(face.name),
            modifier = Modifier.fillMaxSize(),
            shape = GroupShape,
            textStyle = TextStyle(fontSize = (size.value * 0.36f).sp)
        )
    }
}

/**
 * The picture of a group: one card in front, and the bare edges of more cards behind it.
 *
 * In front, the group's own picture, or a plain sign for several people when it has none — never
 * one of the people in it. A face in front was the last speaker's, and a square with Monika in it
 * at the head of a row titled "Monika" read as Monika, not as eight people. Behind it, one or two
 * cards with nothing on them, only their edges showing: the pile says "more than one" without a
 * face to mistake for the whole of it or a number to read. Who they are is one tap away, at the
 * top of the conversation — see [GroupMembersDialog].
 *
 * Without a picture of its own, never somebody's initials either, which is how "Familie" used to
 * become a circle with an F in it, a person called F.
 *
 * @param picture the group's picture and people; the people only set how many edges show.
 */
@Composable
fun GroupAvatar(
    picture: GroupPicture?,
    size: Dp,
    modifier: Modifier = Modifier,
    edgeColor: Color = MaterialTheme.colorScheme.surface
) {
    val photo = picture?.photoUri?.takeIf { it.isNotBlank() }
    val edges = edgesForGroupOf(picture?.faces.orEmpty().size)
    val deckStep = size * DeckEdgeShare
    // The whole pile, edges included, is exactly [size], so it sits where a single picture would.
    val cardSize = size - deckStep * edges

    Box(modifier = modifier.size(size)) {
        (edges downTo 1).forEach { k ->
            DeckEdge(
                size = cardSize,
                fill = edgeColor,
                modifier = Modifier.offset(x = deckStep * k, y = deckStep * k)
            )
        }
        Box(
            modifier = Modifier
                .size(cardSize)
                .background(edgeColor, GroupShape)
                .padding(FaceSeparation)
        ) {
            if (photo != null) {
                ContactAvatar(
                    photoUri = photo,
                    name = "",
                    shape = GroupShape,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.secondaryContainer, GroupShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(cardSize * 0.6f)
                    )
                }
            }
        }
    }
}

/**
 * Faces side by side and overlapping, the first on the left and on top.
 *
 * For a line of people rather than a pile of them: across the top of a group conversation, where
 * there is room for several. When there are more people than [max], one or two bare card edges
 * stand out behind the last face — the same hint as on [GroupAvatar], so a line that stops is not
 * read as everybody.
 *
 * @param max how many to draw. Past it, a line of faces stops being people and becomes a texture.
 */
@Composable
fun FaceRow(
    faces: List<GroupFace>,
    faceSize: Dp,
    modifier: Modifier = Modifier,
    max: Int = GroupFaces.STACK_SIZE,
    edgeColor: Color = MaterialTheme.colorScheme.surface
) {
    val shown = faces.take(max)
    if (shown.isEmpty()) return
    val hidden = faces.size - shown.size
    val edges = hiddenEdges(hidden)
    val step = faceSize * 0.7f
    val deckStep = faceSize * DeckEdgeShare * 1.5f
    val lastOffset = step * (shown.size - 1)
    val names = shown.map { personName(it.name) } +
        if (hidden > 0) listOf(othersText(hidden)) else emptyList()
    val description = names.joinToString(", ")
    Box(
        modifier = modifier
            .size(width = faceSize + lastOffset + deckStep * edges, height = faceSize)
            .semantics { contentDescription = description }
    ) {
        (edges downTo 1).forEach { k ->
            DeckEdge(
                size = faceSize,
                fill = edgeColor,
                modifier = Modifier.offset(x = lastOffset + deckStep * k)
            )
        }
        shown.indices.reversed().forEach { i ->
            GroupMemberFace(
                face = shown[i],
                size = faceSize,
                edgeColor = edgeColor,
                modifier = Modifier.offset(x = step * i)
            )
        }
    }
}

/**
 * The same people standing up the side of the screen instead of lying across the top: the group's
 * own card, then everybody in it, and the bottom of the column faded out where it runs out of room.
 *
 * For the two-column conversation — see [ch.heuscher.gentleconnect.ui.ConversationScreen]. Across
 * the top, a line of faces is worth about a third of the width and stops at five overlapping so
 * hard that the fourth is a sliver. Down the side of the reply block the faces stand apart at the
 * size of the group's own card, in the order the conversation put them, and the column holds
 * however many the window has left — four on the open Fold, fewer when anything else grows.
 *
 * **Faded, not cut.** A column that ends in half a face reads as a fault; one that ends in a face
 * going quiet reads as "there are more", which is true and is what the tap is for. The fade is
 * drawn only when there *are* more, so a group of three is three faces and nothing else.
 *
 * @param fadeInto the colour behind the column, which is what the bottom of it fades into. Wrong
 *   here and the fade is a grey smear over the last face.
 */
@Composable
fun FaceColumn(
    picture: GroupPicture,
    faceSize: Dp,
    modifier: Modifier = Modifier,
    edgeColor: Color = MaterialTheme.colorScheme.surface,
    fadeInto: Color = edgeColor
) {
    val shown = picture.faces
    val names = shown.map { personName(it.name) }
    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .semantics { contentDescription = names.joinToString(", ") }
    ) {
        // The group's card counts as one of them: it is the same square at the same size and it
        // takes the same room, so the sum has to include it or the fade never appears on a group
        // whose people fit exactly.
        val cards = 1 + shown.size
        val fits = GroupFaces.cardsInColumn(
            available = maxHeight.value,
            card = faceSize.value,
            gap = FaceColumnGap.value
        )
        Column(
            // Unbounded, so that a face with only half the room left is drawn whole and the half
            // of it that does not fit is clipped and faded. Measured against what is left instead,
            // a square face would come out as a squashed oblong of somebody.
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true),
            verticalArrangement = Arrangement.spacedBy(FaceColumnGap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GroupAvatar(picture = picture, size = faceSize, edgeColor = edgeColor)
            shown.forEach { face ->
                GroupMemberFace(face = face, size = faceSize, edgeColor = edgeColor)
            }
        }
        if (cards > fits) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(faceSize)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, fadeInto)))
            )
        }
    }
}

/** The gap between two faces standing one under the other. */
private val FaceColumnGap = 8.dp

/** How far each bare edge stands out from the card in front of it, as a share of the card. */
private const val DeckEdgeShare = 0.07f

/** How many bare edges stand for [hidden] people: none, one for one or two, two for more. */
private fun hiddenEdges(hidden: Int): Int = when {
    hidden <= 0 -> 0
    hidden <= 2 -> 1
    else -> 2
}

/**
 * How many bare edges a group's picture has behind it: one for a group of two, two for anything
 * bigger — or for a group whose people we have not been told, which is still several people.
 */
private fun edgesForGroupOf(people: Int): Int = if (people in 1..2) 1 else 2

/**
 * The side of a card in the pile: the square every face has, with nobody in it, and a line round
 * it so it reads as a thing lying there rather than as a gap.
 */
@Composable
private fun DeckEdge(size: Dp, fill: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .background(fill, GroupShape)
            .border(1.5.dp, MaterialTheme.colorScheme.outline, GroupShape)
    )
}

/**
 * What a group with no name of its own is called on her screen — "Monika und 7 weitere" — or
 * null when nobody in it can be named, and the caller keeps what it had. See
 * [GroupFaces.unnamedTitle].
 */
@Composable
fun unnamedGroupTitle(picture: GroupPicture?): String? {
    val faces = picture?.faces.orEmpty()
    val conjunction = stringResource(R.string.group_and)
    val othersFormat = stringResource(R.string.group_others)
    val oneOther = stringResource(R.string.group_one_other)
    return GroupFaces.unnamedTitle(faces, conjunction) {
        if (it == 1) oneOther else String.format(othersFormat, it)
    }
}

/** "7 others", and "1 other" rather than "1 others". */
@Composable
private fun othersText(count: Int): String =
    if (count == 1) stringResource(R.string.group_one_other)
    else stringResource(R.string.group_others, count)

/**
 * Everybody in a group, one under the other, each with their face and their name.
 *
 * What a tap on the faces at the top of a group conversation opens. The row of faces there stops
 * at five and has no names; this is where she, or whoever is sitting beside her, can see who all
 * of them are.
 *
 * ## Pressing somebody
 *
 * A group is where she most often wants one person — somebody asked her something and she wants
 * to answer them, by voice. So a row is pressable and opens [GroupMemberDialog], where that one
 * person can be rung or written to. The face and the name are one target across the whole width:
 * the name is the part she reads and aims at, and a photograph she does not recognise is a poor
 * thing to ask her to hit.
 *
 * Only the people there is some way of reaching are pressable, which [routeFor] answers. The rest
 * stay in the list with their face and their name — they are in the group and she should see
 * them — but nothing about them invites a press that would go nowhere. There is no mark on them
 * saying so, and no explanation: which of her family this app happens to have an address for is
 * not her business and could not be acted on if it were.
 *
 * @param routeFor how to reach this person on their own, or null when there is no way — see
 *   [GroupMemberRoute]. It also decides what they are called here, so that somebody whose
 *   messages arrive under a bare identity is named from her address book in the list as well as
 *   in the dialog behind it.
 * @param onMemberChosen what to open for them. The caller keeps the dialog: this list stays up
 *   behind it, so going back from one person lands on everybody again.
 */
@Composable
fun GroupMembersDialog(
    title: String,
    faces: List<GroupFace>,
    onDismiss: () -> Unit,
    routeFor: (GroupFace) -> GroupMemberRoute?,
    onMemberChosen: (GroupMemberRoute) -> Unit
) {
    AlertDialog(
        modifier = Modifier.traceTaps("group"),
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(faces, key = { it.key }) { face ->
                    val route = routeFor(face)
                    // Named and pictured from the route where there is one: it has already put
                    // her address book to the address, so somebody whose messages arrive under a
                    // bare identity is "Monika" here instead of "Someone".
                    val shown = route?.let { face.copy(name = it.name, photoUri = it.photoUri) }
                        ?: face
                    val name = personName(shown.name)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // Clipped before it is made pressable, so the ripple is the rounded
                            // rectangle the row looks like rather than a bar across the card.
                            .clip(MaterialTheme.shapes.medium)
                            .then(
                                if (route != null) {
                                    Modifier.clickable(
                                        onClickLabel = stringResource(
                                            R.string.group_member_choose,
                                            name
                                        )
                                    ) { onMemberChosen(route) }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = 8.dp, horizontal = 8.dp)
                    ) {
                        GroupMemberFace(
                            face = shown,
                            size = 52.dp,
                            edgeColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            // The name takes the rest of the row so that pressing it — which is
                            // what she will aim at — is pressing the person.
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            GentleSecondaryKey(onClick = onDismiss) {
                Text(text = stringResource(R.string.close))
            }
        }
    )
}
