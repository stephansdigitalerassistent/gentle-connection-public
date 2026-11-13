package ch.heuscher.gentleconnect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import ch.heuscher.gentleconnect.ui.components.FilmMark
import ch.heuscher.gentleconnect.ui.components.GentleKey
import ch.heuscher.gentleconnect.ui.components.ReadAloudKey
import ch.heuscher.gentleconnect.ui.components.LocalScrollPositionProvider
import ch.heuscher.gentleconnect.ui.components.forgivingClick
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.AlbumLayout
import ch.heuscher.gentleconnect.model.Attachment
import ch.heuscher.gentleconnect.model.LookItem
import ch.heuscher.gentleconnect.model.Mail
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.PictureSeen
import ch.heuscher.gentleconnect.model.StatusContent
import ch.heuscher.gentleconnect.model.Recipient
import ch.heuscher.gentleconnect.model.MailPresentation
import ch.heuscher.gentleconnect.model.MailProminence
import ch.heuscher.gentleconnect.model.Searching
import ch.heuscher.gentleconnect.ui.AdvancedMode
import ch.heuscher.gentleconnect.ui.components.aboveTheKeyboard
import ch.heuscher.gentleconnect.ui.components.BottomTab
import ch.heuscher.gentleconnect.ui.components.ContactAvatar
import ch.heuscher.gentleconnect.ui.components.ForwardFlow
import ch.heuscher.gentleconnect.ui.components.jumpToNewestOrNull
import ch.heuscher.gentleconnect.ui.components.NewestEnd
import ch.heuscher.gentleconnect.ui.components.rememberForwardFlow
import ch.heuscher.gentleconnect.ui.components.GentleBottomBar
import ch.heuscher.gentleconnect.ui.components.LetterAttachments
import ch.heuscher.gentleconnect.ui.components.GentleSearchField
import ch.heuscher.gentleconnect.ui.components.GentleScrollbar
import ch.heuscher.gentleconnect.ui.components.doubleTapToMagnify
import ch.heuscher.gentleconnect.ui.components.MessagePhoto
import ch.heuscher.gentleconnect.ui.components.DecodedPhoto
import ch.heuscher.gentleconnect.ui.components.StatusWords
import ch.heuscher.gentleconnect.ui.components.UnseenAmber
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.ui.components.GentleScreenHeader
import ch.heuscher.gentleconnect.ui.components.OUTSIDE_THE_LANE
import ch.heuscher.gentleconnect.ui.components.SCROLLBAR_LANE
import ch.heuscher.gentleconnect.ui.components.scrollbarFor
import ch.heuscher.gentleconnect.ui.components.formatTimeInWords
import ch.heuscher.gentleconnect.ui.components.unseenGlow
import ch.heuscher.gentleconnect.data.mail.MimeBodyExtractor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/**
 * Everything that has arrived for her to look at: the pictures her family put up, and her letters.
 *
 * This was the Post screen, and it showed "No mail" every day since it shipped — no mailbox has
 * ever been connected. Meanwhile the one thing her family actually produces, a photograph posted
 * to everybody, was arriving as a fake message and pushing her daughter off the home screen. Both
 * problems have the same answer: one place for things that arrive which she can only look at.
 *
 * Named for what she does rather than for what the things are, because no single noun covers a
 * letter and a photograph without lying about one of them. The same rule as "Listen" under a
 * voice message rather than "audio message".
 *
 * Strictly read-only, which is the promise that lets the two share a screen: nothing here can be
 * answered, deleted, or sent by mistake. A picture is never drawn as though it were sent to her,
 * and looking at one is never reported back to whoever posted it.
 */
@Composable
fun LookScreen(
    items: List<LookItem>,
    onBackToHome: () -> Unit,
    /**
     * The Home key in the bar at the bottom. Not [onBackToHome]: going back returns her to where
     * she was on home, while the Home key starts home over at the top, as the phone's own does.
     */
    onHomeTab: () -> Unit = onBackToHome,
    onMailRead: (mailId: String) -> Unit,
    onSpeakRequested: (String) -> Unit,
    onStopSpeaking: () -> Unit = {},
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
    onOpenAdvancedSheet: ((Mail) -> Unit)? = null,
    /**
     * The people a picture from here can be sent on to, newest-written-to first. Empty means
     * nobody can be reached with one, and then nothing offers to be sent on at all — see
     * `Forwarding.recentRecipients`.
     */
    forwardRecipients: List<Recipient> = emptyList(),
    /**
     * Send this picture to that person, and say afterwards whether it went. The same callback the
     * conversation uses, so that a picture her family posted and a picture her sister sent her
     * travel by exactly the same road.
     */
    onForwardRequested: ((Message, Recipient, (Boolean) -> Unit) -> Unit)? = null,
    /**
     * A picture she pressed on her home screen, to be opened the moment this screen appears.
     *
     * Home used to answer that press by opening this tab at the top of a list going back months,
     * which is somewhere to look rather than the thing she asked for. Consumed once —
     * [onStatusOpened] — so that coming back out of the picture leaves her in the list beside it
     * instead of opening it again.
     */
    openStatus: Message? = null,
    onStatusOpened: () -> Unit = {},
    seenPictureIds: Set<String> = emptySet(),
    onPictureSeen: (String) -> Unit = {},
    /**
     * Every picture of an album, marked at once when she closes the viewer — see the close below.
     */
    onPicturesSeen: (List<String>) -> Unit = { ids -> ids.forEach(onPictureSeen) },
    /**
     * Leaving the View screen marks everything on it as read — letters and pictures alike.
     * Purely local, never reported to any server or bridge.
     */
    onLeave: () -> Unit = {},
    modifier: Modifier = Modifier,
    onCallRequested: () -> Unit = onBackToHome,
    onMessagesRequested: () -> Unit = onBackToHome
) {
    val currentOnLeave by rememberUpdatedState(onLeave)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                currentOnLeave()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            currentOnLeave()
        }
    }

    val isAdvanced by AdvancedMode.isOn.collectAsState()
    var selectedMail by remember { mutableStateOf<LookItem.Letter?>(null) }
    // The picture she is looking at in the viewer, if she has one open — the picture, not the
    // album. An album is named after its newest picture, so the same person posting again while
    // she looks renames it; held by album, the viewer would shut in her hands at that moment. Held
    // by picture it finds the album again, grown by one, and she stays where she was. Moved along
    // as she turns the pages — see onPictureSeen below.
    var openPictureId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    // Sending a picture on, in the three steps the conversation uses — see [ForwardFlow]. Held
    // here rather than on the opened picture, so that the question survives her tapping through.
    val forwarding = rememberForwardFlow()
    val canForwardAtAll = onForwardRequested != null && forwardRecipients.isNotEmpty()
    var query by remember { mutableStateOf("") }

    // The message home sent her here for: only scroll to it in the list so she can see it in
    // context, rather than opening it over the whole screen. Both happen once: `openStatus` is
    // consumed and forgotten.
    //
    // Consumed only once the picture is actually in the list. The feed is collected only while
    // this screen is open, so what arrives first is the list as it was at her last visit — and a
    // picture posted since then is exactly the one home shows her first. Giving up on that stale
    // list left her wherever she had been scrolled last time; the second press worked only
    // because the list had caught up by then (observed on device).
    LaunchedEffect(openStatus, items) {
        val target = openStatus ?: return@LaunchedEffect
        val row = rowOf(items, target.id) ?: return@LaunchedEffect
        listState.scrollToItem(row)
        onStatusOpened()
    }
    // And forgotten if it never turns up — expired, or filed away — so the list cannot jump
    // under her thumb seconds later while she is already scrolling it herself.
    LaunchedEffect(openStatus) {
        if (openStatus == null) return@LaunchedEffect
        delay(OPEN_STATUS_PATIENCE_MS)
        onStatusOpened()
    }

    // Filtered before the feed is built rather than after, so that the days, the headings and the
    // ordering are all worked out from what she is actually being shown. Filtering afterwards
    // would leave a heading standing over nothing.
    //
    // As on Messages, the filter only applies while the box is on screen: search switched off on
    // the website with something still typed must not leave her post silently short.
    val displayedItems = remember(items, query, searchEnabled) {
        if (searchEnabled && query.isNotBlank()) {
            Searching.lookItems(query, items)
        } else {
            items
        }
    }

    // A letter takes the whole screen, the way opening a message does. It used to be a dialog —
    // a card floating over the list with the letter squeezed into the middle of it and a Close
    // button underneath. Two ways out of one thing (Close, and the dark edge that also dismissed
    // it), a way out that appears nowhere else in the app, and the letter itself given about a
    // third of the glass it could have had. A letter is the longest thing she reads here; it
    // should get the screen, and the way back should be the arrow that goes back everywhere else.
    val openLetter = selectedMail
    if (openLetter != null) {
        MailLetterScreen(
            mail = openLetter.mail,
            photoUri = openLetter.photoUri,
            messagesMarked = messagesMarked,
            onSpeakRequested = onSpeakRequested,
            onBack = {
                selectedMail = null
                onStopSpeaking()
            },
            onHomeRequested = onHomeTab,
            onCallRequested = onCallRequested,
            onMessagesRequested = onMessagesRequested,
            onStopSpeaking = onStopSpeaking,
            support = support,
            onSupportPressed = onSupportPressed,
            isRestless = isRestless,
            modifier = modifier
        )
        return
    }

    // The album holding the picture she is looking at, found afresh on every pass so that what
    // she sees is always what is in her post now.
    val openId = openPictureId
    if (openId != null) {
        val burstEntry = displayedItems
            .filterIsInstance<LookItem.Picture>()
            .firstOrNull { burst -> burst.messages.any { it.id == openId } }
        if (burstEntry == null) {
            // Gone from her post — the status expired, or a search no longer matches it. Back to
            // the list, cleared after this pass rather than written in the middle of drawing it.
            SideEffect { openPictureId = null }
        } else {
            BurstViewer(
                burst = burstEntry,
                startIndex = burstEntry.messages.indexOfFirst { it.id == openId }.coerceAtLeast(0),
                onPictureSeen = { id ->
                    openPictureId = id
                    onPictureSeen(id)
                },
                onClose = {
                    // Leaving the album counts as having seen all of it. She swipes through a
                    // posting and comes out of the end of it; marking only the pages she stopped
                    // on would leave amber frames on a pile she has just been through, and the
                    // mark would stop meaning "new" within a week.
                    onPicturesSeen(burstEntry.messages.map { it.id })
                    openPictureId = null
                    onStopSpeaking()
                },
                onSpeakRequested = onSpeakRequested,
                onStopSpeaking = onStopSpeaking,
                onForwardRequested = if (canForwardAtAll) {
                    { msg -> forwarding.begin(msg) }
                } else {
                    null
                },
                support = support,
                onSupportPressed = onSupportPressed,
                isRestless = isRestless
            )

            ForwardFlow(
                state = forwarding,
                recipients = forwardRecipients,
                onForwardRequested = onForwardRequested
            )
            return
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // The same four buttons as everywhere else. Her post used to be the one screen with no
        // way out but a small arrow in the corner.
        bottomBar = {
            GentleBottomBar(
                selected = BottomTab.LOOK,
                onHomeClicked = onHomeTab,
                onCallClicked = onCallRequested,
                onMessageClicked = onMessagesRequested,
                // Already here — pressing it again starts her post over at the top, as the Home
                // key does on home.
                onLookClicked = { coroutineScope.launch { listState.animateScrollToItem(0) } },
                messagesMarked = messagesMarked
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .aboveTheKeyboard(innerPadding)
                // See SCROLLBAR_LANE.
                .padding(start = 20.dp, top = 20.dp, end = SCROLLBAR_LANE, bottom = 20.dp)
        ) {
            // The shared header, like every other screen that is not home. This one grew its own
            // copy and then drifted: it never took the OUTSIDE_THE_LANE padding back, so the way
            // back and the "?" sat 14dp further right here than on Messages, Contacts and a
            // conversation — two round buttons that moved under her thumb depending on which
            // screen she was on. It had also lost the title's ellipsis and the width cap that
            // keeps a long title off the "?".
            //
            // The "Read-Only" badge that used to sit in it is gone, and is why it was hand-rolled
            // in the first place. It was English on a German screen, it was a word from a
            // developer's vocabulary, and it was an explanation of a limitation — which is the one
            // thing her screens do not carry. She cannot write mail here; the way that is said is
            // that there is nothing here to write with. Anyone who needs the reason is reading the
            // help text, which says it in a sentence.
            GentleScreenHeader(
                modifier = Modifier.padding(end = OUTSIDE_THE_LANE),
                title = stringResource(R.string.look_title),
                onBack = onBackToHome,
                support = support,
                onSupportPressed = onSupportPressed,
                isRestless = isRestless,
                // The way back to the newest of her post, in the title's place and only while
                // she is not there — this list is newest-first, so that is upwards. Counted in
                // rows, one row per item now — a card decodes one full picture and at most three
                // 400px thumbnails, so one lazy item per burst is safe again. See [JumpToNewest].
                insteadOfTitle = jumpToNewestOrNull(
                    listState = listState,
                    itemCount = displayedItems.size,
                    newestAt = NewestEnd.Top
                )
            )

            if (searchEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                GentleSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.padding(end = OUTSIDE_THE_LANE)
                )
            }

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = OUTSIDE_THE_LANE),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        // "Nothing here" and "nothing matched" are different facts; saying the
                        // first about a filtered list reads as her post having been lost.
                        text = stringResource(
                            if (query.isNotBlank()) R.string.search_nothing_found
                            else R.string.look_empty
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CompositionLocalProvider(
                        LocalScrollPositionProvider provides {
                            listState.firstVisibleItemIndex * 50_000 + listState.firstVisibleItemScrollOffset
                        }
                    ) {
                        // No arrangement spacing: the gap belongs to the item now. See [BETWEEN_THINGS].
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.padding(end = OUTSIDE_THE_LANE)
                        ) {
                            // Keyed, so that the place she is looking at is a thing rather than a
                            // row number: post arriving while she reads pushes every row down one,
                            // and without this the list stays on the row and quietly changes what is
                            // under it. See [LookItem.id].
                            displayedItems.forEach { entry ->
                                when (entry) {
                                    is LookItem.Letter -> item(key = entry.id) {
                                        Box(modifier = Modifier.padding(bottom = BETWEEN_THINGS)) {
                                            MailItemCard(
                                                mail = entry.mail,
                                                photoUri = entry.photoUri,
                                                onClick = {
                                                    onMailRead(entry.mail.id)
                                                    selectedMail = entry
                                                },
                                                onHold = if (isAdvanced && onOpenAdvancedSheet != null) {
                                                    { onOpenAdvancedSheet(entry.mail) }
                                                } else null
                                            )
                                        }
                                    }

                                    is LookItem.Picture -> item(key = entry.id) {
                                        Box(modifier = Modifier.padding(bottom = BETWEEN_THINGS)) {
                                            AlbumCard(
                                                entry = entry,
                                                seenPictureIds = seenPictureIds,
                                                onOpenViewer = { index ->
                                                    onStopSpeaking()
                                                    openPictureId = entry.messages[index].id
                                                }
                                            )
                                        }
                                    }
                                }
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
 * One letter, as it looks both here and in the short list on her home screen. Shared for the
 * same reason [ch.heuscher.gentleconnect.ui.components.MessageRow] is: the same letter must not
 * look like two different things depending on which screen she is standing on.
 *
 * Two strengths, decided by [MailPresentation.prominenceOf] and not by anything here:
 *
 *  - A letter from somebody she knows looks exactly as every letter used to look, because until
 *    now those were the only ones shown at all.
 *  - A letter from anybody else is present and legible and deliberately recessive: no raised
 *    card, no unread colour, no coloured subject line, and the address underneath instead of
 *    nothing. She reads "migros-newsletter@..." and knows what she is looking at without any
 *    label having to judge it for her — which matters, because a label reading "advertising"
 *    would eventually sit above a letter from her doctor's practice.
 *
 * The difference is weight and colour, never size: a letter she cannot read is not shown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MailItemCard(
    mail: Mail,
    photoUri: String? = null,
    onClick: () -> Unit,
    onHold: (() -> Unit)? = null
) {
    val fromSomeoneSheKnows =
        MailPresentation.prominenceOf(mail) == MailProminence.FROM_SOMEONE_SHE_KNOWS
    val standsOut = mail.isUnread && fromSomeoneSheKnows
    val sender = mail.senderName.ifBlank { mail.senderAddress }

    val shape = RoundedCornerShape(20.dp)
    GentleKey(
        onClick = onClick,
        onLongClick = onHold,
        modifier = Modifier.fillMaxWidth(),
        face = if (standsOut) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = shape,
        fillWidth = true,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ContactAvatar(
                    photoUri = photoUri,
                    name = sender,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.width(14.dp))

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = sender,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = when {
                            standsOut -> FontWeight.Bold
                            fromSomeoneSheKnows -> FontWeight.Medium
                            else -> FontWeight.Normal
                        },
                        color = if (fromSomeoneSheKnows) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Text(
                        text = formatTimeInWords(mail.timestamp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            // Who it is really from. Only for the ones she has no name for — for her daughter it
            // would be clutter, and an address under a name she knows says nothing she wants.
            if (!fromSomeoneSheKnows && mail.senderAddress.isNotBlank() && mail.senderName.isNotBlank() && mail.senderName != mail.senderAddress) {
                Text(
                    text = mail.senderAddress,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (mail.subject.isNotBlank()) {
                Text(
                    text = mail.subject,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (fromSomeoneSheKnows) FontWeight.Bold else FontWeight.Medium,
                    color = if (fromSomeoneSheKnows) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 6.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val snippet = MimeBodyExtractor.readableLetter(mail.snippet)
            if (snippet.isNotBlank()) {
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * One letter, as she reads it: the sender at the top, then whatever arrived, then the voice.
 *
 * Only the middle of it changes between a letter and a picture somebody posted — the header, the
 * way back, the four buttons at the bottom and the read-aloud button under everything are the
 * same, because they are the same on every screen in this app. Written once here rather than
 * twice, for the reason [ch.heuscher.gentleconnect.ui.components.MessageRow] is written once: two
 * copies drift, and what drifts is the position of the buttons under her thumb.
 */
@Composable
private fun LookReader(
    /** The sender's name alone, the way a conversation is titled with the person she is in it with. */
    title: String,
    /** The one line under it: a letter's subject, or what somebody did with a picture. */
    subject: String,
    /** Everything on this screen as one piece of speech, for the button at the bottom. */
    spoken: String,
    messagesMarked: Boolean,
    onSpeakRequested: (String) -> Unit,
    onBack: () -> Unit,
    onHomeRequested: () -> Unit,
    onCallRequested: () -> Unit,
    onMessagesRequested: () -> Unit,
    onStopSpeaking: () -> Unit,
    support: SupportButtonState = SupportButtonState.None,
    onSupportPressed: () -> Unit = {},
    isRestless: Boolean = false,
    modifier: Modifier = Modifier,
    photoUri: String? = null,
    avatarName: String = title,
    content: @Composable ColumnScope.() -> Unit
) {
    // Her phone's own back gesture puts this down and puts her back in her post. Without it the
    // handler around the whole app catches the gesture and sends her home, so the way out would
    // depend on whether she used the arrow or the gesture — and one of the two would lose her
    // place in a list she may have scrolled a long way down.
    BackHandler { onBack() }

    // No PinchToZoom here. The dialog this replaced needed one of its own, because a dialog is
    // its own window and the magnification wrapped around the app could not reach into it. A
    // screen is inside that wrapper already, and a second one would be a zoom within a zoom.
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // The same four buttons as every other screen she can reach. A letter is not a mode.
        bottomBar = {
            GentleBottomBar(
                selected = BottomTab.LOOK,
                onHomeClicked = onHomeRequested,
                onCallClicked = onCallRequested,
                onMessageClicked = onMessagesRequested,
                // Already in her post — pressing it again puts this down, which is the nearest
                // thing to "you are here" that does something useful.
                onLookClicked = onBack,
                messagesMarked = messagesMarked
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // See SCROLLBAR_LANE.
                .padding(start = 20.dp, top = 20.dp, end = SCROLLBAR_LANE, bottom = 20.dp)
        ) {
            // The same arrow, in the same corner, as everywhere else — which is most of the point
            // of this screen existing. It goes back to her post, not home.
            GentleScreenHeader(
                modifier = Modifier.padding(end = OUTSIDE_THE_LANE),
                // The sender's name alone. "Letter from Google" came out as "Letter from G…" —
                // the header is one line of displayMedium, and the three words in front of the
                // name cost exactly the name. What is left then tells her less than nothing.
                title = title,
                onBack = onBack,
                support = support,
                onSupportPressed = onSupportPressed,
                isRestless = isRestless
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = OUTSIDE_THE_LANE, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ContactAvatar(
                    photoUri = photoUri,
                    name = avatarName,
                    modifier = Modifier.size(48.dp)
                )

                if (subject.isNotBlank()) {
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = subject,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).doubleTapToMagnify()
                    )
                }
            }

            val bodyScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Everything the header and the read-aloud button do not need. This is the
                    // whole reason for the change: what arrived gets the glass, and it grows with
                    // the phone rather than with a height somebody chose once.
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(bodyScroll)
                        .padding(end = OUTSIDE_THE_LANE),
                    content = content
                )

                // A letter is the longest text she reads here, and the only sign that it was cut
                // off used to be the sentence stopping. A picture with three pages of PDF under
                // it has the same problem and now gets the same answer.
                Box(
                    modifier = Modifier.matchParentSize(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    GentleScrollbar(
                        bar = scrollbarFor(bodyScroll),
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(vertical = 8.dp, horizontal = 5.dp)
                    )
                }
            }

            // Fixed at the bottom, above the four buttons, so it does not scroll away down a long
            // letter. For somebody losing her sight this is how the letter actually gets read,
            // and hunting for it at the end of three screens of text is not a thing to ask.
            ReadAloudKey(
                text = spoken,
                onSpeakRequested = onSpeakRequested,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, end = OUTSIDE_THE_LANE)
            )
        }
    }
}

@Composable
private fun MailLetterScreen(
    mail: Mail,
    photoUri: String? = null,
    messagesMarked: Boolean,
    onSpeakRequested: (String) -> Unit,
    onBack: () -> Unit,
    onHomeRequested: () -> Unit,
    onCallRequested: () -> Unit,
    onMessagesRequested: () -> Unit,
    onStopSpeaking: () -> Unit,
    support: SupportButtonState = SupportButtonState.None,
    onSupportPressed: () -> Unit = {},
    isRestless: Boolean = false,
    modifier: Modifier = Modifier
) {
    // The letter, not the preview of it. `snippet` is 160 characters cut on a word boundary and
    // finished with an ellipsis — right for a list row, and for years it was also what the reader
    // showed and what read-aloud said. So the one screen whose whole purpose is reading a letter
    // stopped a paragraph in, and the voice stopped with it. `body` is the whole thing; the
    // fallback is for rows stored before it was kept and for a letter that really is that short.
    //
    // Through [MimeBodyExtractor.readableLetter] here as well as at fetch, because letters
    // already in the database are never rewritten — `insertMail` ignores conflicts and the
    // fetcher only asks for what is newer than the last one it saw. Without this, every letter
    // she has already received keeps its `[image: …]` for good.
    val letter = MimeBodyExtractor.readableLetter(mail.body.ifBlank { mail.snippet })

    // Only what can actually be drawn. A letter carrying one spreadsheet has nothing to show and
    // must not grow a heading over an empty space, nor have the voice promise anything.
    val attachments = remember(mail.attachments) { mail.attachments.filter { it.kind != null } }

    val senderTitle = mail.senderName.ifBlank { mail.senderAddress }

    val spokenLetter = stringResource(
        R.string.mail_read_aloud_format,
        senderTitle,
        mail.subject,
        letter
    )

    // The voice says that something came with the letter, because it is the one thing on this
    // screen she cannot find out by listening. For somebody losing her sight, a photograph her
    // daughter sent is otherwise silently nothing — the same failure this whole change is about,
    // moved from the screen to the speaker.
    val spoken = if (attachments.isEmpty()) {
        spokenLetter
    } else {
        spokenLetter + " " + pluralStringResource(
            R.plurals.letter_attachments_spoken,
            attachments.size,
            attachments.size
        )
    }

    LookReader(
        title = senderTitle,
        subject = mail.subject,
        spoken = spoken,
        messagesMarked = messagesMarked,
        onSpeakRequested = onSpeakRequested,
        onBack = onBack,
        onHomeRequested = onHomeRequested,
        onCallRequested = onCallRequested,
        onMessagesRequested = onMessagesRequested,
        onStopSpeaking = onStopSpeaking,
        support = support,
        onSupportPressed = onSupportPressed,
        isRestless = isRestless,
        modifier = modifier,
        photoUri = photoUri,
        avatarName = senderTitle
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.doubleTapToMagnify()
        )

        LetterAttachments(attachments = attachments)
    }
}


/**
 * The gap between two separate things in her post.
 *
 * Every item has this as bottom padding now: a burst is one album card, a letter is one card.
 */
private val BETWEEN_THINGS = 12.dp

/**
 * How long a picture pressed on home may take to appear in this list before the request is
 * dropped. The feed usually catches up within a second; this only bounds the wait.
 */
private const val OPEN_STATUS_PATIENCE_MS = 5_000L

/**
 * Which row of the list a particular picture is in.
 *
 * One row per LookItem now: a burst is drawn as a single album card with one full picture
 * and at most three small thumbnails, so one lazy item per burst is safe — a card decodes
 * one full picture and at most three 400-pixel thumbnails, not thirty-five full-width
 * photographs.
 */
private fun rowOf(items: List<LookItem>, messageId: String): Int? {
    items.forEachIndexed { index, entry ->
        when (entry) {
            is LookItem.Letter -> if (entry.mail.id == messageId) return index
            is LookItem.Picture -> if (entry.messages.any { it.id == messageId }) return index
        }
    }
    return null
}

/**
 * One burst, drawn as an album card.
 *
 * The newest status on top, exactly as a single one has always been drawn. Under it, when there is
 * more than one, the second — full width too, but in a fixed 4:3 frame so that an upright photograph
 * cannot make the card a screen tall — standing on the edges of a pile of the rest, with how many
 * said on it in words. See [AlbumLayout] for why a pile and not a row of thumbnails.
 *
 * A status that is only words is drawn as words, large, on a soft colour — never as the empty grey
 * square a picture-shaped box made of it. A burst that is nothing but words has no picture to lead
 * with and nothing worth piling up, so it is simply all of its texts, readable where they stand.
 *
 * An amber frame marks what she has not opened in the viewer, and on the second picture it stands
 * for the whole pile under it — see [unseenGlow]. A card of words alone carries none: she has read
 * it by looking at it.
 *
 * Decodes at most two full-width pictures, so one lazy item per burst stays safe — not the
 * thirty-five full-width photographs that once got this app killed.
 */
@Composable
private fun AlbumCard(
    entry: LookItem.Picture,
    seenPictureIds: Set<String>,
    onOpenViewer: (Int) -> Unit
) {
    val layout = AlbumLayout.of(entry.messages)
    val cardShape = RoundedCornerShape(20.dp)

    GentleKey(
        onClick = { onOpenViewer(0) },
        // No forgivingClick out here: the Final pass reaches this card before the pictures inside
        // it, so a wobbly touch on the pile would be rescued as "open the first picture". The lead
        // and the pile rescue their own touches.
        modifier = Modifier.fillMaxWidth(),
        face = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = cardShape,
        fillWidth = true,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AlbumHeader(entry = entry, layout = layout, seenPictureIds = seenPictureIds)

            Spacer(modifier = Modifier.height(12.dp))

            if (layout.onlyWords) {
                entry.messages.forEachIndexed { index, status ->
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                    StatusWords(
                        text = messageInWords(status.text),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(PICTURE_CORNER))
                            .clickable { onOpenViewer(index) }
                            .forgivingClick { onOpenViewer(index) }
                    )
                }
            } else {
                AlbumLead(
                    status = entry.message,
                    unseen = entry.message.id !in seenPictureIds,
                    onClick = { onOpenViewer(0) }
                )

                layout.second?.let { second ->
                    Spacer(modifier = Modifier.height(12.dp))
                    AlbumPile(
                        status = entry.messages[second],
                        edges = layout.edges,
                        more = layout.more,
                        // It stands for everything under it, so it stays marked until the last
                        // of them has been opened.
                        unseen = PictureSeen.unseenAny(entry.messages.drop(second), seenPictureIds),
                        onClick = { onOpenViewer(second) }
                    )
                }

                if (layout.hiddenTexts > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.album_more_texts,
                            layout.hiddenTexts,
                            layout.hiddenTexts
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.doubleTapToMagnify()
                    )
                }
            }
        }
    }
}

/**
 * Who, and when — and for more than one, how many, and how many of those she has not opened.
 *
 * "12 pictures" is said here as well as "10 more" on the pile, because the pile's number counts
 * what is under the second picture and this counts the album, and a person asking "how many did he
 * put up?" means this one.
 */
@Composable
private fun AlbumHeader(
    entry: LookItem.Picture,
    layout: AlbumLayout,
    seenPictureIds: Set<String>
) {
    val newest = entry.message
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(
            photoUri = entry.photoUri,
            name = personName(newest.senderName),
            modifier = Modifier.size(44.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = personName(newest.senderName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                // The newest one's time, which is when she would say it arrived.
                Text(
                    text = formatTimeInWords(newest.timestamp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            val unopened = if (layout.onlyWords) 0 else entry.messages.count { it.id !in seenPictureIds }
            if (entry.count > 1 || unopened > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.count > 1) {
                        Text(
                            text = pluralStringResource(
                                if (layout.onlyWords) R.plurals.album_text_count else R.plurals.status_picture_count,
                                entry.count,
                                entry.count
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (unopened > 0) {
                        // The same amber as the frames, so the badge and the pictures it counts
                        // are visibly one thing.
                        Text(
                            text = pluralStringResource(R.plurals.album_new_count, unopened, unopened),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier
                                .background(UnseenAmber, RoundedCornerShape(percent = 50))
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/** The newest status, drawn the way a single one always has been, with its caption under it. */
@Composable
private fun AlbumLead(status: Message, unseen: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(PICTURE_CORNER)
    val kind = StatusContent.of(status)

    if (kind == StatusContent.WORDS) {
        StatusWords(
            text = messageInWords(status.text),
            modifier = Modifier
                .fillMaxWidth()
                .unseenGlow(unseen, PICTURE_CORNER)
                .clip(shape)
                .clickable(onClick = onClick)
                .forgivingClick(onClick = onClick)
        )
        return
    }

    Box(
        modifier = Modifier
            .unseenGlow(unseen, PICTURE_CORNER)
            .clip(shape)
            .clickable(onClick = onClick)
            .forgivingClick(onClick = onClick)
    ) {
        MessagePhoto(path = status.photoPath, videoPath = status.videoPath)
        if (kind == StatusContent.FILM) FilmMark(Modifier.matchParentSize())
    }

    // Only the sender's own words. A picture still on its way has nothing but the token, and
    // "Photo" under an empty space would be this app talking, not her family.
    val caption = Attachment.captionIn(status.text)
    if (caption.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.doubleTapToMagnify()
        )
    }
}

/**
 * The second status, standing on the pile of the rest.
 *
 * The edges are drawn first and deepest first, each the picture's own size moved one step up and
 * one step right, so that the nearer one covers the further and both show only as strips along the
 * top and right of the picture — the way prints dropped on a table lie. No shadow does this work,
 * because shadows are exactly what came out too faint to see on her phone the first time.
 *
 * They went below the picture and narrowed until the 2026-09-12 revision: a pile that climbs away from
 * the picture reads as prints under it, where strips hanging off the bottom read as a stripe.
 */
@Composable
private fun AlbumPile(
    status: Message,
    edges: Int,
    more: Int,
    unseen: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(PICTURE_CORNER)
    val kind = StatusContent.of(status)

    Box(modifier = Modifier.fillMaxWidth()) {
        for (depth in edges downTo 1) {
            // The whole box is the picture plus [edges] steps above and to the right; padding it
            // by the steps this edge does not take leaves exactly the picture's size, moved.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(
                        start = PILE_STEP * depth,
                        top = PILE_STEP * (edges - depth),
                        end = PILE_STEP * (edges - depth),
                        bottom = PILE_STEP * depth
                    )
                    // Lighter than the card it sits on, not darker. Drawn in surfaceVariant first,
                    // the edges were all but invisible on her dark theme — a card and its pile were
                    // the same near-black, and the strip below the picture read as a gap rather
                    // than as paper. This is the text colour at a tenth, so it lifts off the card
                    // in either theme, with a firmer line along the edge itself.
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f - 0.04f * depth), shape)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f - 0.08f * depth), shape)
            )
        }

        Box(
            modifier = Modifier
                .padding(top = PILE_STEP * edges, end = PILE_STEP * edges)
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .unseenGlow(unseen, PICTURE_CORNER)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
                .forgivingClick(onClick = onClick)
        ) {
            if (kind == StatusContent.WORDS) {
                StatusWords(
                    text = messageInWords(status.text),
                    maxLines = 6,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                DecodedPhoto(
                    path = status.photoPath,
                    videoPath = status.videoPath,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (kind == StatusContent.FILM) FilmMark(Modifier.matchParentSize())

            if (more > 0) {
                Text(
                    text = pluralStringResource(R.plurals.album_more_pictures, more, more),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(percent = 50))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** The corners [MessagePhoto] clips a picture to, so frame, pile and photograph share one edge. */
private val PICTURE_CORNER = 16.dp

/** How far above and to the right of the one in front each edge of the pile shows. */
private val PILE_STEP = 13.dp
