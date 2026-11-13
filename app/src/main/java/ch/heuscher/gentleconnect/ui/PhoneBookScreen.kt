package ch.heuscher.gentleconnect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.CallHistory
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.MissedCallNotice
import ch.heuscher.gentleconnect.model.Nudge
import ch.heuscher.gentleconnect.model.PhoneBook
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.ui.AdvancedMode
import ch.heuscher.gentleconnect.ui.components.aboveTheKeyboard
import ch.heuscher.gentleconnect.ui.components.BottomTab
import ch.heuscher.gentleconnect.ui.components.CallToolsRow
import ch.heuscher.gentleconnect.ui.components.ConfirmCallDialog
import ch.heuscher.gentleconnect.ui.components.ContactRow
import ch.heuscher.gentleconnect.ui.components.GentleBottomBar
import ch.heuscher.gentleconnect.ui.components.GentleSearchField
import ch.heuscher.gentleconnect.ui.components.GentleScreenHeader
import ch.heuscher.gentleconnect.ui.components.GentleScrollbar
import ch.heuscher.gentleconnect.ui.components.OUTSIDE_THE_LANE
import ch.heuscher.gentleconnect.ui.components.SCROLLBAR_LANE
import ch.heuscher.gentleconnect.ui.components.scrollbarFor
import kotlinx.coroutines.launch

/**
 * Her whole address book — everyone, with the people she actually rings at the top.
 *
 * The favourites are the same faces as the home screen and all of them, not the five home has
 * room to draw; the rest of the address book follows. See [PhoneBook] for why the order is that
 * way round.
 *
 * One thing per person: call them. There used to be a speech bubble beside the telephone, but it
 * could only appear on the few rows a message could actually reach, so an otherwise uniform list
 * had a second button on three rows and not on the rest — with nothing to explain why her son had
 * one and her sister did not. Writing has its own tab; this screen means one thing.
 */
@Composable
fun PhoneBookScreen(
    contacts: List<Contact>,
    favourites: List<Contact>,
    onCallContact: (Contact) -> Unit,
    /** The keys, for a number that belongs to nobody yet. See [KeypadScreen]. */
    onKeypadRequested: () -> Unit,
    /** Who has rung, and who she has rung. See [RecentCallsScreen]. */
    onRecentCallsRequested: () -> Unit,
    /**
     * Holding a row opens that person in the telephone's own contact editor — the caregiver's
     * way to correct a spelling or add a second number, invisible to her. See
     * [ch.heuscher.gentleconnect.data.ContactEditing].
     */
    onEditContact: (Contact) -> Unit,
    /**
     * Holding the keys asks Android to make this app the telephone. See [CallToolsRow], and
     * [ch.heuscher.gentleconnect.telecom.DefaultPhoneRole] for what the role actually changes.
     */
    onBecomeTelephoneRequested: () -> Unit,
    onBackToHome: () -> Unit,
    /**
     * The Home key in the bar at the bottom. Not [onBackToHome]: going back returns her to where
     * she was on home, while the Home key starts home over at the top, as the phone's own does.
     */
    onHomeTab: () -> Unit = onBackToHome,
    onSpeakRequested: (String) -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    onMessagesRequested: () -> Unit,
    onMailRequested: () -> Unit,
    /**
     * Whether somebody has written and she has not seen it — drawn as a mark on the Message
     * button, which is on every screen. See [GentleBottomBar].
     */
    messagesMarked: Boolean = false,
    /**
     * Whether a caregiver has asked for a search box. Off for most phones — see
     * [ch.heuscher.gentleconnect.model.Searching].
     */
    searchEnabled: Boolean = false,
    support: SupportButtonState = SupportButtonState.None,
    onSupportPressed: () -> Unit = {},
    isRestless: Boolean = false,
    onOpenAdvancedSheet: ((Contact) -> Unit)? = null,
    /**
     * Every missed call still open. Each person among them wears the missed-call amber, and the
     * list of calls does when one of them is a number nobody here has. See [MissedCallNotice].
     */
    missedCalls: List<Nudge.MissedCallNudge> = emptyList(),
    /**
     * The number of the person the card on her home screen was about, when that card is how she
     * got here: the list opens at them, rather than at the top of twelve hundred names with the
     * one she came for somewhere below. [onJumped] says it has been done, so that coming back to
     * this screen later opens where she left it.
     */
    jumpToNumber: String? = null,
    onJumped: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isAdvanced by AdvancedMode.isOn.collectAsState()

    var query by remember { mutableStateOf("") }
    var contactToCall by remember { mutableStateOf<Contact?>(null) }

    // Her favourites at the top, then everybody else. See [PhoneBook] — alphabetical from A put
    // the handful of people she actually rings at whatever letter their first name happens to
    // start with, several hundred rows down.
    val shown = remember(contacts, favourites, query, searchEnabled) {
        val effectiveQuery = if (searchEnabled) query else ""
        PhoneBook.forHer(contacts, favourites, effectiveQuery)
    }

    // Back to the top whenever what she typed changes. Found on the phone: after scrolling into
    // the Ms and then searching, the results opened partway down — the list keeps its old offset
    // when its contents change, and 1254 contacts is a very long way to keep an offset from. She
    // sees a screen of names that are not the first matches and no sign that anything is above
    // them, which is exactly the case where the person she wanted is the one hoisted out of sight.
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    // Only when it has changed, though. The effect also runs each time the address book comes back into
    // the composition — back from the keys, the list of calls, a call — and then sent her to the
    // top of 1254 names from wherever she had been. The text last answered for is saveable, like
    // the scroll position; the box itself is not, so coming back with a search left behind still
    // counts as a change and still goes to the top.
    var answeredQuery by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query == answeredQuery) return@LaunchedEffect
        answeredQuery = query
        if (jumpToNumber == null) listState.scrollToItem(0)
    }

    // Who rang, by the row's key, so the marks are worked out once per change and not on every
    // row as it scrolls past.
    val rangKeys = remember(shown, missedCalls) {
        if (missedCalls.isEmpty()) {
            emptySet<String>()
        } else {
            shown.favourites.filter { MissedCallNotice.rang(it, missedCalls) }.map { "fav_${it.id}" }.toSet() +
                shown.others.filter { MissedCallNotice.rang(it, missedCalls) }.map { "all_${it.id}" }
        }
    }
    val strangerRang = remember(contacts, favourites, missedCalls) {
        missedCalls.isNotEmpty() && MissedCallNotice.anyFromStrangers(missedCalls, favourites + contacts)
    }

    // Straight to the person the card was about. Waits for the list, which on a cold start is
    // still being read when she arrives; gives up only once there is a list and they are not in
    // it — a number filed under nobody, or a search that hides them.
    LaunchedEffect(jumpToNumber, shown) {
        val number = jumpToNumber ?: return@LaunchedEffect
        if (shown.isEmpty) return@LaunchedEffect
        val matches: (Contact) -> Boolean = {
            it.phoneNumber.isNotBlank() && CallHistory.sameNumber(it.phoneNumber, number)
        }
        val inFavourites = shown.favourites.indexOfFirst(matches)
        val index = if (inFavourites >= 0) {
            inFavourites
        } else {
            val inOthers = shown.others.indexOfFirst(matches)
            val delimiter = if (shown.favourites.isNotEmpty()) 1 else 0
            if (inOthers >= 0) shown.favourites.size + delimiter + inOthers else -1
        }
        if (index >= 0) listState.scrollToItem(index)
        onJumped()
    }

    // Nothing dials on a tap any more. She has 1254 contacts sorted by first name and 48dp
    // telephones down the right-hand edge of a scrolling list, so a thumb landing slightly wrong
    // while she is looking for someone rings a stranger — and once it is ringing, she has to work
    // out how to stop it. The question is a question, not a warning: she has done nothing wrong
    // and there is nothing here to be careful about.
    contactToCall?.let { contact ->
        ConfirmCallDialog(
            name = personName(contact.name),
            onConfirm = {
                contactToCall = null
                onCallContact(contact)
            },
            onDismiss = { contactToCall = null }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            GentleBottomBar(
                selected = BottomTab.CALL,
                onHomeClicked = onHomeTab,
                // Already here — pressing it again starts the list over at the top, as the Home
                // key does on home.
                onCallClicked = { coroutineScope.launch { listState.animateScrollToItem(0) } },
                onMessageClicked = onMessagesRequested,
                onLookClicked = onMailRequested,
                messagesMarked = messagesMarked
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .aboveTheKeyboard(innerPadding)
                // Short on the right, and everything that is not the list puts the 14.dp back.
                // That leaves a strip down the edge for the scroll bar which costs the rows no
                // width at all — the name is the only thing on a row that says who the person
                // is, and it has had to be defended twice already.
                .padding(start = 20.dp, top = 20.dp, end = SCROLLBAR_LANE, bottom = 20.dp)
        ) {
            GentleScreenHeader(
                modifier = Modifier.padding(end = OUTSIDE_THE_LANE),
                title = stringResource(R.string.phonebook_title),
                onBack = onBackToHome,
                support = support,
                onSupportPressed = onSupportPressed,
                isRestless = isRestless
            )

            // The keys and the record of calls, above the people. See [CallToolsRow] for why they
            // are here and not on the bar along the bottom.
            CallToolsRow(
                onKeypad = onKeypadRequested,
                onRecentCalls = onRecentCallsRequested,
                onHoldKeypad = onBecomeTelephoneRequested,
                recentCallsMarked = strangerRang,
                modifier = Modifier.padding(end = OUTSIDE_THE_LANE, bottom = 12.dp)
            )

            // The search box is only shown when the caregiver has enabled search.
            if (searchEnabled) {
                GentleSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    label = stringResource(R.string.phonebook_search_label),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = OUTSIDE_THE_LANE, bottom = 12.dp)
                )
            }

            if (shown.isEmpty) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = OUTSIDE_THE_LANE),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(
                            // Two different nothings. An address book that could not be read at
                            // all is not the same as a name she has spelled differently from the
                            // way it is filed, and telling her "no contacts" while she can see
                            // she has hundreds would be the phone contradicting her.
                            if (query.isBlank()) {
                                R.string.phonebook_empty
                            } else {
                                R.string.phonebook_no_match
                            }
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(shown.favourites, key = { "fav_${it.id}" }) { contact ->
                            ContactRow(
                                contact = contact,
                                isFavourite = true,
                                rang = "fav_${contact.id}" in rangKeys,
                                onCall = { contactToCall = contact },
                                onHold = {
                                    if (isAdvanced && onOpenAdvancedSheet != null) {
                                        onOpenAdvancedSheet(contact)
                                    } else {
                                        onEditContact(contact)
                                    }
                                }
                            )
                        }

                        // Where her people end and the address book begins. Without it the list
                        // simply stopped being in alphabetical order partway down, which does not
                        // read as two groups — it reads as the sorting having gone wrong. A line
                        // rather than a heading, because a heading is another word to read and the
                        // rows above it already say what they are.
                        if (shown.favourites.isNotEmpty() && shown.others.isNotEmpty()) {
                            item(key = "delimiter") {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    thickness = 2.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }

                        items(shown.others, key = { "all_${it.id}" }) { contact ->
                            ContactRow(
                                contact = contact,
                                isFavourite = false,
                                rang = "all_${contact.id}" in rangKeys,
                                onCall = { contactToCall = contact },
                                onHold = {
                                    if (isAdvanced && onOpenAdvancedSheet != null) {
                                        onOpenAdvancedSheet(contact)
                                    } else {
                                        onEditContact(contact)
                                    }
                                }
                            )
                        }

                    }

                    // 1424 contacts, five on the screen at a time. Scrolled into the middle of
                    // them every screenful looks like every other one, so without this there is
                    // nothing anywhere that says whether she is near the top, near the bottom, or
                    // eight hundred names from either.
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
