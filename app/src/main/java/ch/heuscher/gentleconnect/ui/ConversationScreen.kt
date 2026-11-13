package ch.heuscher.gentleconnect.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.ConversationBuilder
import ch.heuscher.gentleconnect.data.ConversationItem
import ch.heuscher.gentleconnect.data.SendRetryPolicy
import ch.heuscher.gentleconnect.data.SendStatePolicy
import ch.heuscher.gentleconnect.model.ConversationScroll
import ch.heuscher.gentleconnect.model.GroupFace
import ch.heuscher.gentleconnect.model.GroupFaces
import ch.heuscher.gentleconnect.model.GroupMemberRoute
import ch.heuscher.gentleconnect.model.GroupPicture
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.PictureFit
import ch.heuscher.gentleconnect.model.PictureFlood
import ch.heuscher.gentleconnect.model.Reactions
import ch.heuscher.gentleconnect.model.SpokenReactions
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.speech.EmojiNames
import ch.heuscher.gentleconnect.ui.AdvancedMode
import ch.heuscher.gentleconnect.ui.components.BottomTab
import ch.heuscher.gentleconnect.data.Forwarding
import ch.heuscher.gentleconnect.model.Recipient
import ch.heuscher.gentleconnect.ui.components.ComposeMessageDialog
import ch.heuscher.gentleconnect.ui.components.ConfirmCallDialog
import ch.heuscher.gentleconnect.ui.components.ContactAvatar
import ch.heuscher.gentleconnect.ui.components.FaceColumn
import ch.heuscher.gentleconnect.ui.components.FaceRow
import ch.heuscher.gentleconnect.ui.components.FilmPlayer
import ch.heuscher.gentleconnect.ui.components.GentleBottomBar
import ch.heuscher.gentleconnect.ui.components.GentleScreenHeader
import ch.heuscher.gentleconnect.ui.components.GentleScrollbar
import ch.heuscher.gentleconnect.ui.components.GroupAvatar
import ch.heuscher.gentleconnect.ui.components.GroupMemberDialog
import ch.heuscher.gentleconnect.ui.components.GroupMemberFace
import ch.heuscher.gentleconnect.ui.components.GroupMembersDialog
import ch.heuscher.gentleconnect.ui.components.GroupShape
import ch.heuscher.gentleconnect.ui.components.HoldToResend
import ch.heuscher.gentleconnect.ui.components.MessagePhoto
import ch.heuscher.gentleconnect.ui.components.MessageVideo
import ch.heuscher.gentleconnect.ui.components.MessageVoice
import ch.heuscher.gentleconnect.ui.components.ReadAloudKey
import ch.heuscher.gentleconnect.ui.components.NewestEnd
import ch.heuscher.gentleconnect.ui.components.OUTSIDE_THE_LANE
import ch.heuscher.gentleconnect.ui.components.PhotoViewer
import ch.heuscher.gentleconnect.ui.components.QuotedMessageCard
import ch.heuscher.gentleconnect.ui.components.ReplyControls
import ch.heuscher.gentleconnect.ui.components.SCROLLBAR_LANE
import ch.heuscher.gentleconnect.ui.components.aboveTheKeyboard
import ch.heuscher.gentleconnect.ui.components.doubleTapToMagnify
import ch.heuscher.gentleconnect.ui.components.jumpToNewestOrNull
import ch.heuscher.gentleconnect.ui.components.forgivingClick
import ch.heuscher.gentleconnect.ui.components.scrollbarFor
import ch.heuscher.gentleconnect.ui.components.unnamedGroupTitle
import ch.heuscher.gentleconnect.ui.components.rememberZoomAtTap
import ch.heuscher.gentleconnect.ui.theme.isWideWindow

/**
 * One person, everything they have written to each other.
 *
 * The word on her screen is still "Messages" — this is a conversation only in this file's name and
 * in the code. She reaches it by tapping a message, which is the gesture she already has.
 *
 * Oldest at the top and newest at the bottom, which is the opposite of the flat message list and
 * the right way round for something being read as a back-and-forth. Nothing here says which service
 * carried anything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConversationScreen(
    title: String,
    messages: List<Message>,
    canReply: Boolean,
    onReactionSelected: (String) -> Unit,
    onTextReplySubmitted: (String) -> Unit,
    onResendRequested: (Message) -> Unit,
    /** Which voice message is sounding, so its control can say so. Null when the app is quiet. */
    playingVoiceMessage: String?,
    /** Start this message's voice message, or stop it if it is the one already playing. */
    onVoiceMessageToggled: (Message) -> Unit,
    /**
     * A film is about to fill the screen, so whatever the app is saying out loud must stop.
     *
     * The same rule voice messages already follow: one voice at a time. A film playing over the
     * top of this phone reading a message aloud is two people talking, and she cannot tell which
     * control silenced which.
     */
    onFilmStarted: () -> Unit,
    onBack: () -> Unit,
    onBackToHome: () -> Unit,
    /**
     * The Home key in the bar at the bottom. Not [onBackToHome]: going back returns her to where
     * she was on home, while the Home key starts home over at the top, as the phone's own does.
     */
    onHomeTab: () -> Unit = onBackToHome,
    onSpeakRequested: (String) -> Unit,
    onStopSpeaking: () -> Unit = {},
    onPhoneBookRequested: () -> Unit,
    onMailRequested: () -> Unit,
    /**
     * Whether somebody has written and she has not seen it — drawn as a mark on the Message
     * button, which is on every screen. See [GentleBottomBar].
     */
    messagesMarked: Boolean = false,
    /**
     * Whether the last of the five faces opens the rest of them rather than sending the laughing
     * face. The caregiver's switch — see [ch.heuscher.gentleconnect.model.RemoteSettings.enableEmojiChoice].
     */
    emojiChoiceEnabled: Boolean = false,
    support: SupportButtonState = SupportButtonState.None,
    onSupportPressed: () -> Unit = {},
    isRestless: Boolean = false,
    onOpenAdvancedSheet: ((Message) -> Unit)? = null,
    /**
     * Her correspondent's face, for the strip beside a conversation with one person.
     *
     * Null is an ordinary answer — somebody with no photograph anywhere — and then the strip
     * draws their initials, which is what [ContactAvatar] does everywhere else in the app.
     */
    personPhoto: String? = null,
    /**
     * Ring her correspondent, or null when there is no way to ring them.
     *
     * What makes the face beside a conversation with one person pressable. Always confirmed
     * before anything dials — see [ConfirmCallDialog]. Null when the person has neither a
     * telephone number nor a Threema identity, and then the face stays still: no ripple, no
     * hint, nothing saying she can press something that cannot be rung.
     */
    onRingPerson: (() -> Unit)? = null,
    /**
     * The people in it, when this conversation is several people — null when it is one.
     *
     * Everything that makes a group look like a group hangs off this: the faces under the title,
     * a square face where each person starts speaking, and the people in front of the reply,
     * framed, so that answering all of them never looks like answering one. See [GroupFaces].
     */
    groupPicture: GroupPicture? = null,
    /**
     * How to reach one person in the group on their own, or null when there is no way to.
     *
     * What makes the people in the list of everybody pressable. Null is an ordinary answer for
     * somebody the messenger named but gave no address for, and then their row is not pressable —
     * see [GroupMemberRoute].
     */
    memberRoute: (GroupFace) -> GroupMemberRoute? = { null },
    /** Ring that one person. Already confirmed: [GroupMemberDialog] names them in the button. */
    onRingMember: (GroupMemberRoute) -> Unit = {},
    /** Send that one person this, away from the group — everyone else sees nothing. */
    onWriteToMember: (GroupMemberRoute, String) -> Unit = { _, _ -> },
    /** Open the camera to send a photo to this conversation — one person or the whole group. */
    onCameraRequested: (() -> Unit)? = null,
    /** Open the camera to send a photo to this member of a group. */
    onPhotoToMember: ((Recipient) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isAdvanced by AdvancedMode.isOn.collectAsState()

    val listState = rememberLazyListState()

    // The film she is watching, if she is watching one. Held here rather than in the bubble so
    // that there is exactly one player in existence: a second film starting while the first is
    // still sounding is the failure this whole screen is arranged to prevent.
    var playingFilm by remember { mutableStateOf<String?>(null) }

    // The photograph she has opened full screen to see it larger, if any.
    var viewerPhoto by remember { mutableStateOf<String?>(null) }

    // The three steps of "one of these people": everybody, then one of them, then writing to
    // that one. Held here, and only here, because both ways in — the faces down the side of a
    // wide window and the strip across the top of a phone — must open the same one stack. They
    // are separate states rather than one, so that closing the person leaves everybody up behind
    // them and she is back where she pressed from rather than back in the conversation.
    var showingMembers by remember { mutableStateOf(false) }
    var chosenMember by remember { mutableStateOf<GroupMemberRoute?>(null) }
    var writingToMember by remember { mutableStateOf<GroupMemberRoute?>(null) }
    // Asking whether to ring her correspondent. A face on its own must never dial on a single
    // tap — see [ConfirmCallDialog].
    var confirmingCall by remember { mutableStateOf(false) }

    val items = remember(messages) { ConversationBuilder.build(messages, ::startOfDay) }
    // Which pictures of a run are drawn small, so that a camera roll emptied into this
    // conversation cannot decode itself into the memory this launcher has to live in — see
    // [PictureFlood.shrunken]. Nothing is hidden or reordered; the fourth picture of a run and
    // everything after it is simply smaller.
    val shrunkenPictures = remember(messages) { PictureFlood.shrunken(messages) }

    // Whether she has moved the conversation herself, which stops it being moved for her — see
    // [ConversationScroll]. Read from the drag rather than from the scroll position, because the
    // effect below scrolls this list too and must not take its own work for hers.
    var sheHasScrolled by remember(listState) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) sheHasScrolled = true
        }
    }

    // The newest message is the one she came for, so the conversation opens on it rather than on
    // whatever was said first, months ago.
    //
    // On its *end*, not its start. Scrolling to the item put the top of the last message at the
    // top of the screen, which for anything longer than a few lines left the rest of it — and its
    // "Read aloud" button — below the fold, with nothing to say they were there. She had opened a
    // message and been shown the beginning of it. The offset is clamped to the end of the list, so
    // for a short message this is exactly where it was before.
    //
    // And then again each time that bubble grows underneath us. It is rarely its final height when
    // this first runs: a still is still downloading and a film's length is still being read off
    // the disk, and both land a moment later and push the bottom of the message off the screen —
    // a film whose play button she never saw. [ConversationScroll] holds the rule for when that is
    // followed and, more importantly, when it stops.
    LaunchedEffect(listState, items.size) {
        if (items.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(items.lastIndex, Int.MAX_VALUE)

        var followedAt: Int? = null
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull { it.index == items.lastIndex }?.size
        }.collect { height ->
            if (ConversationScroll.shouldFollowToEnd(height, followedAt, sheHasScrolled)) {
                followedAt = height
                listState.scrollToItem(items.lastIndex, Int.MAX_VALUE)
            }
        }
    }

    // And the keyboard takes its half of the screen off the bottom, where the message she is
    // answering was. The list is scrolled by whatever the window loses, so the bottom edge stays
    // where she left it — see [ConversationScroll.shrunkBy]. Watched all the way through the
    // keyboard's animation rather than once at the end, because each frame of it takes a little
    // more room away.
    //
    // No "has she scrolled" here, unlike the effect above: this never decides where to go, only
    // that what she is already looking at should stay where it is. Her thumb still wins while it
    // is down, though — a list she is dragging is hers, and a scroll sent into somebody else's
    // drag is refused by the scroll mutex, which would end this effect for the rest of the
    // conversation rather than for the one frame it was refused in.
    LaunchedEffect(listState) {
        var previousHeight: Int? = null
        snapshotFlow {
            listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        }.collect { height ->
            val lost = ConversationScroll.shrunkBy(previousHeight, height)
            previousHeight = height
            if (lost > 0 && !listState.isScrollInProgress) listState.scrollBy(lost.toFloat())
        }
    }

    // While she is typing, the keyboard has nearly half the screen and the conversation is paying
    // for it. The room this screen keeps for itself is given back for as long as that lasts: the
    // strip above the title, and the gap between the last message and the reply block.
    val keyboardUp = WindowInsets.isImeVisible

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            GentleBottomBar(
                selected = BottomTab.MESSAGE,
                onHomeClicked = onHomeTab,
                onCallClicked = onPhoneBookRequested,
                onMessageClicked = onBack,
                onLookClicked = onMailRequested,
                messagesMarked = messagesMarked
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .aboveTheKeyboard(innerPadding)
                // See SCROLLBAR_LANE.
                .padding(start = 20.dp, end = SCROLLBAR_LANE)
                // Twelve rather than the twenty the other screens keep. The band above the
                // first bubble — status bar, this padding, the round buttons, the gap under
                // them — came to about a hundred and twenty points, and on a wide window that is
                // height the photographs are paying for. See [PictureFit].
                .padding(top = if (keyboardUp) 4.dp else 12.dp)
        ) {
            // A group with no name of its own is called after whoever wrote last *and how many
            // more*. Titled with the last speaker alone, eight people read as Monika.
            val groupNamed = messages.any { it.groupName != null }
            val shownTitle = if (groupPicture != null && !groupNamed) {
                unnamedGroupTitle(groupPicture) ?: personName(title)
            } else {
                personName(title)
            }
            GentleScreenHeader(
                modifier = Modifier.padding(end = OUTSIDE_THE_LANE),
                spaceBelow = 8.dp,
                // Through the same rule as everywhere else, so a conversation is headed by the
                // name the list called it — and never by a bare identity, which is what the top
                // of a conversation with somebody she has no contact for used to say.
                title = shownTitle,
                onBack = onBack,
                support = support,
                onSupportPressed = onSupportPressed,
                isRestless = isRestless,
                // A conversation is read downwards like a letter, so here the newest is at the
                // *bottom* — the one screen where the way back to it points the other way. It is
                // drawn only once she has scrolled back into what was said weeks ago, which is
                // also the only state this screen does not scroll her out of by itself. See
                // [JumpToNewest] and [ConversationScroll].
                insteadOfTitle = jumpToNewestOrNull(
                    listState = listState,
                    itemCount = items.size,
                    newestAt = NewestEnd.Bottom
                )
            )

            // Whether this window is wide enough to stand the people beside the conversation.
            // Asked once, here, because two things have to agree about it: the faces go across
            // the top or down the left-hand side, and never both.
            //
            // Nothing to do with whether she can answer. The reply is one line under the
            // conversation on every window now — a letter she can read but not answer simply has
            // no line, and the people beside it stay where they are.
            val besideTheMessages = isWideWindow()

            // Who is in it, under the name — the group's own picture if it has one, then the
            // people. Given up while she types, with the rest of this screen's spare room.
            //
            // Only where the faces have nowhere better to be. On a wide window they stand down
            // the left-hand side instead, and a strip across the top as well would be the same
            // people twice, in a band of height the messages are paying for.
            if (groupPicture != null && !keyboardUp && !besideTheMessages) {
                GroupHeaderStrip(
                    picture = groupPicture,
                    onShowMembers = { showingMembers = true },
                    modifier = Modifier.padding(end = OUTSIDE_THE_LANE, bottom = 8.dp)
                )
            }

            // The conversation itself, and the bar saying how far back it goes.
            //
            // Handed where it sits rather than deciding: on a phone it is the whole width under
            // the title, and on a window wide enough for two columns it is the left-hand one. The
            // insides are the same either way — the lane the scroll bar lives in is measured from
            // this block's own right edge, which is the screen on one and the middle on the other.
            val theConversation: @Composable (Modifier, Dp) -> Unit = { placed, widestPicture ->
                Box(
                    modifier = placed
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = OUTSIDE_THE_LANE)
                    ) {
                        items(items.size) { index ->
                            when (val item = items[index]) {
                                is ConversationItem.Day -> DaySeparator(item.timestamp)
                                is ConversationItem.Entry -> {
                                    val speaker = GroupFaces.speakerKey(item.message)?.let { key ->
                                        groupPicture?.faces?.firstOrNull { it.key == key }
                                            ?: GroupFace(key, item.message.senderName)
                                    }
                                    val speakerRoute = speaker?.let { memberRoute(it) }
                                    MessageBubble(
                                        message = item.message,
                                        speaker = speaker,
                                        onSpeakerPressed = if (speakerRoute != null) {
                                            { face -> memberRoute(face)?.let { chosenMember = it } }
                                        } else null,
                                        // A day heading in between begins everything anew, so only the
                                        // message directly above counts.
                                        startsRun = GroupFaces.startsRun(
                                            previous = (items.getOrNull(index - 1) as? ConversationItem.Entry)?.message,
                                            current = item.message
                                        ),
                                        compact = item.message.id in shrunkenPictures,
                                        onSpeakRequested = onSpeakRequested,
                                        onResendRequested = onResendRequested,
                                        isVoicePlaying = playingVoiceMessage == item.message.id,
                                        onVoiceMessageToggled = onVoiceMessageToggled,
                                        onFilmRequested = { film ->
                                            onFilmStarted()
                                            playingFilm = film
                                        },
                                        onPhotoRequested = { photo ->
                                            viewerPhoto = photo
                                        },
                                        isAdvanced = isAdvanced,
                                        onOpenAdvancedSheet = onOpenAdvancedSheet,
                                        widestPicture = widestPicture
                                    )
                                }
                            }
                        }
                    }

                    // A long correspondence scrolls like anything else, and the top of it is where
                    // the oldest thing is. Without a bar there is no sign of how far back it goes.
                    GentleScrollbar(
                        bar = scrollbarFor(listState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                    )
                }
            }

            // Over everything, including the reply controls: while a film is playing there is
            // nothing else on this screen to do. Both of these are windows of their own rather
            // than something drawn in this column, so where they stand in it decides nothing.
            playingFilm?.let { film ->
                FilmPlayer(
                    path = film,
                    onFinished = { playingFilm = null },
                    support = support,
                    onSupportPressed = onSupportPressed,
                    isRestless = isRestless
                )
            }

            // A photograph she asked to see enlarged over the whole screen.
            viewerPhoto?.let { photo ->
                PhotoViewer(
                    path = photo,
                    onClose = { viewerPhoto = null },
                    support = support,
                    onSupportPressed = onSupportPressed,
                    isRestless = isRestless
                )
            }

            // Everybody in it, by name. What a press on the faces opens, wherever they are
            // drawn — the column beside the reply block has no room for names and the strip
            // across the top stops at five, so both lead here.
            if (showingMembers && groupPicture != null) {
                GroupMembersDialog(
                    title = shownTitle,
                    faces = groupPicture.faces,
                    onDismiss = { showingMembers = false },
                    routeFor = memberRoute,
                    onMemberChosen = { chosenMember = it }
                )
            }

            // One of them, on their own: ring them, or write to them. Opened over the list, which
            // stays up behind it so that going back from one person lands on everybody again.
            chosenMember?.let { member ->
                val onPhotoForMember = if (onPhotoToMember != null && Forwarding.canCarryMedia(member.recipient)) {
                    {
                        chosenMember = null
                        showingMembers = false
                        onPhotoToMember(member.recipient)
                    }
                } else null

                GroupMemberDialog(
                    route = member,
                    onRing = {
                        // Everything closes. She is about to be on the telephone, and coming out
                        // of a call onto a stack of dialogs over a conversation is a phone that
                        // has gone somewhere she cannot get back from.
                        chosenMember = null
                        showingMembers = false
                        onRingMember(member)
                    },
                    onWrite = {
                        chosenMember = null
                        writingToMember = member
                    },
                    onPhoto = onPhotoForMember,
                    // Back to everybody: she may have pressed the wrong face, and that is where
                    // she pressed from.
                    onBack = { chosenMember = null },
                    // Walked away from, though, and the whole stack goes — a list of eight
                    // people left standing over the conversation is a phone she cannot use.
                    onWalkedAway = {
                        chosenMember = null
                        showingMembers = false
                    }
                )
            }

            // Writing to that one person: their name, a line, and the keyboard already up with
            // the cursor in it, because pressing "write to Monika" is not an ambiguous thing to
            // have pressed. Deliberately not a walk into a conversation of their own, which for
            // somebody she has never written to on her own before would be a screen with nothing
            // on it.
            writingToMember?.let { member ->
                ComposeMessageDialog(
                    recipient = member.recipient,
                    onSend = { text -> onWriteToMember(member, text) },
                    onDismiss = {
                        // Sent or not, this one is finished with, and what is behind it is the
                        // conversation she was reading.
                        writingToMember = null
                        showingMembers = false
                    }
                )
            }

            // Asking whether to ring her correspondent, when she tapped the face beside a
            // conversation with one person. A face on its own must never dial on a single tap:
            // a stray brush of the thumb while reading cannot ring a family member.
            if (confirmingCall && onRingPerson != null) {
                ConfirmCallDialog(
                    name = shownTitle,
                    onConfirm = {
                        confirmingCall = false
                        onRingPerson()
                    },
                    onDismiss = {
                        confirmingCall = false
                    }
                )
            }

            val theReply: @Composable (Modifier) -> Unit = { placed ->
                if (canReply) {
                    ReplyControls(
                        onReactionSelected = onReactionSelected,
                        onTextReplySubmitted = onTextReplySubmitted,
                        canChooseEmoji = emojiChoiceEnabled,
                        onCameraRequested = onCameraRequested,
                        // Out of the bar's lane, like the header and everything else here that is not
                        // the scrolling list. Without it the writing line ran fourteen points wider
                        // than the messages above it, so the send button sat in the scroll bar's
                        // strip and the row of faces was centred on a different axis than the
                        // conversation it belongs to.
                        //
                        // The same with several people as with one. It used to be framed there, to
                        // say "this goes to all of them" — but the frame and its padding shrank the
                        // faces and the writing line, so answering a group looked and sat
                        // differently from answering a person, and the title and the faces —
                        // above it or beside it — already say who it goes to.
                        modifier = placed
                    )
                }
            }

            // The people beside the conversation, and the height a photograph has to fit in.
            //
            // Both are measured here because both are questions about this one band. A picture is
            // drawn at the width of its bubble and whatever height its shape then asks for — see
            // [MessagePhoto] — so width is the only thing anybody sets and height is what comes
            // out. On the open Fold an ordinary upright photograph came out eight hundred points
            // tall in five hundred points of list: she scrolled through one picture of her
            // daughter instead of looking at it.
            //
            // The cap therefore goes on the picture and not on the column — see [PictureFit].
            // Holding the whole conversation to the width a photograph could fit in did fix the
            // photographs, and left a wide window with two hundred points of empty cream down the
            // side of it: the words, the reading-aloud buttons and the voice messages all paid
            // for a rule that only pictures broke.
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Taken out here, in points: inside the row below this scope is out of reach,
                // because a layout scope hides the one outside it.
                val widestPicture = PictureFit.widestPicture(
                    height = maxHeight.value,
                    narrowest = NarrowestPicture.value
                ).dp

                Row(modifier = Modifier.fillMaxSize()) {
                    // Who she is looking at, down the left-hand edge and full height.
                    //
                    // On the left because that is where a face belongs beside what it is saying,
                    // and because the right-hand edge of this screen is the scroll bar's. A group
                    // stands as its own picture and then the people in it, faded out at the
                    // bottom where the height runs out ([FaceColumn]); one person stands as one
                    // face, which is the same answer to the same question — who is this?
                    //
                    // Only on a wide window. On her phone the width belongs to the words, and a
                    // group says who is in it across the top instead.
                    if (besideTheMessages) {
                        Column(
                            modifier = Modifier
                                .width(SideColumn)
                                .fillMaxHeight()
                                .padding(end = SideColumnGap),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (groupPicture != null) {
                                FaceColumn(
                                    picture = groupPicture,
                                    faceSize = ColumnFace,
                                    edgeColor = MaterialTheme.colorScheme.background,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clickable(
                                            enabled = groupPicture.faces.isNotEmpty(),
                                            onClickLabel = stringResource(R.string.group_show_members)
                                        ) { showingMembers = true }
                                )
                            } else {
                                ContactAvatar(
                                    photoUri = personPhoto,
                                    name = shownTitle,
                                    textStyle = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier
                                        .size(ColumnFace)
                                        .then(
                                            if (onRingPerson != null) {
                                                Modifier
                                                    .clip(CircleShape)
                                                    .clickable(
                                                        onClickLabel = stringResource(
                                                            R.string.call_action,
                                                            shownTitle
                                                        )
                                                    ) { confirmingCall = true }
                                            } else {
                                                Modifier
                                            }
                                        )
                                )
                            }
                        }
                    }

                    theConversation(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        widestPicture
                    )
                }
            }

            // Under the conversation, across the whole of it — the five faces and the way to
            // words. See [ReplyControls] for why it is a button rather than an open writing line.
            if (canReply) {
                Spacer(modifier = Modifier.height(if (keyboardUp) 4.dp else 12.dp))
                theReply(Modifier.padding(end = OUTSIDE_THE_LANE, bottom = 12.dp))
            }
        }
    }
}

/**
 * As narrow as a photograph is ever drawn, whatever the height says.
 *
 * A window short enough to ask for less than this — a phone on its side, or one squeezed into a
 * corner of a folding screen — would otherwise pull the picture in to a postage stamp. A
 * photograph she has to scroll is a smaller fault than one she cannot make her family out in, so
 * below this it is allowed to be tall again. Never *widens* anything: it is handed on as a
 * maximum, and the bubble is narrower than it on every phone.
 */
private val NarrowestPicture = 240.dp

/**
 * The column the people stand in, beside the conversation on a wide window, and the air between
 * it and the messages.
 *
 * Wide enough for one face at [ColumnFace] and nothing else. It is a strip saying who this is,
 * not a list: their names are behind a press on it — see [GroupMembersDialog].
 */
private val SideColumn = 84.dp
private val SideColumnGap = 12.dp

/**
 * The people in a group conversation, under its name: the group's own card, then the people in it,
 * read left to right.
 *
 * The card comes first so that the first thing under the title is the group, not somebody in it.
 * The whole strip is one thing to press, and pressing it lists everybody by name — the faces
 * alone stop at five and do not say who anyone is.
 *
 * The list itself belongs to [ConversationScreen] and not to this strip. It used to be opened
 * from here, with its own state, which was harmless while it was only a list — but it now leads
 * on to one person and to writing to them, and two of those stacks in existence at once, one per
 * way in, is two of her half-written messages.
 */
@Composable
private fun GroupHeaderStrip(
    picture: GroupPicture,
    onShowMembers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val edge = MaterialTheme.colorScheme.background
    val hasFaces = picture.faces.isNotEmpty()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = hasFaces,
                onClickLabel = stringResource(R.string.group_show_members)
            ) { onShowMembers() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GroupAvatar(picture = picture, size = 52.dp, edgeColor = edge)
        if (hasFaces) {
            FaceRow(
                faces = picture.faces,
                faceSize = HeaderFace,
                max = HEADER_FACES,
                edgeColor = edge
            )
        }
    }
}

/**
 * How many people the top of a group conversation shows. Five at 52dp fill the line the six at 44dp
 * did; past that a card edge says there are more.
 */
private const val HEADER_FACES = 5

/** The size of the faces along the top of a group conversation: as tall as the group's card. */
private val HeaderFace = 52.dp

/**
 * The size of the faces standing down the side of a wide conversation.
 *
 * Bigger than the ones along the top, not smaller. A line of faces across a screen has to fit
 * five of them side by side in the width, so 52 points is what there is; a column is paid for in
 * height, which is what the side of a wide window has spare, and it has to hold no particular
 * number. So they are drawn at the size a face wants to be for eyes that are no longer good —
 * and the column shows as many as the window has left, six or so on the open Fold, fading out
 * where the height ends.
 */
private val ColumnFace = 72.dp

/**
 * The face at the top of the first bubble in a run of somebody's messages, beside their name.
 *
 * Inside the bubble, not in a column beside it. The column cost every bubble — the first and all
 * the ones after it — 48dp of width, to hold a 40dp face that was too small to recognise anybody
 * by. On the name line it takes room that line has anyway, so it can be bigger and the words get
 * the whole width.
 */
private val SpeakerFace = 56.dp

/**
 * One message, hers or theirs.
 *
 * Told apart by side and colour, never by a label. A "You:" in front of her own words is a word
 * she has to read before she can read the message, and it says something she can already see.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    /**
     * Who wrote it, when it was somebody else in a group — null for everything else. Their face
     * is drawn beside the first of their messages in a row, and the room for it beside the rest,
     * so that one person's messages line up under their face.
     */
    speaker: GroupFace? = null,
    /**
     * What pressing the speaker's face does — opens their one-person dialog to ring or write to
     * them. Null when this person has no address to reach them at, or when this is not a group.
     */
    onSpeakerPressed: ((GroupFace) -> Unit)? = null,
    /** Whether this is the first of a row of messages by the same person — see [GroupFaces.startsRun]. */
    startsRun: Boolean = false,
    /**
     * Whether this picture is one of a run and drawn small — see [PictureFlood]. Everything else
     * about the message is unchanged: the words, the voice, the reaction, the hold to resend.
     */
    compact: Boolean = false,
    onSpeakRequested: (String) -> Unit,
    onResendRequested: (Message) -> Unit,
    isVoicePlaying: Boolean,
    onVoiceMessageToggled: (Message) -> Unit,
    /** Play the film that came with this message, given by the path it was stored at. */
    onFilmRequested: (String) -> Unit,
    /** Open the photograph full screen in the photo viewer. */
    onPhotoRequested: (String) -> Unit = {},
    isAdvanced: Boolean = false,
    onOpenAdvancedSheet: ((Message) -> Unit)? = null,
    /**
     * The widest a photograph in this bubble may be drawn — see [PictureFit].
     *
     * Measured from the height of the list rather than from anything in here, because the fault
     * it prevents is a picture taller than the list it is in. Unspecified leaves the picture at
     * the width of the bubble, which is every other screen that draws one.
     */
    widestPicture: Dp = Dp.Unspecified
) {
    // A message of hers that did not leave the phone is the one thing on her screen that is still
    // allowed to look wrong. Everywhere else, hiding a fault costs her an explanation she would not
    // have wanted; here, hiding it means her message sits in the conversation looking exactly like
    // one that arrived, and she believes she has answered her daughter when she has not. It is
    // still not an error message — the message simply stays visibly unfinished, the way a
    // half-written note is obviously half-written.
    val unfinished = SendStatePolicy.isUnfinished(
        state = message.sendState,
        sentAt = message.timestamp,
        now = System.currentTimeMillis()
    )
    val outlineColor = MaterialTheme.colorScheme.outline
    val bubbleShape = RoundedCornerShape(20.dp)

    // Holding it sends it again. Only ever offered on a message of hers that did not go — see
    // SendRetryPolicy.mayResend — so the gesture exists exactly where it does something.
    val resendable = SendRetryPolicy.mayResend(
        isOutgoing = message.isOutgoing,
        state = message.sendState,
        sentAt = message.timestamp,
        now = System.currentTimeMillis()
    )

    // Where somebody else starts speaking, more room above: the gap says "somebody new" before
    // the face and the name do.
    val newSpeaker = speaker != null && startsRun

    val words = messageInWords(message.text)
    val ownReaction = Reactions.ownOf(message.reaction)
    val othersCap = if (ownReaction != null) Reactions.MAX_KEPT - 1 else Reactions.MAX_KEPT
    val otherReactions = Reactions.othersOf(message.reaction).take(othersCap)

    val textToSpeak = if (message.showsWords) {
        val quotedAuthor = when {
            message.quotedIsHers -> stringResource(R.string.quote_your_message)
            else -> personName(message.quotedSenderName)
        }
        val quotedSnippet = message.quotedText?.let { capForVoice(messageInWords(it)) }
        val answer = if (!quotedSnippet.isNullOrBlank()) {
            stringResource(R.string.quote_read_aloud_format, quotedAuthor, quotedSnippet, words)
        } else {
            words
        }
        val spokenWords = if (message.isGroupMessage && message.senderName.isNotBlank() && !message.isOutgoing) {
            stringResource(R.string.group_read_aloud_format, personName(message.senderName), answer)
        } else {
            answer
        }
        val context = LocalContext.current
        val spokenReactions = SpokenReactions.sentences(
            own = ownReaction,
            others = otherReactions,
            name = { emoji -> EmojiNames.RESOURCE_MAP[emoji]?.let { id -> context.getString(id) } },
            conjunction = stringResource(R.string.group_and),
            othersFormat = stringResource(R.string.reaction_read_aloud_others),
            ownFormat = stringResource(R.string.reaction_read_aloud_own)
        )
        listOf(spokenWords, spokenReactions)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    } else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (newSpeaker) 14.dp else 4.dp, bottom = 4.dp),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start
    ) {
      HoldToResend(
        enabled = !isAdvanced && resendable,
        onResend = { onResendRequested(message) },
        // Somebody else's message in a group takes nearly the whole width: its face is inside it,
        // on the name line, rather than in a column that pushed every bubble in from the edge.
        modifier = Modifier.fillMaxWidth(if (speaker != null) 0.94f else 0.86f)
      ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (unfinished) {
                        Modifier.drawBehind {
                            drawRoundRect(
                                color = outlineColor,
                                cornerRadius = CornerRadius(20.dp.toPx()),
                                style = Stroke(
                                    width = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(14f, 12f)
                                    )
                                )
                            )
                        }
                    } else {
                        Modifier
                            .clip(bubbleShape)
                            .background(
                                if (message.isOutgoing) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                    }
                )
                // A tap on the bubble reads it aloud. The double tap is taken and does nothing here:
                // [doubleTapToMagnify] on the words only watches, so without it the two taps that
                // magnify the screen would each start the voice as well.
                .then(
                    if (isAdvanced && onOpenAdvancedSheet != null) {
                        Modifier.combinedClickable(
                            onClick = { if (textToSpeak.isNotBlank()) onSpeakRequested(textToSpeak) },
                            onDoubleClick = {},
                            onLongClick = { onOpenAdvancedSheet(message) }
                        )
                    } else if (!unfinished && !resendable && textToSpeak.isNotBlank()) {
                        Modifier.combinedClickable(
                            onClickLabel = stringResource(R.string.read_aloud),
                            onClick = { onSpeakRequested(textToSpeak) },
                            onDoubleClick = {}
                        )
                    } else Modifier
                )
                .padding(16.dp)
        ) {
            // The one place a sender's name is load-bearing. In a conversation with one person
            // she knows who is talking; with several she does not, and a message with no name on
            // it is one she has to guess the author of.
            //
            // Once per row of their messages, beside their face — not on every bubble, where the
            // same name five times is five more lines to read past.
            if (message.isGroupMessage && !message.isOutgoing && (speaker == null || startsRun)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (speaker != null) {
                        GroupMemberFace(
                            face = speaker,
                            size = SpeakerFace,
                            edgeColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = if (onSpeakerPressed != null) {
                                Modifier
                                    .clip(GroupShape)
                                    .clickable(
                                        onClickLabel = stringResource(
                                            R.string.group_member_choose,
                                            personName(speaker.name)
                                        )
                                    ) { onSpeakerPressed(speaker) }
                            } else {
                                Modifier
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    // The size and colour a name has on her View screen, so a name reads as a name
                    // wherever she meets it rather than as small print above the message.
                    Text(
                        text = personName(message.senderName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Citation card showing what this message is answering. Drawn below the group sender
            // name so that the author of the current message is clearly distinct from who they quote.
            if (!message.quotedText.isNullOrBlank()) {
                QuotedMessageCard(
                    quotedSenderName = message.quotedSenderName,
                    quotedText = message.quotedText,
                    quotedIsHers = message.quotedIsHers
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // What was said, or a word for what arrived when it was not words — see
            // [messageInWords]. The same substitution the list row makes, so a photograph is
            // called the same thing in both places.
            //
            // Unless the photograph is right here, in which case there are no words at all: see
            // [Message.showsWords]. The list row still says "Photo", because a row has no room to
            // show one.
            if (message.showsWords) {
                if (isAdvanced) {
                    SelectionContainer {
                        Text(
                            text = words,
                            style = MaterialTheme.typography.bodyLarge,
                            color = when {
                                unfinished -> MaterialTheme.colorScheme.onSurfaceVariant
                                message.isOutgoing -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.doubleTapToMagnify()
                        )
                    }
                } else {
                    Text(
                        text = words,
                        style = MaterialTheme.typography.bodyLarge,
                        color = when {
                            unfinished -> MaterialTheme.colorScheme.onSurfaceVariant
                            message.isOutgoing -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.doubleTapToMagnify()
                    )
                }
            }


            // What somebody did about this message, on the message itself.
            //
            // Larger than the text on purpose: it is a small mark carrying an emotion, and at
            // body size it reads as punctuation. Underneath rather than overlapping the corner
            // the way most messengers draw it — an emoji half outside the bubble is a thing she
            // has to work out the ownership of.
            //
            // All of them, side by side, because one person reacts more than once: a heart when
            // her daughter read the message and a laughing face added afterwards are two things
            // her daughter did, and showing only the newer one said she had changed her mind.
            // Spaced widely and never stacked or counted — see [Reactions].
            //
            // Hers comes first, and never falls off the end. The mark she looks for on a message
            // is the evidence that she answered it, so it sits where the eye starts rather than
            // after however many other people happened to react; the cap takes one off the others
            // instead. Drawn on its own ground, ringed, and a size up: at arm's length a tint
            // alone is not a difference, and the whole point of the mark is that she can find it.
            if (otherReactions.isNotEmpty() || ownReaction != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (ownReaction != null) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(percent = 50)
                                )
                                .border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(percent = 50)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = ownReaction,
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    otherReactions.forEach { reaction ->
                        Text(
                            text = reaction,
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }
            }

            // One tap opens a photograph full screen, and a double tap magnifies it like two
            // fingers spreading on it. A film's still plays the film on one tap, exactly as the
            // Play button underneath does — see [MessagePhoto] for why it takes no double tap.
            val hasFilm = !message.videoPath.isNullOrBlank()
            val isRealPhoto = !message.photoPath.isNullOrBlank() && !hasFilm
            val zoomAtTap = rememberZoomAtTap()
            val playFilm: () -> Unit = { message.videoPath?.let(onFilmRequested) }

            val pictureAndFilm: @Composable () -> Unit = {
                // Under the words rather than above them, so the shape of the conversation stays
                // the same whether or not a picture ever arrives: the text is where it always was,
                // and the photograph appears below it when it has been fetched.
                MessagePhoto(
                    path = message.photoPath,
                    videoPath = message.videoPath,
                    onTap = if (isRealPhoto) {
                        { message.photoPath?.let(onPhotoRequested) }
                    } else null,
                    onDoubleTap = if (hasFilm) null else zoomAtTap::zoomAt,
                    onPlay = if (hasFilm) playFilm else null,
                    // Centred, because a tall photograph is now narrower than the bubble it sits in
                    // and a picture pushed against one edge of a wide bubble reads as a mistake.
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .then(zoomAtTap.modifier)
                        .then(
                            if (compact) {
                                Modifier.fillMaxWidth(PictureFlood.SMALL_WIDTH)
                            } else if (widestPicture != Dp.Unspecified) {
                                Modifier.widthIn(max = widestPicture)
                            } else {
                                Modifier
                            }
                        )
                )

                // Directly under the still it belongs to, because for a video message the two are
                // one thing: the picture says who is in it, and this plays them moving. Before the
                // film has been fetched — and for the many that never will be — there is only the
                // picture, exactly as before.
                MessageVideo(
                    path = message.videoPath,
                    onPlay = playFilm
                )
            }

            // A film is one thing to press: its still, the button under it, its length and the
            // bubble on either side of them. On 2026-11-01 her presses landed beside the still and
            // slid across the button — see [ForgivingTap] — and none of them opened the film. The
            // still and the button keep their own clicks; this catches everything between them.
            if (hasFilm) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = playFilm)
                        .forgivingClick(onClick = playFilm)
                ) { pictureAndFilm() }
            } else {
                pictureAndFilm()
            }

            // Above read-aloud rather than below it, because when a message has both, the one
            // she wants is the voice of the person who wrote — not this phone reading out
            // "[Sprachnachricht]".
            MessageVoice(
                path = message.audioPath,
                isPlaying = isVoicePlaying,
                onToggle = { onVoiceMessageToggled(message) }
            )

            // The read-aloud button is only under what somebody wrote to her: a button under every
            // bubble would halve how much of the conversation fits on the screen. Her own words
            // are read back by tapping the bubble itself, which every bubble with words allows.
            //
            // And only where there are words. Under a photograph that carries none, this button
            // offers to read her a picture and, pressed, says "Photo" out loud.
            val showReadAloudButton = !message.isOutgoing && message.showsWords && textToSpeak.isNotBlank()

            if (showReadAloudButton) {
                Spacer(modifier = Modifier.height(12.dp))
                ReadAloudKey(
                    text = textToSpeak,
                    onSpeakRequested = onSpeakRequested,
                    modifier = Modifier.fillMaxWidth(),
                    minHeight = 48.dp,
                    contentPadding = ButtonLabelPadding
                )
            }
        }
      }
    }
}

/**
 * The day the messages below were written.
 *
 * The only time marker in a conversation, in the wording already used elsewhere on her screen.
 * "Was this today or last week" is the question she asks; the exact minute is not.
 */
@Composable
private fun DaySeparator(timestamp: Long) {
    val label = dayInWords(timestamp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Caps quoted text for read-aloud: a listener cannot skim a quoted paragraph before hearing the answer.
 * Cuts back to the last word boundary within the limit so the voice does not read half a word.
 */
private const val MAX_VOICE_QUOTED_LENGTH = 80

private fun capForVoice(text: String): String {
    val trimmed = text.trim()
    if (trimmed.length <= MAX_VOICE_QUOTED_LENGTH) return trimmed

    if (trimmed[MAX_VOICE_QUOTED_LENGTH].isWhitespace()) {
        return trimmed.take(MAX_VOICE_QUOTED_LENGTH).trimEnd()
    }

    val candidate = trimmed.take(MAX_VOICE_QUOTED_LENGTH)
    val lastWordBoundary = candidate.indexOfLast { it.isWhitespace() }
    return if (lastWordBoundary > 0) {
        candidate.substring(0, lastWordBoundary).trimEnd()
    } else {
        candidate.trimEnd()
    }
}

/** Less side padding than a stock button, so a long label sits comfortably in a bubble. */
private val ButtonLabelPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

