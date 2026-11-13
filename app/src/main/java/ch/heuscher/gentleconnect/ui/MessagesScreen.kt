package ch.heuscher.gentleconnect.ui

import androidx.compose.foundation.layout.PaddingValues
import ch.heuscher.gentleconnect.ui.components.GentleKey
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.ContactSearch
import ch.heuscher.gentleconnect.model.GroupFaces
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.GroupPicture
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.ui.AdvancedMode
import ch.heuscher.gentleconnect.ui.components.aboveTheKeyboard
import ch.heuscher.gentleconnect.ui.components.BottomTab
import ch.heuscher.gentleconnect.ui.components.ContactAvatar
import ch.heuscher.gentleconnect.model.Searching
import ch.heuscher.gentleconnect.ui.components.GentleBottomBar
import ch.heuscher.gentleconnect.ui.components.GentleSearchField
import ch.heuscher.gentleconnect.ui.components.GentleScreenHeader
import ch.heuscher.gentleconnect.ui.components.GentleScrollbar
import ch.heuscher.gentleconnect.ui.components.jumpToNewestOrNull
import ch.heuscher.gentleconnect.ui.components.NewestEnd
import ch.heuscher.gentleconnect.ui.components.MessageRow
import ch.heuscher.gentleconnect.ui.components.OUTSIDE_THE_LANE
import ch.heuscher.gentleconnect.ui.components.SCROLLBAR_LANE
import ch.heuscher.gentleconnect.ui.components.scrollbarFor
import kotlinx.coroutines.launch

/**
 * Every message she has, from every service, in one list.
 *
 * The home screen shows the newest handful; this is the whole thing. Nothing here says which
 * app a message came in on — an SMS, a Threema message and a WhatsApp message are the same kind
 * of object on this screen, distinguished only by who sent them.
 *
 * Messages, and nothing else. There used to be a "write to someone" button above the list, on
 * the reasoning that answering someone and starting something new are different enough to
 * deserve separate doors. In front of the list it was instead the first and largest thing on the
 * screen, and it led to a picker — a list of names that is not the list of messages she came
 * here for, and that looks enough like one to be mistaken for it.
 *
 * She still writes to people: she opens what they wrote her and answers there, which is how the
 * screen already worked and needs nothing explained.
 *
 * After the messages come the people she could write to and has never heard from here, in the
 * order the telephone lists them, under a line that says why there is nothing from them. Without
 * it somebody she has only ever rung was simply absent from this screen, and looking for them
 * here she found nothing and no reason. See [ch.heuscher.gentleconnect.model.NotYetWritten].
 */
@Composable
fun MessagesScreen(
    messages: List<Message>,
    onMessageClicked: (Message) -> Unit,
    onBackToHome: () -> Unit,
    /**
     * The Home key in the bar at the bottom. Not [onBackToHome]: going back returns her to where
     * she was on home, while the Home key starts home over at the top, as the phone's own does.
     */
    onHomeTab: () -> Unit = onBackToHome,
    onSpeakRequested: (String) -> Unit,
    onStopSpeaking: () -> Unit,
    onPhoneBookRequested: () -> Unit,
    onMailRequested: () -> Unit,
    /**
     * A face per message id, where there is one. The home screen's short list is handed the same
     * map — see [ch.heuscher.gentleconnect.model.MessagePortrait] for why it is resolved once.
     */
    photos: Map<String, String> = emptyMap(),
    /** The picture and faces of each several-people conversation — see [GroupFaces]. */
    groups: Map<GroupIdentity, GroupPicture> = emptyMap(),
    /**
     * Whether somebody has written and she has not seen it — drawn as a mark on the Message
     * button, which is on every screen. See [GentleBottomBar].
     */
    messagesMarked: Boolean = false,
    /**
     * Whether a caregiver has asked for a search box. Off for most phones — see
     * [ch.heuscher.gentleconnect.model.Searching] for why this is somebody's decision and not a
     * default.
     */
    searchEnabled: Boolean = false,
    onOpenAdvancedSheet: ((Message) -> Unit)? = null,
    support: ch.heuscher.gentleconnect.model.SupportButtonState = ch.heuscher.gentleconnect.model.SupportButtonState.None,
    onSupportPressed: () -> Unit = {},
    isRestless: Boolean = false,
    /** Everybody she could write to with no conversation yet, in the phone book's order. */
    notYetWrittenTo: List<Contact> = emptyList(),
    /** Pressing one of them: the line to write on, addressed to them. */
    onWriteToContact: (Contact) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isAdvanced by AdvancedMode.isOn.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }

    // The box is drawn from `searchEnabled`, but the filtering is not: it also insists the box is
    // actually there. Otherwise switching search off on the website while she happens to have
    // something typed would hide most of her messages behind a filter with nothing on screen to
    // explain it and no way left to clear it.
    val shown = if (searchEnabled) Searching.messages(query, messages) else messages
    // The same box finds a person below the messages the way it finds one in the phone book.
    val people = remember(notYetWrittenTo, query, searchEnabled) {
        if (searchEnabled && query.isNotBlank()) {
            notYetWrittenTo.filter { ContactSearch.matches(it.name, query) }
        } else {
            notYetWrittenTo
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            GentleBottomBar(
                selected = BottomTab.MESSAGE,
                onHomeClicked = onHomeTab,
                onCallClicked = onPhoneBookRequested,
                // Already here — pressing it again starts the list over at the top, as the Home
                // key does on home.
                onMessageClicked = { coroutineScope.launch { listState.animateScrollToItem(0) } },
                onLookClicked = onMailRequested,
                messagesMarked = messagesMarked
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .aboveTheKeyboard(innerPadding)
                // See SCROLLBAR_LANE: the strip on the right is the bar's, and everything that
                // is not the list puts the width back.
                .padding(start = 20.dp, top = 20.dp, end = SCROLLBAR_LANE, bottom = 20.dp)
        ) {
            GentleScreenHeader(
                modifier = Modifier.padding(end = OUTSIDE_THE_LANE),
                title = stringResource(R.string.messages_title),
                onBack = onBackToHome,
                support = support,
                onSupportPressed = onSupportPressed,
                isRestless = isRestless,
                // The way back to the newest, in the title's place and only while she is not
                // there — this list is newest-first, so that is upwards. See [JumpToNewest].
                insteadOfTitle = jumpToNewestOrNull(
                    listState = listState,
                    itemCount = shown.size,
                    newestAt = NewestEnd.Top
                )
            )

            // The gap the removed button used to leave behind it. Without it the first message
            // sits directly under the title and reads as part of the heading.
            Spacer(modifier = Modifier.height(16.dp))

            if (searchEnabled) {
                GentleSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.padding(end = OUTSIDE_THE_LANE)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (shown.isEmpty() && people.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = OUTSIDE_THE_LANE),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        // Two different facts, and saying the wrong one is unkind: "no messages"
                        // to somebody whose messages are merely filtered reads as having lost
                        // them.
                        text = stringResource(
                            if (query.isNotBlank()) R.string.search_nothing_found
                            else R.string.no_messages
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.padding(end = OUTSIDE_THE_LANE),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown) { message ->
                            MessageRow(
                                message = message,
                                photoUri = photos[message.id],
                                group = message.groupIdentity?.let { groups[it] },
                                onClick = { onMessageClicked(message) },
                                onHold = if (isAdvanced && onOpenAdvancedSheet != null) {
                                    { onOpenAdvancedSheet(message) }
                                } else null
                            )
                        }

                        if (people.isNotEmpty()) {
                            // Why these rows have no message on them. Said once, above them, in
                            // her words: a face with no text under it on a list of messages looks
                            // like a message that has been lost.
                            item(key = "not_yet_written") {
                                Text(
                                    text = stringResource(R.string.messages_not_yet_written),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
                                )
                            }
                            // No key: one id can be both a favourite and an address-book row, and a repeated key
                            // takes the whole screen down.
                            items(people) { contact ->
                                WriteToRow(contact = contact, onClick = { onWriteToContact(contact) })
                            }
                        }
                    }

                    GentleScrollbar(
                        bar = scrollbarFor(listState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

/**
 * Somebody she has no messages with: their face and name, and a pencil saying what pressing them
 * does. The whole row is the target, as in the phone book.
 */
@Composable
private fun WriteToRow(contact: Contact, onClick: () -> Unit) {
    val displayName = personName(contact.name)
    GentleKey(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        face = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(12.dp),
        contentAlignment = Alignment.Start,
        fillWidth = true,
        spacing = 0.dp
    ) {
        ContactAvatar(
            photoUri = contact.photoUri,
            name = displayName,
            modifier = Modifier.size(56.dp)
        )
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp)
        )
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = stringResource(R.string.group_member_write, displayName),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp)
        )
    }
}
