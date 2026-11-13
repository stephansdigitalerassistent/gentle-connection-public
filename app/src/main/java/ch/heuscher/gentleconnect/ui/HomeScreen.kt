package ch.heuscher.gentleconnect.ui

import android.text.format.DateFormat as AndroidDateFormat
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Message
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import ch.heuscher.gentleconnect.ui.components.LocalScrollPositionProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.Forwarding
import ch.heuscher.gentleconnect.data.AppGraph
import ch.heuscher.gentleconnect.data.StepCounter
import ch.heuscher.gentleconnect.model.Agenda
import ch.heuscher.gentleconnect.model.Appointment
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.Home
import ch.heuscher.gentleconnect.model.LookFeed
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.Nudge
import ch.heuscher.gentleconnect.model.Partner
import ch.heuscher.gentleconnect.model.PaymentButtonState
import ch.heuscher.gentleconnect.model.PhotoButtonPlacement
import ch.heuscher.gentleconnect.model.Recipient
import ch.heuscher.gentleconnect.model.StepDays
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.model.toRecipient
import ch.heuscher.gentleconnect.ui.components.AgendaSection
import ch.heuscher.gentleconnect.ui.components.StepsSection
import ch.heuscher.gentleconnect.ui.components.PaymentButton
import ch.heuscher.gentleconnect.ui.components.PaymentReadyNotice
import ch.heuscher.gentleconnect.ui.components.PaymentSleepDialog
import ch.heuscher.gentleconnect.ui.components.PhotoHomeButton
import ch.heuscher.gentleconnect.ui.components.BottomTab
import ch.heuscher.gentleconnect.ui.components.ClockHeader
import ch.heuscher.gentleconnect.ui.components.ConfirmCallDialog
import ch.heuscher.gentleconnect.ui.components.FavoriteFacesRow
import ch.heuscher.gentleconnect.ui.components.GentleBottomBar
import ch.heuscher.gentleconnect.ui.components.GentleScrollbar
import ch.heuscher.gentleconnect.ui.components.scrollbarFor
import ch.heuscher.gentleconnect.ui.components.SupportButton
import ch.heuscher.gentleconnect.ui.components.SupportButtonSize
import ch.heuscher.gentleconnect.ui.components.WideScreenGripMargin
import ch.heuscher.gentleconnect.ui.components.NudgeCard
import ch.heuscher.gentleconnect.ui.components.PartnerActionDialog
import ch.heuscher.gentleconnect.ui.components.PartnerFacesRow
import ch.heuscher.gentleconnect.ui.components.LookSection
import ch.heuscher.gentleconnect.ui.components.UnreadMessagesSection
import ch.heuscher.gentleconnect.ui.theme.isWideWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Main "Zuhause" quiet launcher home screen.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    /** The payment card. See [PaymentViewModel]. */
    payment: PaymentViewModel,
    /** Her appointments. See [AppointmentsViewModel]. */
    calendar: AppointmentsViewModel,
    onNavigateToMail: () -> Unit,
    /**
     * The picture she pressed on this screen, opened on the View screen.
     *
     * Distinct from [onNavigateToMail], which is the door to everything that has arrived. A
     * press on one particular photograph of her sister's is about that photograph, and until now
     * it left her at the top of a list going back months with the picture somewhere in it.
     */
    onNavigateToStatus: (Message) -> Unit = { onNavigateToMail() },
    onNavigateToPhoneBook: () -> Unit,
    onNavigateToMessages: () -> Unit,
    /**
     * Her list of calls, which is where a missed call from somebody not in her address book leads.
     * The same place the missed-call notice in the status bar goes, on purpose: one event, one
     * destination, whichever of the two she happens to press.
     */
    onNavigateToRecentCalls: () -> Unit,
    /**
     * The missed-call card, pressed. Never a call: it used to ring the person back, and a card
     * that turns up under her thumb unasked is the easiest thing on the phone to press by
     * accident. It shows her who rang instead — see MainActivity.
     */
    onMissedCallOpened: (Nudge.MissedCallNudge) -> Unit = { onNavigateToRecentCalls() },
    onNavigateToAppointments: () -> Unit = {},
    /**
     * One appointment on the home block, pressed. Takes her to the same list
     * [onNavigateToAppointments] does, but opened at that appointment rather than at the top.
     */
    onOpenAppointment: (Appointment) -> Unit = {},
    onOpenConversation: (Message) -> Unit,
    /**
     * Whether somebody has written and she has not seen it — drawn as a mark on the Message
     * button, which is on every screen. See [GentleBottomBar].
     */
    messagesMarked: Boolean = false,
    /**
     * Five fast taps on the clock. The caregiver's screen is a screen of its own now, so this
     * says where to go rather than putting a card over her home screen — see [CaregiverScreen].
     */
    onCaregiverRequested: () -> Unit,
    /**
     * Bumped when the home button is pressed, to return to the top and dismiss open dialogs.
     */
    homeRequests: Int = 0,
    support: SupportButtonState = SupportButtonState.None,
    onSupportPressed: () -> Unit = {},
    isRestless: Boolean = false,
    onPaymentPressed: () -> Unit = {},
    /** Opens the camera to take and send a photo to this recipient. */
    onPhotoRequested: ((Recipient) -> Unit)? = null,
    /** Opens the camera to take a photo first, then pick who gets it. */
    onTakePhotoRequested: (() -> Unit)? = null,
    showRestartHint: Boolean = false,
    onDismissRestartHint: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentTimeMillis by viewModel.currentTimeMillis.collectAsState()
    val stepCounter = remember { AppGraph.stepCounter(context) }
    val stepState by stepCounter.state.collectAsState()
    val stepsAvailable by stepCounter.available.collectAsState()
    val settings by viewModel.remoteSettings.collectAsState()
    val paymentState by payment.paymentButtonState.collectAsState()
    val paymentSleepHint by payment.paymentSleepHintVisible.collectAsState()
    val photoRecipients by viewModel.photoRecipients.collectAsState()
    val paymentCardShown = settings.enableWallet && paymentState != PaymentButtonState.Unavailable
    val effectivePlacement = PhotoButtonPlacement.effective(settings.photoButtonPlacement, paymentCardShown)
    val showPhotoButton = settings.enablePhotoSending &&
        effectivePlacement != PhotoButtonPlacement.NONE &&
        photoRecipients.isNotEmpty()
    // The first five, not all of them — the rest are under Call. See [Home].
    val favoriteContacts by viewModel.homeFavourites.collectAsState()
    // All of them: a wide screen draws every favourite, not only the first five.
    val allFavourites by viewModel.favoriteContacts.collectAsState()
    val initiatingPartners by viewModel.initiatingPartners.collectAsState()
    val nudge by viewModel.singleNudge.collectAsState()
    // Null when this phone has no calendar on it at all, which is not the same as a free day —
    // see AppointmentsViewModel.appointments.
    val appointments by calendar.appointments.collectAsState()
    val unreadMessages by viewModel.unreadMessages.collectAsState()
    // The faces for those rows. Resolved in the view model, off the UI thread, and shared with
    // the Message tab so that one message cannot show a photograph in one place and initials in
    // the other — see [ch.heuscher.gentleconnect.model.MessagePortrait].
    val messagePhotos by viewModel.messagePhotos.collectAsState()
    // The same for the rows that are several people: their picture and the faces in it.
    val groupPictures by viewModel.groupPictures.collectAsState()
    // Everything the Message tab would show, only ever used to decide whether "More" leads
    // anywhere. Home lists people; the tab also holds the companies home filters out, so the
    // question is not "are there more than the listed people" but "is there anything under Message
    // that is not on this screen".
    val everyMessage by viewModel.visibleMessages.collectAsState()
    val homeMails by viewModel.homeMails.collectAsState()
    val totalMailCount by viewModel.totalMailCount.collectAsState()
    val homeStatuses by viewModel.homeStatuses.collectAsState()
    val statusPosts by viewModel.statusPosts.collectAsState()
    val homeStatusCount = statusPosts.size
    // The newest of everything she can look at, letters and pictures together, cut back to
    // Home.LIST_LENGTH after they have been merged rather than that many of each.
    val homeLook by viewModel.homeLookFeed.collectAsState()

    var activePartnerForAction by remember { mutableStateOf<Partner?>(null) }
    var contactToCall by remember { mutableStateOf<Contact?>(null) }

    val coroutineScope = rememberCoroutineScope()
    // Format the date in the language of the strings Android actually resolved, not a hardcoded
    // one. With Locale.GERMANY pinned here, a phone that fell back to the English strings showed
    // an English screen with "Dienstag, 28. Juli" under the clock — and the voice, which follows
    // the same resource, then read that German date aloud in English.
    // The pattern comes from the locale too, not just the language. "EEEE, d. MMMM" is a German
    // shape, and with the locale alone corrected it produced "Wednesday, 29. July" — an English
    // month behind a German ordinal full stop. Asking for the best pattern gives "Mittwoch, 29.
    // Juli" and "Wednesday, 29 July" from the same skeleton.
    val clockLocale = Locale.forLanguageTag(stringResource(R.string.tts_language_tag))
    val timeFormat = remember(clockLocale) { SimpleDateFormat("HH:mm", clockLocale) }
    val dateFormat = remember(clockLocale) {
        SimpleDateFormat(
            AndroidDateFormat.getBestDateTimePattern(clockLocale, "EEEEdMMMM"),
            clockLocale
        )
    }

    val timeString = timeFormat.format(Date(currentTimeMillis))
    val dateString = dateFormat.format(Date(currentTimeMillis))

    val scrollState = rememberScrollState()
    // The right-hand column of the wide layout scrolls on its own. Remembered whatever the
    // window is doing, so that folding the phone mid-scroll and opening it again does not
    // resurrect a scroll position from a column that was not on the screen at the time.
    val peopleScrollState = rememberScrollState()

    // Only a press she has not been answered for yet. The effect also runs every time home comes
    // back into the composition — back from the post, the messages, a conversation — and the
    // count is still the one from the last press, so keyed on the count alone every return by the
    // back button slid home to the top, away from the thing she had just pressed. Back returns
    // her to where she was; only the Home button starts over. Saveable so that it survives the
    // walk to another screen, as the scroll position it protects does.
    var answeredHomeRequests by rememberSaveable { mutableIntStateOf(homeRequests) }
    LaunchedEffect(homeRequests) {
        if (homeRequests != answeredHomeRequests) {
            answeredHomeRequests = homeRequests
            contactToCall = null
            activePartnerForAction = null
            scrollState.animateScrollTo(0)
            peopleScrollState.animateScrollTo(0)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (showPhotoButton && effectivePlacement == PhotoButtonPlacement.BOTTOM) {
                    PhotoHomeButton(
                        onClick = { onTakePhotoRequested?.invoke() },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
                GentleBottomBar(
                    selected = BottomTab.HOME,
                    onHomeClicked = {
                        contactToCall = null
                        activePartnerForAction = null
                        coroutineScope.launch {
                            scrollState.animateScrollTo(0)
                        }
                        // Both columns, since both of them are the home screen when it is wide.
                        coroutineScope.launch {
                            peopleScrollState.animateScrollTo(0)
                        }
                    },
                    onCallClicked = onNavigateToPhoneBook,
                    // Opens the full message list rather than scrolling. Scrolling is not an
                    // action: pressing "Message" moved the screen a little and left her with
                    // nothing to do.
                    onMessageClicked = onNavigateToMessages,
                    onLookClicked = onNavigateToMail,
                    messagesMarked = messagesMarked
                )
            }
        }
    ) { innerPadding ->
      Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Clock & Date (five fast taps trigger the pairing dialog), with the support button beside
            // it. The clock keeps its own modifier and gesture untouched.
            //
            // Outside the scrolling column, so it stays. What time it is, is the question this
            // screen is asked most, and it was the first thing to leave the screen the moment she
            // reached the faces — the phone knowing the time and hiding it behind a scroll she
            // does not know she has made.
            val gripMargin = if (isWideWindow()) WideScreenGripMargin else 0.dp
            Row(
                modifier = Modifier
                    // Above what follows: the support button's "?" may hang a little below the date.
                    .zIndex(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (gripMargin > 0.dp) {
                    Spacer(modifier = Modifier.width(gripMargin))
                }

                // The same width as the support button on the other end, and nothing in it. Without it the
                // clock centres itself in what is left over beside the button and sits half a
                // button left of the middle of the screen — barely visible on a narrow phone,
                // plainly wrong on the wide inner screen of a folding one, where half a button is
                // a centimetre. Balancing the row rather than laying the button over the clock
                // keeps the two as siblings, which is what stops a tap aimed at the button from
                // counting towards the hidden five-tap caregiver gesture. Always there: without a
                // support group the button leaves a space of the same size.
                // The payment card stands in that space when a caregiver has turned it on and
                // the phone can actually pay. [PaymentButtonState.Unavailable] — no PIN on the
                // phone at all — leaves the plain spacer: a card she could press to no effect is
                // worse than no card. Like the support button, it measures one button wide and
                // draws past the bottom of the row, so the clock does not move when it appears.
                if (paymentCardShown) {
                    PaymentButton(
                        state = paymentState,
                        onClick = onPaymentPressed,
                        modifier = Modifier.align(Alignment.Top)
                    )
                } else if (showPhotoButton && effectivePlacement == PhotoButtonPlacement.CLOCK) {
                    PhotoHomeButton(
                        onClick = { onTakePhotoRequested?.invoke() },
                        clockTile = true,
                        modifier = Modifier.align(Alignment.Top)
                    )
                } else {
                    Spacer(modifier = Modifier.width(SupportButtonSize))
                }

                ClockHeader(
                    timeString = timeString,
                    dateString = dateString,
                    onCaregiverGesture = onCaregiverRequested,
                    modifier = Modifier.weight(1f)
                )

                // Top-aligned: the tile, face over "?", then runs down beside the clock and the date
                // instead of hanging half its height below them.
                SupportButton(
                    state = support,
                    onClick = onSupportPressed,
                    isRestless = isRestless,
                    modifier = Modifier.align(Alignment.Top)
                )

                if (gripMargin > 0.dp) {
                    Spacer(modifier = Modifier.width(gripMargin))
                }
            }

            // Under the clock while the phone will really take a tap, and gone the moment it
            // will not — there is no countdown on it, because the card behind it is not counting
            // anything, and no button to press: it ends when the phone does, which is to say
            // when she puts it to sleep. Six words and nothing beside them; pressing the lit card
            // is what opens the rest, in PaymentSleepDialog.
            // Switched off per phone on the website, for whoever the lit card alone says enough to.
            if (settings.enableWallet && settings.showPaymentReadyNotice &&
                paymentState == PaymentButtonState.Ready
            ) {
                PaymentReadyNotice()
            }

            if (showRestartHint) {
                RestartHintCard(onDismiss = onDismissRestartHint)
            }

            if (showPhotoButton && effectivePlacement == PhotoButtonPlacement.CARD) {
                PhotoHomeButton(
                    onClick = { onTakePhotoRequested?.invoke() },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            // Everything below the clock, and the bar that says how much of it there is. The
            // bar lives in each column with the part that moves rather than down the whole side
            // of the screen: a track that starts up at the clock is measuring a stretch that
            // does not scroll.
            //
            // The seven things this screen is made of, held as pieces so that the same pieces can
            // be laid out down one column or across two without either arrangement being a copy
            // of the other. Nothing here decides where they go.
            val theDay: @Composable () -> Unit = {
                // 1b. What is coming. Directly under the clock, every morning — the day she is in is
                // what the top of this screen is for, and it belongs there whether or not anybody
                // has written. It used to step down a place whenever a card arrived above it, so
                // the shape of the screen changed on exactly the days something else had already
                // changed it; the part she finds by position now stays where she last found it.
                // Absent when there is no calendar here to read, and absent when a caregiver has
                // switched it off; a free day is neither of those and says so on the screen.
                if (settings.enableCalendar) {
                    // Without the birthdays when a caregiver has switched them off — see
                    // RemoteSettings.showBirthdays. Before AgendaSection counts anything, so that
                    // "more on this day" and the next-appointment line leave them out as well.
                    // Remembered, because this screen recomposes with the clock every second.
                    val shownCalendar = remember(appointments, settings.showBirthdays) {
                        appointments?.let { Agenda.shown(it, settings.showBirthdays) }
                    }
                    shownCalendar?.let { herCalendar ->
                        AgendaSection(
                            appointments = herCalendar,
                            // The clock the screen is already keeping. An appointment that ends at
                            // four leaves the screen at four, without anybody asking the calendar
                            // again.
                            now = currentTimeMillis,
                            hasMore = Agenda.hasMore(herCalendar, currentTimeMillis, ::startOfDay),
                            onOpenList = onNavigateToAppointments,
                            onOpenAppointment = onOpenAppointment
                        )
                    }
                }
            }

            val theNudge: @Composable () -> Unit = {
                // 2. Single Nudge Card (if available) — somebody having just written or rung, and
                // still the one urgent thing here: above her faces, above her messages, and drawn
                // in the amber that is used nowhere else. The day is the only thing it does not
                // push down, because what she came to the phone to find out is what today holds.
                nudge?.let { activeNudge ->
                    NudgeCard(
                        nudge = activeNudge,
                        onViewClicked = { n ->
                            when (n) {
                                is Nudge.MessageNudge -> {
                                    onOpenConversation(n.message)
                                }
                                is Nudge.MissedCallNudge -> {
                                    // A call that reached her through a messenger is a message,
                                    // and reading it is what clears it. Anything the telephone
                                    // logged is shown on the calls tab, and leaving that tab is
                                    // what clears it — see HomeViewModel.missedCallsSeen.
                                    val message = n.message
                                    if (message != null) {
                                        onOpenConversation(message)
                                    } else {
                                        onMissedCallOpened(n)
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Asked once, and used both to choose the arrangement and to decide how many of her
            // people fit in it.
            val wide = isWideWindow()

            val thePeople: @Composable () -> Unit = {
                // 3. The people she calls, one under the other — and asked about before anything
                // rings. The same question the address book asks, because a mis-tap costs her the
                // same either way.
                FavoriteFacesRow(
                    // Two of them down one column, because on a phone the third face is below
                    // the fold and a face she cannot see is a face she has to know to scroll for.
                    // A column of its own has room for the lot, so it gets the lot, and the
                    // people who have written to her follow underneath rather than a button
                    // promising more of the same.
                    contacts = if (wide) allFavourites else favoriteContacts,
                    photoSizeMultiplier = settings.photoSizeMultiplier,
                    onContactClicked = { contact ->
                        contactToCall = contact
                    },
                    // "More" means two different things in the two layouts, and both are true.
                    // Down one column it means "there are favourites you cannot see"; beside the
                    // day, where every favourite is already on the screen, it means the rest of
                    // her address book — the people who are not favourites at all, which is most
                    // of them. It is the way out of this column either way, so it stays.
                    hasMore = if (wide) true else Home.hasMore(allFavourites.size),
                    onMoreClicked = onNavigateToPhoneBook
                )
            }

            val theMessages: @Composable () -> Unit = {
                // 4. Messages (tap to open that person's conversation).
                //
                // Including whatever the nudge is showing. This used to take the nudged message
                // out of the list, on the reasoning that the same sentence twice reads as two
                // people having written. What it produced was worse: the newest message — the
                // one she was fetched to the phone for — was the only one NOT under "Messages",
                // so the list under that heading was the older, already-read ones, and it was
                // grey. The one thing on the screen the amber was for had been removed from the
                // only place the amber is used. The nudge is a card about an event; the row is
                // that message's place in her list, and the two saying the same thing is how she
                // learns that they are the same message.
                val listedMessages = unreadMessages
                if (listedMessages.isNotEmpty() || nudge == null) {
                    UnreadMessagesSection(
                        messages = listedMessages,
                        onMessageClicked = onOpenConversation,
                        photos = messagePhotos,
                        groups = groupPictures,
                        hasMore = everyMessage.size > listedMessages.size,
                        onMoreClicked = onNavigateToMessages
                    )
                }
            }

            val thePartners: @Composable () -> Unit = {
                // 4b. Menschen (Initiate partners section) — unique faces not in favorites
                if (initiatingPartners.isNotEmpty()) {
                    PartnerFacesRow(
                        partners = initiatingPartners,
                        photoSizeMultiplier = settings.photoSizeMultiplier,
                        onPartnerClicked = { partner ->
                            activePartnerForAction = partner
                        }
                    )
                }
            }

            val theLook: @Composable () -> Unit = {
                // 5. Things to look at (read-only) — always visible
                LookSection(
                    items = homeLook,
                    // A letter opens where letters live. Home lists them; it does not become a
                    // second place to read them.
                    onLetterClicked = {
                        viewModel.triggerMailSync(context)
                        onNavigateToMail()
                    },
                    onOpenLookClicked = {
                        viewModel.triggerMailSync(context)
                        onNavigateToMail()
                    },
                    hasMore = Home.hasMore(totalMailCount + homeStatusCount),
                    onPictureClicked = { picture ->
                        viewModel.triggerMailSync(context)
                        onNavigateToStatus(picture)
                    }
                )
            }

            val theSteps: @Composable () -> Unit = {
                // 6. Steps she has taken today and yesterday — only when available and turned on.
                if (settings.showSteps && stepsAvailable) {
                    val (todayStart, previousStart) = StepCounter.dayBounds(currentTimeMillis)
                    val shown = StepDays.shown(stepState, todayStart, previousStart)
                    StepsSection(shown)
                }
            }

            // Two columns when the window is wide enough for them — the open inner screen of a
            // folding phone, and nothing else she is likely to hold. See
            // GentleWindowScale.isWide, and the note there about why this is worth changing the
            // shape of the screen for: opening the phone gains width and *loses* height, so one
            // column open shows less than one column closed.
            //
            // The split is things on the left and people on the right, and the reading order
            // survives it: down the left column, then down the right. Faces move from third in
            // one column to first in the other — the top of a column is where they belong on a
            // screen that finally has room to make them large.
            if (wide) {
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    HomeColumn(scrollState) {
                        theDay()
                        theNudge()
                        theMessages()
                        theLook()
                        theSteps()
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    HomeColumn(peopleScrollState) {
                        thePeople()
                        thePartners()
                        if (showPhotoButton && effectivePlacement == PhotoButtonPlacement.FACES) {
                            PhotoHomeButton(
                                onClick = { onTakePhotoRequested?.invoke() },
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    HomeColumn(scrollState) {
                        theDay()
                        theNudge()
                        thePeople()
                        if (showPhotoButton && effectivePlacement == PhotoButtonPlacement.FACES) {
                            PhotoHomeButton(
                                onClick = { onTakePhotoRequested?.invoke() },
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        theMessages()
                        thePartners()
                        theLook()
                        theSteps()
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
      }
    }

    // She pressed the card while it was already green: how a payment ends, in words, where there
    // is room for them. See PaymentSleepDialog and PaymentViewModel.showPaymentSleepHint.
    if (paymentSleepHint) {
        PaymentSleepDialog(onDismiss = payment::dismissPaymentSleepHint)
    }

    contactToCall?.let { contact ->
        ConfirmCallDialog(
            name = personName(contact.name),
            onConfirm = {
                contactToCall = null
                // The missed-call card goes with the call itself and not from here — whoever she
                // rings back, and from wherever she pressed. See [HomeViewModel.placeCallWithDetails].
                // Only on "yes", still: saying "not now" is not dealing with a missed call, and a
                // card that vanished on the way to a question she then declined would leave her
                // with no way back to it.
                viewModel.placeCall(context, contact)
            },
            onDismiss = {
                contactToCall = null
            }
        )
    }

    // Partner Action Sheet / Dialog (Anrufen / Herz senden / Foto zeigen)
    activePartnerForAction?.let { partner ->
        val partnerRecipient = partner.toRecipient()
        val onSendPhotoClicked: (() -> Unit)? =
            if (onPhotoRequested != null && settings.enablePhotoSending &&
                Forwarding.canCarryMedia(partnerRecipient)
            ) {
                {
                    activePartnerForAction = null
                    onPhotoRequested(partnerRecipient)
                }
            } else null

        PartnerActionDialog(
            partner = partner,
            onCallClicked = {
                viewModel.placeCallWithDetails(
                    context = context,
                    name = partner.displayName,
                    phoneNumber = partner.phoneNumber,
                    // Only a Threema partner has a Threema ID to call. A WhatsApp partner
                    // falls back to their phone number, since the roaming fallback is
                    // Threema-only by design.
                    threemaId = partner.id.takeIf { partner.transport == BridgeTransport.THREEMA }
                )
                activePartnerForAction = null
            },
            onSendHeartClicked = {
                coroutineScope.launch {
                    val sent = viewModel.sendHeartToPartner(partner)
                    val toastText = if (sent) {
                        context.getString(R.string.reaction_sent) + " ❤️"
                    } else {
                        context.getString(R.string.reaction_not_sent)
                    }
                    Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
                    activePartnerForAction = null
                }
            },
            onSendPhotoClicked = onSendPhotoClicked,
            onDismissRequest = {
                activePartnerForAction = null
            }
        )
    }
}

/**
 * One scrolling column of the home screen, with its own bar down the outside edge.
 *
 * Its own scroll state rather than a shared one: two columns on one screen scroll to different
 * depths, and a bar that measures the other column is worse than no bar.
 */
@Composable
private fun RowScope.HomeColumn(
    scrollState: ScrollState,
    content: @Composable ColumnScope.() -> Unit
) {
    CompositionLocalProvider(
        LocalScrollPositionProvider provides { scrollState.value }
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content
            )

            // Out in the margin rather than taking a strip out of the content. It is also the only
            // thing that says there is more below the last face — the fold cuts a heading in half,
            // and half a word at the bottom edge reads as the screen having ended there.
            GentleScrollbar(
                bar = scrollbarFor(scrollState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 12.dp, horizontal = 6.dp)
            )
        }
    }
}

/**
 * Quiet, dismissable caregiver-facing card shown when gentle connection became the default
 * home app during the current boot [docs/phase1b.md §3b].
 */
@Composable
private fun RestartHintCard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.restart_hint_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = stringResource(R.string.restart_hint_dismiss))
            }
        }
    }
}

