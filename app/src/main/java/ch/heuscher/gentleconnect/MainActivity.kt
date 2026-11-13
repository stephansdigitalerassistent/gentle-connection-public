package ch.heuscher.gentleconnect

import android.Manifest
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import ch.heuscher.gentleconnect.model.PaymentAuthMethod
import ch.heuscher.gentleconnect.model.TapFilter
import ch.heuscher.gentleconnect.model.TouchOutcome
import ch.heuscher.gentleconnect.model.TouchSample
import ch.heuscher.gentleconnect.ui.HingeWatch
import ch.heuscher.gentleconnect.model.RestartHint
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ch.heuscher.gentleconnect.data.AppGraph
import ch.heuscher.gentleconnect.data.ContactEditing
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber
import ch.heuscher.gentleconnect.model.Agenda
import ch.heuscher.gentleconnect.model.Appointment
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.GroupFace
import ch.heuscher.gentleconnect.model.GroupMemberRoute
import ch.heuscher.gentleconnect.model.GroupPicture
import ch.heuscher.gentleconnect.model.Home
import ch.heuscher.gentleconnect.model.HomeIntent
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.PaymentButtonState
import ch.heuscher.gentleconnect.model.PersonName
import ch.heuscher.gentleconnect.model.Recipient
import ch.heuscher.gentleconnect.model.TouchTolerance
import ch.heuscher.gentleconnect.model.WriteRequest
import ch.heuscher.gentleconnect.service.HomeAfterUpdate
import ch.heuscher.gentleconnect.service.MessengerService
import ch.heuscher.gentleconnect.sms.DefaultMessagesRole
import ch.heuscher.gentleconnect.telecom.DefaultPhoneRole
import ch.heuscher.gentleconnect.ui.AdvancedMode
import ch.heuscher.gentleconnect.ui.FollowAdvancedModeWithNavigationBar
import ch.heuscher.gentleconnect.ui.HiddenNavigationBar
import ch.heuscher.gentleconnect.ui.AppointmentsScreen
import ch.heuscher.gentleconnect.ui.ConversationScreen
import ch.heuscher.gentleconnect.ui.HomeScreen
import ch.heuscher.gentleconnect.ui.KeypadScreen
import ch.heuscher.gentleconnect.ui.LookScreen
import ch.heuscher.gentleconnect.data.Forwarding
import ch.heuscher.gentleconnect.ui.AppointmentsViewModel
import ch.heuscher.gentleconnect.ui.CallsViewModel
import ch.heuscher.gentleconnect.ui.CameraScreen
import ch.heuscher.gentleconnect.ui.components.PhotoRecipientFlow
import ch.heuscher.gentleconnect.ui.CaregiverSettingsViewModel
import ch.heuscher.gentleconnect.ui.ConversationViewModel
import ch.heuscher.gentleconnect.ui.GentleViewModelFactory
import ch.heuscher.gentleconnect.ui.HomeViewModel
import ch.heuscher.gentleconnect.ui.PaymentViewModel
import ch.heuscher.gentleconnect.ui.Speech
import ch.heuscher.gentleconnect.ui.SupportViewModel
import ch.heuscher.gentleconnect.ui.VoiceViewModel
import ch.heuscher.gentleconnect.ui.MessagesScreen
import ch.heuscher.gentleconnect.ui.PhoneBookScreen
import ch.heuscher.gentleconnect.ui.RecentCallsScreen
import ch.heuscher.gentleconnect.ui.startOfDay
import ch.heuscher.gentleconnect.ui.components.AdvancedActionsSheet
import ch.heuscher.gentleconnect.ui.CaregiverScreenHost
import ch.heuscher.gentleconnect.ui.components.AdvancedModeBand
import ch.heuscher.gentleconnect.ui.components.AdvancedTarget
import ch.heuscher.gentleconnect.ui.components.CaregiverTapZone
import ch.heuscher.gentleconnect.ui.components.KeypadTapZone
import ch.heuscher.gentleconnect.ui.components.ComposeMessageDialog
import ch.heuscher.gentleconnect.ui.components.LocalReadingAloud
import ch.heuscher.gentleconnect.ui.components.PaymentPinHintDialog
import ch.heuscher.gentleconnect.ui.components.PinchToZoom
import ch.heuscher.gentleconnect.ui.components.ReactionTapZone
import ch.heuscher.gentleconnect.model.SupportButtonState
import ch.heuscher.gentleconnect.ui.components.SupportConfirmDialog
import ch.heuscher.gentleconnect.ui.SupportStatusScreen
import ch.heuscher.gentleconnect.ui.theme.GentleTheme
import ch.heuscher.gentleconnect.model.RestlessSnapshotPolicy
import ch.heuscher.gentleconnect.util.RestlessSnapshots
import ch.heuscher.gentleconnect.util.UiTrace
import ch.heuscher.gentleconnect.util.traceTaps
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File


/**
 * Main Home Launcher Activity for gentle connection.
 * Serves as the quiet Android HOME launcher surface.
 */
class MainActivity : ComponentActivity() {

    private val viewModelFactory by lazy { GentleViewModelFactory(applicationContext) }
    private val viewModel: HomeViewModel by viewModels { viewModelFactory }

    /** The payment card on the home screen. See [PaymentViewModel]. */
    private val payment: PaymentViewModel by viewModels { viewModelFactory }

    /** The support button and restlessness. See [SupportViewModel]. */
    private val support: SupportViewModel by viewModels { viewModelFactory }

    /** Her appointments, on the home screen and in their own list. See [AppointmentsViewModel]. */
    private val calendar: AppointmentsViewModel by viewModels { viewModelFactory }

    /** Reading aloud and voice messages. See [VoiceViewModel] and [Speech]. */
    private val voice: VoiceViewModel by viewModels { viewModelFactory }

    /** The conversation she has open, and everything she can do in it. See [ConversationViewModel]. */
    private val conversation: ConversationViewModel by viewModels { viewModelFactory }

    /** Her list of calls. See [CallsViewModel]. */
    private val calls: CallsViewModel by viewModels { viewModelFactory }

    /** The caregiver's hidden screen. See [CaregiverSettingsViewModel]. */
    private val caregiver: CaregiverSettingsViewModel by viewModels { viewModelFactory }

    private var showRestartHint by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.refreshContacts(force = true)
        calendar.refreshAppointments(force = true)
        viewModel.loadSmsMessages(this)
        // Only now, because Android refuses the pair of them asked together. See below.
        requestBackgroundLocation()
        AppGraph.stepCounter(applicationContext).start()
    }

    /**
     * The second half of the location question, which Android insists is a separate conversation.
     *
     * From Android 11 a request that contains both the foreground location permissions and this
     * one is not partially granted — it is **ignored entirely**, and the app is left with neither.
     * So this is its own launcher, fired only once the first request has come back.
     *
     * The system takes it from there: rather than a dialog it opens its own location page for this
     * app, where "immer zulassen" is the option that matters. That is a screen the caregiver goes
     * through while setting the phone up, and one she would never find on her own — which is why
     * nothing here nags. Asked when it is missing, refused twice, and Android stops showing it at
     * all; from then on this call is a silent no-op and the phone goes on reporting the last
     * position it managed to take while she had the screen on.
     */
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // The result is ignored on purpose — see MessengerService.refreshLocationEntitlement. When
        // Android answers this request by opening its own settings page, what comes back here says
        // "denied" whatever the caregiver then chose on it.
        MessengerService.refreshLocationEntitlement(applicationContext)
    }

    /**
     * The system's own "make this your phone app?" screen, and what comes back from it.
     *
     * Nothing is done with the answer. Whether the role was granted is not something to remember —
     * it is something to *ask Android* at the moment it matters, because it can be taken away
     * again on a settings screen this app never sees. See [DefaultPhoneRole.isHeld].
     */
    private val dialerRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The second question, asked only once the first has been answered. Two system screens
        // stacked on top of each other is how a caregiver dismisses one without reading it.
        offerToBeTheMessagesApp(force = caregiverIsSettingUp)
    }

    /** The messages half of the same setup. See [offerToBeTheMessagesApp]. */
    private val smsRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        caregiverIsSettingUp = false
    }

    /**
     * Back from the system's device-credential screen (PIN / pattern / password).
     *
     * Entering it counts as a strong unlock, which is what Google Wallet's payment key needs and
     * what a weak 2D face unlock does not give. Whether it counted is read back from the
     * keystore rather than inferred from this result — see PaymentAuthGate.
     */
    private val paymentPinLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // The result code is not consulted, and deliberately: whether the payment key is usable
        // now is the phone's answer, not this screen's. She may have typed her PIN, backed out
        // of it, or unlocked the phone some other way while the sheet was up. Ask.
        //
        // Coming back from this screen is the start of a payment either way, so a dark screen
        // left over from the last one does not grey out the card she has just unlocked.
        payment.beginPaymentAuth()
        payment.refreshPaymentState()
    }

    /**
     * Whether a caregiver is deliberately going through the role questions, rather than the app
     * asking once by itself. Carries the "ask me anyway" through from the first question to the
     * second.
     */
    private var caregiverIsSettingUp = false

    /**
     * Bumped every time the phone is woken by something arriving, so that the composition below
     * puts the message in front of her — and, while it is being found, the home screen, since
     * waking her onto whichever screen she happened to leave open would show her nothing.
     */
    private val wokenForArrival = mutableIntStateOf(0)

    /**
     * Which message the notice was about, so that pressing it opens that message.
     *
     * The notice says "Press to read it" and used to put her on the home screen instead, where the
     * card said the same sentence again over a button she then had to find and press. Two presses
     * for one message, the second of them a search — and if she had been left in a conversation or
     * on the post screen, the wake also threw that away to show her a card she had already read
     * the content of on the lock screen.
     *
     * Null when the notice carried no message, which is every notice posted by a version of the
     * app older than this one — those still land on the home screen, which is where the card is.
     */
    private val arrivedMessageId = mutableStateOf<String?>(null)

    /**
     * A number another app has asked the telephone to dial, waiting to be shown on the keys.
     *
     * Only set once this app holds the dialler role, because only then does anything send one:
     * a `tel:` link pressed in a browser, or an address in a letter. It is never dialled on
     * arrival — it is typed into the field for her, and the call still waits for her to press the
     * green circle and answer the question. An app that rings somebody because a web page said so
     * is an app that can be made to ring anybody.
     */
    private val numberToDial = mutableStateOf<String?>(null)

    /** Bumped for each request, so two calls to the same number are two events. */
    private val dialRequests = mutableIntStateOf(0)

    /**
     * A number another app has asked her to write to, waiting to be turned back into a person.
     *
     * The counterpart of [numberToDial], and it arrives the same way: this app holds the messages
     * role, so an `smsto:` link in a browser, a shop's "text us" button or a caregiver's own
     * message app hands the request here. Null with a bumped [writeRequests] means the request
     * named nobody, which is a real request too — it means "open the messages".
     */
    private val numberToWriteTo = mutableStateOf<String?>(null)

    /** Bumped for each request, for the reason [dialRequests] is. */
    private val writeRequests = mutableIntStateOf(0)

    /**
     * Bumped when she presses the missed-call notice, so the composition below shows her the calls.
     *
     * The notice says "Tap for details" and used to have no intent behind it at all: pressing it
     * dismissed it and left her where she was. See
     * [ch.heuscher.gentleconnect.telecom.GentlePhoneCallService].
     */
    private val missedCallRequests = mutableIntStateOf(0)

    /** Bumped when the home button or launcher intent requests returning to home. */
    private val homeRequests = mutableIntStateOf(0)

    /** Wall-clock timestamp of the most recent ACTION_DOWN, paired with ACTION_UP for [TouchSample]. */
    private var touchDownAt: Long = 0L

    /** Watches the folding phone's hinge while the activity is started, for fold-cycle restlessness. */
    private val hingeWatch by lazy { HingeWatch(this) { closed -> support.recordHinge(closed) } }

    /**
     * The phone going dark, which is how a trip to the till ends when nobody presses anything,
     * and waking up again, which counts towards screen-cycle restlessness.
     *
     * Registered for as long as this activity exists rather than only while it is in front: at
     * the moment the screen goes off after a payment the launcher is often behind Wallet's own
     * screen, and a receiver that lived only between onResume and onPause would miss exactly the
     * case it is here for.
     *
     * A screen-off with no payment in progress costs nothing — the latch is dropped again by the
     * next read that does not say Ready.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> payment.finishPayment()
                Intent.ACTION_SCREEN_ON -> {
                    UiTrace.event("screen on")
                    support.recordScreenOn()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize replier strategy and contacts repository with activity context
        viewModel.start(this)

        // The floor under her phone's own back button: pressing it must never leave the launcher,
        // because what is behind a launcher is the bare Android desktop and there is no way back
        // from there that she would find.
        //
        // This catches back only where nothing above it does — on the home screen. The screens
        // below install their own handler on top of this one; see the BackHandler in setContent,
        // which is what makes the button actually go somewhere.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Home launcher remains on home screen
                UiTrace.event("back without effect")
                support.recordBackWithoutEffect()
            }
        })

        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        requestRequiredPermissions()
        showFor(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                support.restlessSnapshotRequests.collect { trigger ->
                    for ((index, delayMs) in RestlessSnapshotPolicy.FOLLOW_UP_DELAYS_MS.withIndex()) {
                        launch {
                            delay(delayMs)
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                RestlessSnapshots.capture(this@MainActivity, trigger, index)
                            }
                        }
                    }
                }
            }
        }

        setContent {
            // Widened touch slop so her tremor does not turn taps into accidental drags that
            // get swallowed by scrolling lists. Remembered around the platform base so all other
            // timings and configurations delegate unchanged. Wrapped outside .traceTaps("main")
            // so UiTrace measures against the same threshold the app uses.
            val base = LocalViewConfiguration.current
            val tolerant = remember(base) {
                object : ViewConfiguration by base {
                    override val touchSlop: Float
                        get() = TouchTolerance.slopFor(base.touchSlop)
                }
            }
            // What the voice is reading, for the "read aloud" buttons to show that they are.
            val readingAloud by voice.readingAloud.collectAsState()
            CompositionLocalProvider(
                LocalViewConfiguration provides tolerant,
                LocalReadingAloud provides readingAloud
            ) {
            // Android's buttons come back for as long as a caregiver has Advanced Mode on.
            FollowAdvancedModeWithNavigationBar(this@MainActivity)

            val settings by viewModel.remoteSettings.collectAsState()

            // Somebody has written and she has not seen it. Collected once here rather than in
            // each screen, because the mark belongs to the bar at the bottom, and that bar is the
            // one thing every screen has in common.
            val messagesMarked by viewModel.messagesMarked.collectAsState()

            var currentScreen by remember { mutableStateOf(Screen.Home) }
            // Which appointment the list should open at, when she got there by pressing one on
            // the home screen rather than by asking for the list. Held beside the screen rather
            // than passed through goTo, because it is not a screen — it is a question about one,
            // and only one of the several ways into that list asks it.
            var appointmentToOpen by remember { mutableStateOf<Appointment?>(null) }

            // The same shape, for the picture she pressed on her home screen: a question about
            // the View screen rather than a screen of its own. The View screen consumes it and
            // clears it, so coming back out of the picture leaves her in the list beside it.
            var statusToOpen by remember { mutableStateOf<Message?>(null) }
            var activeAdvancedTarget by remember { mutableStateOf<AdvancedTarget?>(null) }

            // The person the missed-call card was about, for the address book to open at. The
            // same shape as appointmentToOpen: a question about a screen, consumed by it.
            var phoneBookJumpTo by remember { mutableStateOf<String?>(null) }

            // Somebody another app asked her to write to, who has never written to her — so there
            // is no conversation to open. The dialog the group member list uses stands in for it.
            // See the write request below.
            var writingToRequested by remember { mutableStateOf<Recipient?>(null) }

            // A person she asked to take and send a photo to. When non-null, the full-screen camera
            // overlay is open above whatever screen she was on.
            var photoRecipient by remember { mutableStateOf<Recipient?>(null) }
            var photoFirst by remember { mutableStateOf(false) }
            var photoReadyPath by remember { mutableStateOf<String?>(null) }
            var photoSentSuccessfully by remember { mutableStateOf(false) }

            // The telephone's record of calls is read when she opens the screen that shows it,
            // and then kept true for as long as she is on it: the row for a call she places from
            // this screen is written when that call ends, by which time she is already back here
            // looking at a list that does not have it. See CallsViewModel.watchRecentCalls — the
            // watch lives exactly as long as the screen, because that is the only time anybody
            // is looking. Her year of appointments is kept true the same way, for an appointment
            // that syncs in while she is reading the list.
            LaunchedEffect(currentScreen) {
                // One `when`, and nothing after it: each of these suspends until the screen goes
                // away.
                when (currentScreen) {
                    Screen.Appointments -> calendar.watchPlannedAppointments()
                    Screen.RecentCalls -> coroutineScope {
                        launch { viewModel.watchMissedCalls() }
                        calls.watchRecentCalls()
                    }
                    Screen.PhoneBook, Screen.Keypad -> viewModel.watchMissedCalls()
                    else -> Unit
                }
            }

            // Leaving the calls — the address book, the keys, the list of calls — is what ends
            // the missed calls marked there. She was shown who rang; whether she rings them back
            // is hers to decide, and the card must not go on asking. Watched here rather than in
            // goTo because the home button and the notices move her too, without going through it.
            val shownCallsTab = remember { arrayOf(false) }
            LaunchedEffect(currentScreen) {
                val onCallsTab = currentScreen in CALLS_TAB
                if (shownCallsTab[0] && !onCallsTab) viewModel.missedCallsSeen()
                if (!onCallsTab) phoneBookJumpTo = null
                shownCallsTab[0] = onCallsTab
            }

            // Woken by a message: what she needs to be looking at is the message itself.
            //
            // Home first, always, and then the conversation as soon as the message can be found —
            // which is the same instant, unless the app was started cold by this very press and is
            // still loading. So the worst case is the screen this used to stop at, with the card on
            // it, rather than a blank wait; and the ordinary case is that pressing a notice that
            // says "Press to read it" reads it to her.
            //
            // Marking the conversation read is deliberate and belongs here: the card and the mark
            // on the Message button both mean "somebody wrote and she has not seen it", and she
            // has now seen it. Leaving them lit after she pressed the notice is how they come to
            // mean nothing — see ConversationViewModel.markConversationRead.
            val wokenCount = wokenForArrival.intValue
            LaunchedEffect(wokenCount) {
                if (wokenCount <= 0) return@LaunchedEffect
                currentScreen = Screen.Home
                val messageId = arrivedMessageId.value ?: return@LaunchedEffect
                arrivedMessageId.value = null
                if (conversation.openConversationForMessage(messageId)) {
                    currentScreen = Screen.Conversation
                }
            }

            // Another app has handed the telephone a number. Put her on the keys with it already
            // typed, so the number she pressed in a browser is the number under her thumb. An
            // empty request is a request too — it means "open the telephone" — and lands on the
            // same screen with nothing typed.
            val dialCount = dialRequests.intValue
            LaunchedEffect(dialCount) {
                if (dialCount > 0) currentScreen = Screen.Keypad
            }

            // And another app has asked her to write to somebody. The messages first, always, so a
            // request naming nobody still lands somewhere that makes sense — and so the wait for a
            // person to be found happens on a screen with her conversations on it rather than on a
            // blank one. See showForWriting.
            val writeCount = writeRequests.intValue
            LaunchedEffect(writeCount) {
                if (writeCount <= 0) return@LaunchedEffect
                currentScreen = Screen.Messages
                writingToRequested = null
                val number = numberToWriteTo.value ?: return@LaunchedEffect
                numberToWriteTo.value = null
                if (conversation.openConversationWithNumber(number)) {
                    currentScreen = Screen.Conversation
                } else {
                    // Nobody has written from that number: somebody in her address book she has
                    // only ever rung, or a shop. Leaving her on the list was a request that
                    // visibly did nothing, so the line to write on comes up over it instead.
                    writingToRequested = conversation.recipientForNumber(number)
                }
            }

            // She pressed the missed-call notice. The calls are what it promised her, and the
            // effect further up refreshes that list as she arrives on it.
            val missedCount = missedCallRequests.intValue
            LaunchedEffect(missedCount) {
                if (missedCount > 0) currentScreen = Screen.RecentCalls
            }

            // She pressed the phone's home button or the launcher was resumed: return to home.
            val homeCount = homeRequests.intValue
            LaunchedEffect(homeCount) {
                if (homeCount <= 0) return@LaunchedEffect
                voice.stopSpeaking()
                conversation.closeConversation()
                activeAdvancedTarget = null
                writingToRequested = null
                photoRecipient = null
                photoFirst = false
                photoReadyPath?.let { File(it).delete() }
                photoReadyPath = null
                photoSentSuccessfully = false
                appointmentToOpen = null
                statusToOpen = null
                currentScreen = Screen.Home
            }

            val scope = rememberCoroutineScope()

            // Tapping a message anywhere opens that person's conversation. The message list is
            // still the entry point — Messages never becomes a second list of people — but what
            // opens from it is now everything the two of them have written, rather than the one
            // message she happened to tap.
            val openConversation: (Message) -> Unit = { message ->
                voice.stopSpeaking()
                // Only if there is one. A message the app cannot file has no conversation to show,
                // and moving her there anyway left her on a screen with no name at the top and
                // nothing under it, which she has no way to read as "this message has no thread".
                if (conversation.openConversation(message)) {
                    currentScreen = Screen.Conversation
                }
            }

            GentleTheme(
                darkTheme = settings.isDarkMode,
                textSizeMultiplier = settings.textSizeMultiplier
            ) {
                val isAdvancedMode by AdvancedMode.isOn.collectAsState()
                val supportButtonState by support.supportButtonState.collectAsState()
                val isRestless by support.isRestless.collectAsState()
                val currentSupportRequest by support.currentSupportRequest.collectAsState()
                val supportMembers by support.supportMembers.collectAsState()
                var showSupportConfirmDialog by remember { mutableStateOf(false) }

                val goTo: (Screen) -> Unit = { screen ->
                    voice.stopSpeaking()
                    if (screen != Screen.Conversation) conversation.closeConversation()
                    activeAdvancedTarget = null
                    photoRecipient = null
                    photoFirst = false
                    photoReadyPath?.let { File(it).delete() }
                    photoReadyPath = null
                    photoSentSuccessfully = false
                    if (screen != Screen.Appointments) appointmentToOpen = null
                    if (screen != Screen.Look) statusToOpen = null
                    if (currentScreen == Screen.Look && screen != Screen.Look) {
                        viewModel.markLookFeedAsRead()
                    }
                    currentScreen = screen
                }

                // The Home key in the bar at the bottom of every other screen: the same as the phone's
                // own Home key, so home opens at the top and not wherever she left it. The green
                // arrow and the back button use goTo instead, which keeps her place.
                val startHomeOver: () -> Unit = { homeRequests.intValue += 1 }

                // Into the app's log, so a touch can later be read against the screen it was on.
                LaunchedEffect(currentScreen) {
                    UiTrace.screen(currentScreen.name)
                    support.recordScreenChange(currentScreen.name)
                }

                val onSupportPressed = {
                    when (supportButtonState) {
                        is SupportButtonState.Sent, is SupportButtonState.Claimed -> {
                            goTo(Screen.SupportStatus)
                        }
                        is SupportButtonState.Idle -> {
                            UiTrace.event("support dialog shown")
                            showSupportConfirmDialog = true
                        }
                        SupportButtonState.None -> {}
                    }
                }

                if (showSupportConfirmDialog) {
                    SupportConfirmDialog(
                        members = supportMembers,
                        onConfirm = {
                            UiTrace.event("support dialog confirmed")
                            showSupportConfirmDialog = false
                            support.triggerSupportRequest(currentScreen.name)
                            goTo(Screen.SupportStatus)
                        },
                        onDismiss = {
                            UiTrace.event("support dialog dismissed")
                            showSupportConfirmDialog = false
                        },
                        onTimeout = {
                            UiTrace.event("support dialog timed out")
                            showSupportConfirmDialog = false
                            support.logSupportOpenedNotConfirmed()
                        }
                    )
                }

                writingToRequested?.let { recipient ->
                    ComposeMessageDialog(
                        recipient = recipient,
                        onSend = { text ->
                            // Into the conversation her message is filed under, when there is one,
                            // so she sees it go — the same place a reply lands. A number nobody
                            // knows may have none yet, and then she stays on the list, where the
                            // message appears.
                            conversation.openConversationWith(recipient)
                            if (conversation.openConversationId.value != null) {
                                currentScreen = Screen.Conversation
                            }
                            scope.launch { conversation.sendNewMessage(recipient, text) }
                        },
                        onDismiss = { writingToRequested = null }
                    )
                }

                // Before the PIN that unlocks the card, when a hint is set on this phone. See
                // launchPaymentPinAuth.
                val paymentPinHint by payment.paymentPinHintShown.collectAsState()
                paymentPinHint?.let { hint ->
                    PaymentPinHintDialog(
                        hint = hint,
                        onContinue = {
                            payment.dismissPaymentPinHint()
                            startPaymentPinPrompt()
                        },
                        onDismiss = payment::dismissPaymentPinHint
                    )
                }

                // Her phone's own back button, which until now did nothing at all: the callback in
                // onCreate was written to keep the launcher from being left, and it swallowed back
                // on every screen rather than only on home. So the arrow on the bar along the
                // bottom of the telephone — the control she reaches for first, because every other
                // Android app she has ever used answered it — was dead on the message list, in the
                // phone book, on the post screen and inside a conversation. A button that does
                // nothing is not a small annoyance to her; it is the phone being broken.
                //
                // It goes exactly where the green arrow at the top of the same screen goes, so the
                // two can never disagree: out of a conversation to the messages it was opened
                // from, out of anything else to home. On home it is disabled and the floor in
                // onCreate catches the press, which is what still keeps the launcher unleavable.
                BackHandler(enabled = currentScreen != Screen.Home) {
                    // Wherever the green arrow at the top of that screen goes, so the two can
                    // never disagree: out of a conversation to the messages, out of the keys or
                    // the record of calls to the address book they hang under, out of anything
                    // else to home.
                    goTo(
                        when (currentScreen) {
                            Screen.Conversation -> Screen.Messages
                            Screen.Keypad, Screen.RecentCalls -> Screen.PhoneBook
                            Screen.SupportStatus -> Screen.Home
                            else -> Screen.Home
                        }
                    )
                }

                // Remembers where each screen was, so going back returns to the place she left.
                //
                // Navigation here is a `when`, which means the screen she leaves is removed from
                // the composition entirely and everything it remembered — including how far down
                // its list she had scrolled — is thrown away. So opening a message from halfway
                // down Messages and pressing back put her at the top again, with the message she
                // had just read somewhere below, and no way to find it except to scroll and read
                // until she recognised it. With four years of history now imported that list is
                // long, and the effect is that going back loses her place completely.
                //
                // The holder keeps each branch's saved state under its own key. It restores what
                // `rememberSaveable` holds — scroll positions — and not what plain `remember`
                // holds, which is the distinction that matters: a help dialog she had open should
                // not reappear when she comes back, and it does not.
                val screenState = rememberSaveableStateHolder()

                Column(modifier = Modifier.fillMaxSize().traceTaps("main")) {
                    AdvancedModeBand(
                        visible = isAdvancedMode,
                        onDismiss = { AdvancedMode.disable() }
                    )

                    // Around everything, so two fingers make any screen bigger — see PinchToZoom for
                    // why it is here and not inside the screens. Outside the state holder rather than
                    // inside it, so that walking to another screen does not silently leave a
                    // magnification behind on the one she left.
                    PinchToZoom(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f, fill = true)
                            // The band above has taken the space behind the status bar. Saying so
                            // stops every screen's own Scaffold from insetting for it a second
                            // time, which put a second clock's worth of empty space under the band
                            // on every screen while Advanced Mode was on.
                            .then(
                                if (isAdvancedMode) {
                                    Modifier.consumeWindowInsets(WindowInsets.statusBars)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        // Keyed by name rather than by the enum itself: the key is written into a
                        // Bundle when Android saves the activity, and a String is unarguably allowed
                        // there.
                        screenState.SaveableStateProvider(currentScreen.name) {
                            when (currentScreen) {
                                Screen.Home -> HomeScreen(
                                    viewModel = viewModel,
                                    calendar = calendar,
                                    payment = payment,
                                    onCaregiverRequested = { goTo(Screen.Caregiver) },
                                    onNavigateToMail = { goTo(Screen.Look) },
                                    onNavigateToStatus = { picture ->
                                        statusToOpen = picture
                                        goTo(Screen.Look)
                                    },
                                    onNavigateToPhoneBook = { goTo(Screen.PhoneBook) },
                                    onNavigateToMessages = { goTo(Screen.Messages) },
                                    onNavigateToRecentCalls = { goTo(Screen.RecentCalls) },
                                    // Not a call: the address book, open at whoever rang, with
                                    // everybody else who rang marked beside them. A number
                                    // nobody has can only be found in her list of calls.
                                    onMissedCallOpened = { missed ->
                                        // The address book is only read while a screen draws it,
                                        // so before she has ever opened it this is empty — and
                                        // the address book is then the better guess, where the
                                        // list of calls is marked if nobody there has the number.
                                        val everybody = viewModel.allContacts.value
                                        val known = Home.callBack(
                                            viewModel.favoriteContacts.value + everybody,
                                            missed
                                        )
                                        if (missed.number.isNotBlank() && (known != null || everybody.isEmpty())) {
                                            phoneBookJumpTo = missed.number
                                            goTo(Screen.PhoneBook)
                                        } else {
                                            goTo(Screen.RecentCalls)
                                        }
                                    },
                                    onNavigateToAppointments = {
                                        appointmentToOpen = null
                                        goTo(Screen.Appointments)
                                    },
                                    onOpenAppointment = { appointment ->
                                        appointmentToOpen = appointment
                                        goTo(Screen.Appointments)
                                    },
                                    onOpenConversation = openConversation,
                                    messagesMarked = messagesMarked,
                                    homeRequests = homeCount,
                                    support = supportButtonState,
                                    onSupportPressed = onSupportPressed,
                                    isRestless = isRestless,
                                    onPaymentPressed = ::launchPaymentPinAuth,
                                    onPhotoRequested = { recipient -> photoRecipient = recipient },
                                    onTakePhotoRequested = {
                                        photoSentSuccessfully = false
                                        photoFirst = true
                                    },
                                    showRestartHint = showRestartHint,
                                    onDismissRestartHint = ::dismissRestartHint
                                )
                                Screen.PhoneBook -> {
                                    val allContacts by viewModel.allContacts.collectAsState()
                                    val favoriteContacts by viewModel.favoriteContacts.collectAsState()
                                    val openMissedCalls by viewModel.openMissedCalls.collectAsState()
                                    PhoneBookScreen(
                                        contacts = allContacts,
                                        // All of them, not the five home draws — this is the screen she comes to
                                        // because the person she wants was not one of the five.
                                        favourites = favoriteContacts,
                                        onCallContact = { contact -> viewModel.placeCall(this@MainActivity, contact) },
                                        onKeypadRequested = { goTo(Screen.Keypad) },
                                        onBecomeTelephoneRequested = { offerToBeTheTelephone(force = true) },
                                        onRecentCallsRequested = { goTo(Screen.RecentCalls) },
                                        // Her address book's own id when the row came from the telephone,
                                        // and the number when it did not. A favourite the caregiver named
                                        // on the website carries the website's id, which Android's editor
                                        // cannot open — and the hold would then do nothing at all, with
                                        // nothing to say why. Offering the number instead lands on the
                                        // same person: INSERT_OR_EDIT attaches it to whoever already has
                                        // it rather than making a second copy of them.
                                        onEditContact = { contact ->
                                            if (!ContactEditing.editContact(this@MainActivity, contact.id)) {
                                                ContactEditing.saveNumber(
                                                    this@MainActivity,
                                                    contact.phoneNumber,
                                                    contact.name
                                                )
                                            }
                                        },
                                        onBackToHome = { goTo(Screen.Home) },
                                        onHomeTab = startHomeOver,
                                        onSpeakRequested = { text -> voice.speakText(text) },
                                        onStopSpeaking = { voice.stopSpeaking() },
                                        onMessagesRequested = { goTo(Screen.Messages) },
                                        onMailRequested = { goTo(Screen.Look) },
                                        messagesMarked = messagesMarked,
                                        searchEnabled = settings.enableSearch,
                                        support = supportButtonState,
                                        onSupportPressed = onSupportPressed,
                                        onOpenAdvancedSheet = { contact ->
                                            activeAdvancedTarget = AdvancedTarget.ContactTarget(contact)
                                        },
                                        isRestless = isRestless,
                                        missedCalls = openMissedCalls,
                                        jumpToNumber = phoneBookJumpTo,
                                        onJumped = { phoneBookJumpTo = null }
                                    )
                                }
                                Screen.Keypad -> {
                                    val allContacts by viewModel.allContacts.collectAsState()
                                    KeypadScreen(
                                        contacts = allContacts,
                                        onCallNumber = { number ->
                                            viewModel.placeCallWithNumber(this@MainActivity, number, number)
                                        },
                                        onSaveNumber = { number ->
                                            ContactEditing.saveNumber(this@MainActivity, number)
                                        },
                                        onBackToHome = { goTo(Screen.Home) },
                                        onHomeTab = startHomeOver,
                                        onBackToPhoneBook = { goTo(Screen.PhoneBook) },
                                        onSpeakRequested = { text -> voice.speakText(text) },
                                        onStopSpeaking = { voice.stopSpeaking() },
                                        onMessagesRequested = { goTo(Screen.Messages) },
                                        onMailRequested = { goTo(Screen.Look) },
                                        messagesMarked = messagesMarked,
                                        support = supportButtonState,
                                        onSupportPressed = onSupportPressed,
                                        initialNumber = numberToDial.value.orEmpty(),
                                        isRestless = isRestless
                                    )
                                }
                                Screen.RecentCalls -> {
                                    val recentCalls by calls.recentCalls.collectAsState()
                                    val canReadCalls by calls.canReadCalls.collectAsState()
                                    RecentCallsScreen(
                                        calls = recentCalls,
                                        canReadCalls = canReadCalls,
                                        onCallBack = { record ->
                                            viewModel.placeCallWithNumber(
                                                this@MainActivity,
                                                record.name ?: record.number,
                                                record.number
                                            )
                                        },
                                        // Somebody she has: correct them. A number nobody has: keep it.
                                        // One gesture, two outcomes, because from her side of the screen
                                        // they are one thing — "put this right in the telephone book".
                                        onEditRequested = { record ->
                                            val contactId = record.contactId
                                            if (contactId != null) {
                                                ContactEditing.editContact(this@MainActivity, contactId)
                                            } else {
                                                ContactEditing.saveNumber(this@MainActivity, record.number)
                                            }
                                        },
                                        onBackToHome = { goTo(Screen.Home) },
                                        onHomeTab = startHomeOver,
                                        onBackToPhoneBook = { goTo(Screen.PhoneBook) },
                                        onSpeakRequested = { text -> voice.speakText(text) },
                                        onStopSpeaking = { voice.stopSpeaking() },
                                        onMessagesRequested = { goTo(Screen.Messages) },
                                        onMailRequested = { goTo(Screen.Look) },
                                        messagesMarked = messagesMarked,
                                        support = supportButtonState,
                                        onSupportPressed = onSupportPressed,
                                        onOpenAdvancedSheet = { record ->
                                            activeAdvancedTarget = AdvancedTarget.CallTarget(record)
                                        },
                                        isRestless = isRestless
                                    )
                                }
                                Screen.Messages -> {
                                    val allMessages by viewModel.visibleMessages.collectAsState()
                                    val notYetWrittenTo by viewModel.notYetWrittenTo.collectAsState()
                                    val messagePhotos by viewModel.messagePhotos.collectAsState()
                                    val groupPictures by viewModel.groupPictures.collectAsState()
                                    MessagesScreen(
                                        messages = allMessages,
                                        photos = messagePhotos,
                                        groups = groupPictures,
                                        onMessageClicked = openConversation,
                                        onBackToHome = { goTo(Screen.Home) },
                                        onHomeTab = startHomeOver,
                                        onSpeakRequested = { text -> voice.speakText(text) },
                                        onStopSpeaking = { voice.stopSpeaking() },
                                        onPhoneBookRequested = { goTo(Screen.PhoneBook) },
                                        notYetWrittenTo = notYetWrittenTo,
                                        // The same line to write on that a request from another
                                        // app opens — see writingToRequested.
                                        onWriteToContact = { contact ->
                                            writingToRequested = Recipient(
                                                name = contact.name,
                                                photoUri = contact.photoUri,
                                                threemaId = contact.threemaId,
                                                phoneNumber = contact.phoneNumber
                                            )
                                        },
                                        onMailRequested = { goTo(Screen.Look) },
                                        messagesMarked = messagesMarked,
                                        searchEnabled = settings.enableSearch,
                                        support = supportButtonState,
                                        onSupportPressed = onSupportPressed,
                                        onOpenAdvancedSheet = { message ->
                                            val allInChat = allMessages.filter {
                                                it.conversationId == message.conversationId ||
                                                    (it.senderAddress != null && it.senderAddress == message.senderAddress)
                                            }
                                            activeAdvancedTarget = AdvancedTarget.ChatTarget(
                                                message = message,
                                                allMessagesInChat = allInChat,
                                                conversationId = message.conversationId
                                            )
                                        },
                                        isRestless = isRestless
                                    )
                                }
                                Screen.Conversation -> {
                                    val conversationTitle by conversation.openConversationTitle.collectAsState()
                                    val conversationMessages by conversation.openConversationMessages.collectAsState()
                                    // The face of whoever she is writing to, for the strip beside
                                    // the conversation. Taken from the newest thing they sent,
                                    // because that is the message the address book was already
                                    // asked about — see [HomeViewModel.messagePhotos].
                                    val conversationPhotos by viewModel.messagePhotos.collectAsState()
                                    val conversationReplyTarget by conversation.openConversationReplyTarget.collectAsState()
                                    val playingVoice by voice.playingVoiceMessage.collectAsState()
                                    val groupPictures by viewModel.groupPictures.collectAsState()
                                    // Several people, when the conversation is a group: drawn above it
                                    // and in front of the reply. Null for a conversation with one person.
                                    val conversationGroup = conversationMessages
                                        .firstNotNullOfOrNull { it.groupIdentity }
                                        ?.let { groupPictures[it] ?: GroupPicture(null, emptyList()) }
                                    val conversationFace = conversationMessages
                                        .lastOrNull { !it.isOutgoing && conversationPhotos[it.id] != null }
                                        ?.let { conversationPhotos[it.id] }
                                    // Which messenger carried this conversation, which is what
                                    // says how to read a face's key: a Threema face is filed
                                    // under the identity, a WhatsApp one under the digits of the
                                    // number. Taken from the messages themselves rather than
                                    // from the group, because they are what the faces were built
                                    // from in the first place.
                                    val conversationTransport = conversationMessages
                                        .lastOrNull { it.transport != BridgeTransport.UNKNOWN }
                                        ?.transport
                                        ?: BridgeTransport.UNKNOWN
                                    val conversationContacts by viewModel.allContacts.collectAsState()
                                    // How to ring her correspondent in a one-person conversation.
                                    // A face is something she presses to ring somebody everywhere else
                                    // in this app, so pressing the face beside the messages must ring
                                    // them too — always confirmed before anything dials.
                                    //
                                    // Only for a conversation with one person: in a group the people are
                                    // reached individually through the member routes from their faces.
                                    // Null when her correspondent has neither a telephone number nor a
                                    // Threema identity, and then the face stays still with no ripple.
                                    val onePerson: Pair<Recipient?, (() -> Unit)?> = if (conversationGroup == null) {
                                        val target = conversationReplyTarget
                                            ?: conversationMessages.lastOrNull { !it.isOutgoing }
                                            ?: conversationMessages.lastOrNull()
                                        if (target != null) {
                                            val personKey = (target.senderId ?: target.senderAddress)?.trim().orEmpty()
                                            val routeKey = if (conversationTransport == BridgeTransport.WHATSAPP) {
                                                WhatsAppNumber.numberFromJid(personKey)
                                                    ?: WhatsAppNumber.digitsOf(personKey)
                                                    ?: personKey
                                            } else {
                                                personKey
                                            }
                                            val route = if (routeKey.isNotEmpty() && conversationTransport != BridgeTransport.UNKNOWN) {
                                                GroupMemberRoute.of(
                                                    face = GroupFace(
                                                        key = routeKey,
                                                        name = conversationTitle,
                                                        photoUri = conversationFace
                                                    ),
                                                    transport = conversationTransport,
                                                    contacts = conversationContacts
                                                )
                                            } else null

                                            val rawAddress = target.senderAddress ?: target.senderId
                                            val digits = WhatsAppNumber.digitsOf(rawAddress)
                                            val (callName, callPhone, callThreema) = if (route != null && route.canRing) {
                                                Triple(route.name, route.phoneNumber, route.threemaId)
                                            } else {
                                                val contact = conversationContacts.firstOrNull { c ->
                                                    (!target.contactId.isNullOrBlank() && c.id == target.contactId) ||
                                                        (digits != null && WhatsAppNumber.sameSubscriber(c.phoneNumber, digits))
                                                }
                                                val phone = contact?.phoneNumber?.trim()?.ifEmpty { null }
                                                    ?: rawAddress?.trim()?.takeIf {
                                                        it.isNotEmpty() &&
                                                            !it.any { ch -> ch.isLetter() } &&
                                                            !PersonName.looksLikeAddress(it)
                                                    }
                                                val threema = contact?.threemaId?.trim()?.ifEmpty { null }
                                                val name = contact?.name ?: conversationTitle
                                                Triple(name, phone, threema)
                                            }

                                            val ringAction: (() -> Unit)? = if (!callPhone.isNullOrBlank() || !callThreema.isNullOrBlank()) {
                                                {
                                                    viewModel.placeCallWithDetails(
                                                        context = this@MainActivity,
                                                        name = callName,
                                                        phoneNumber = callPhone,
                                                        threemaId = callThreema,
                                                        photoUri = conversationFace
                                                    )
                                                }
                                            } else null

                                            val recipient = route?.recipient ?: Recipient(
                                                name = callName,
                                                photoUri = conversationFace,
                                                threemaId = callThreema,
                                                whatsappId = if (conversationTransport == BridgeTransport.WHATSAPP) digits else null,
                                                phoneNumber = callPhone
                                            )
                                            Pair(recipient, ringAction)
                                        } else Pair(null, null)
                                    } else Pair(null, null)
                                    // Typed apart from the destructuring: kapt still reads this file as
                                    // Kotlin 1.9, which refuses a type written on a destructuring.
                                    val (onePersonRecipient, onRingPerson) = onePerson

                                    // A group is sent to as a group: the same people her words
                                    // to it reach, decided the same way when it goes — see
                                    // Outbox.sendPhoto.
                                    val photoTarget: Recipient? = when {
                                        onePersonRecipient != null ->
                                            onePersonRecipient.takeIf { Forwarding.canCarryMedia(it) }
                                        else -> conversationReplyTarget?.groupIdentity?.let { group ->
                                            Recipient(name = conversationTitle, groupIdentity = group)
                                        }
                                    }
                                    val onCameraRequested: (() -> Unit)? = if (
                                        settings.enablePhotoSending && photoTarget != null
                                    ) {
                                        { photoRecipient = photoTarget }
                                    } else null
                                    ConversationScreen(
                                        title = conversationTitle,
                                        messages = conversationMessages,
                                        groupPicture = conversationGroup,
                                        playingVoiceMessage = playingVoice,
                                        onVoiceMessageToggled = { message ->
                                            val path = message.audioPath
                                            if (path != null) voice.playVoiceMessage(message.id, path)
                                        },
                                        // One voice at a time, and a film is a voice. Both are stopped
                                        // rather than only the one that happens to be sounding, because
                                        // she cannot be asked to know which of the two she started.
                                        onFilmStarted = {
                                            voice.stopSpeaking()
                                            voice.stopVoiceMessage()
                                        },
                                        // The reply affordance is present or it is not. Never greyed out, and
                                        // never with a reason beside it — see "Errors are the caregiver's
                                        // business" in docs/conversations-and-groups.md.
                                        canReply = conversationReplyTarget != null,
                                        onReactionSelected = { emoji ->
                                            conversationReplyTarget?.let { target ->
                                                scope.launch { conversation.sendReaction(target, emoji) }
                                            }
                                        },
                                        onTextReplySubmitted = { text ->
                                            conversationReplyTarget?.let { target ->
                                                scope.launch { conversation.sendTextReply(target, text) }
                                            }
                                        },
                                        // Holding a message of hers that did not go sends it again, in place.
                                        onResendRequested = { message ->
                                            scope.launch { conversation.resend(message) }
                                        },
                                        onBack = { goTo(Screen.Messages) },
                                        onBackToHome = { goTo(Screen.Home) },
                                        onHomeTab = startHomeOver,
                                        onSpeakRequested = { text -> voice.speakText(text) },
                                        onStopSpeaking = { voice.stopSpeaking() },
                                        onPhoneBookRequested = { goTo(Screen.PhoneBook) },
                                        onMailRequested = { goTo(Screen.Look) },
                                        messagesMarked = messagesMarked,
                                        emojiChoiceEnabled = settings.enableEmojiChoice,
                                        support = supportButtonState,
                                        onSupportPressed = onSupportPressed,
                                        onOpenAdvancedSheet = { message ->
                                            activeAdvancedTarget = AdvancedTarget.MessageTarget(
                                                message = message,
                                                conversationMessages = conversationMessages
                                            )
                                        },
                                        personPhoto = conversationFace,
                                        onRingPerson = onRingPerson,
                                        // One person out of several. A group is where she most
                                        // often wants one of them — somebody asked her something
                                        // and she wants to answer *them*, by voice. What can be
                                        // offered for each is worked out before the buttons are
                                        // drawn, so a name she can press always leads somewhere.
                                        memberRoute = { face ->
                                            GroupMemberRoute.of(
                                                face = face,
                                                transport = conversationTransport,
                                                contacts = conversationContacts
                                            )
                                        },
                                        // No second question: the button she pressed had this
                                        // person's name in it, which is everything
                                        // [ConfirmCallDialog] would have asked.
                                        onRingMember = { member ->
                                            viewModel.placeCallWithDetails(
                                                context = this@MainActivity,
                                                name = member.name,
                                                phoneNumber = member.phoneNumber,
                                                threemaId = member.threemaId,
                                                photoUri = member.photoUri
                                            )
                                        },
                                        // Away from the group, to that one person. The router
                                        // picks the way, as it does for everybody she writes to
                                        // from the address book.
                                        onWriteToMember = { member, text ->
                                            scope.launch {
                                                conversation.sendNewMessage(member.recipient, text)
                                            }
                                        },
                                        onCameraRequested = onCameraRequested,
                                        onPhotoToMember = if (settings.enablePhotoSending) {
                                            { memberRecipient -> photoRecipient = memberRecipient }
                                        } else null,
                                        isRestless = isRestless
                                    )
                                }
                                Screen.Look -> {
                                    val lookFeed by viewModel.lookFeed.collectAsState()
                                    val seenPictureIds by viewModel.seenPictureIds.collectAsState()
                                    // The same list the conversation offers, collected again
                                    // here: read once at the top it would recompose every screen
                                    // whenever she writes to somebody.
                                    val forwardFromLook by viewModel.forwardRecipients.collectAsState()
                                    LookScreen(
                                        items = lookFeed,
                                        seenPictureIds = seenPictureIds,
                                        onPictureSeen = { viewModel.markPictureSeen(it) },
                                        onPicturesSeen = { viewModel.markPicturesSeen(it) },
                                        onBackToHome = { goTo(Screen.Home) },
                                        onHomeTab = startHomeOver,
                                        onMailRead = { mailId -> viewModel.markMailAsRead(mailId) },
                                        onSpeakRequested = { text -> voice.speakText(text) },
                                        onStopSpeaking = { voice.stopSpeaking() },
                                        onCallRequested = { goTo(Screen.PhoneBook) },
                                        onMessagesRequested = { goTo(Screen.Messages) },
                                        messagesMarked = messagesMarked,
                                        searchEnabled = settings.enableSearch,
                                        support = supportButtonState,
                                        onSupportPressed = onSupportPressed,
                                        onOpenAdvancedSheet = { mail ->
                                            activeAdvancedTarget = AdvancedTarget.MailTarget(mail)
                                        },
                                        forwardRecipients = forwardFromLook,
                                        onForwardRequested = { message, recipient, whenDone ->
                                            scope.launch {
                                                whenDone(conversation.forwardMedia(message, recipient))
                                            }
                                        },
                                        openStatus = statusToOpen,
                                        onStatusOpened = { statusToOpen = null },
                                        onLeave = { viewModel.markLookFeedAsRead() },
                                        isRestless = isRestless
                                    )
                                }
                                Screen.Appointments -> {
                                    // The clock is collected here rather than beside the other
                                    // state at the top of this block. Read up there it ticks the
                                    // whole tree — every screen, once a second — because the
                                    // recompose scope is this entire lambda. Read inside the
                                    // branch, only the screen that needs it moves.
                                    val now by viewModel.currentTimeMillis.collectAsState()
                                    val plannedAppointments by calendar.plannedAppointments.collectAsState()
                                    // Null while nobody has asked the calendar yet, which is not
                                    // an empty calendar and must not be drawn as one — the same
                                    // distinction AppointmentsViewModel.appointments keeps.
                                    val plannedList = plannedAppointments?.let { herCalendar ->
                                        // Without the birthdays when a caregiver has switched
                                        // them off, like the home screen.
                                        Agenda.planned(
                                            Agenda.shown(herCalendar, settings.showBirthdays),
                                            now,
                                            ::startOfDay
                                        )
                                    }
                                    AppointmentsScreen(
                                        appointments = plannedList,
                                        now = now,
                                        openAt = appointmentToOpen,
                                        onBackToHome = { goTo(Screen.Home) },
                                        onHomeTab = startHomeOver,
                                        onCallRequested = { goTo(Screen.PhoneBook) },
                                        onMessagesRequested = { goTo(Screen.Messages) },
                                        onMailRequested = { goTo(Screen.Look) },
                                        onSpeakRequested = { text -> voice.speakText(text) },
                                        onStopSpeaking = { voice.stopSpeaking() },
                                        messagesMarked = messagesMarked,
                                        support = supportButtonState,
                                        onSupportPressed = onSupportPressed,
                                        isRestless = isRestless
                                    )
                                }
                                Screen.SupportStatus -> SupportStatusScreen(
                                    request = currentSupportRequest,
                                    members = supportMembers,
                                    onSpeakRequested = { text -> voice.speakText(text) },
                                    onReAlert = {
                                        support.triggerSupportRequest(currentScreen.name, isReAlert = true)
                                    },
                                    onBack = { goTo(Screen.Home) }
                                )
                                Screen.Caregiver -> CaregiverScreenHost(
                                    caregiver = caregiver,
                                    payment = payment,
                                    onBack = { goTo(Screen.Home) }
                                )
                            }
                        }
                    }
                }

                // After the screens, not before them: this is drawn in the same window rather than
                // as a dialog, so it must come later to sit on top, and its BackHandler must be
                // registered after the screens' one to be the one back closes.
                if (photoRecipient != null) {
                    val recipient = photoRecipient!!
                    CameraScreen(
                        recipient = recipient,
                        cameraSwitchEnabled = settings.enableCameraSwitch,
                        onSend = { path -> conversation.sendPhoto(recipient, path) },
                        onSent = {
                            val target = photoRecipient
                            photoRecipient = null
                            target?.let { rec ->
                                conversation.openConversationWith(rec)
                                if (conversation.openConversationId.value != null) {
                                    currentScreen = Screen.Conversation
                                }
                            }
                        },
                        onClose = { photoRecipient = null }
                    )
                } else if (photoFirst) {
                    CameraScreen(
                        recipient = null,
                        cameraSwitchEnabled = settings.enableCameraSwitch,
                        onPhotoReady = { path -> photoReadyPath = path },
                        isSent = { photoSentSuccessfully },
                        onClose = {
                            photoFirst = false
                            photoReadyPath?.let { File(it).delete() }
                            photoReadyPath = null
                            photoSentSuccessfully = false
                        }
                    )

                    photoReadyPath?.let { path ->
                        val photoRecipients by viewModel.photoRecipients.collectAsState()
                        PhotoRecipientFlow(
                            photoPath = path,
                            targets = photoRecipients,
                            onSend = { target -> conversation.sendPhoto(target.recipient, path) },
                            onSuccess = { target ->
                                photoSentSuccessfully = true
                                val sentRecipient = target.recipient
                                photoReadyPath = null
                                photoFirst = false
                                conversation.openConversationWith(sentRecipient)
                                if (conversation.openConversationId.value != null) {
                                    currentScreen = Screen.Conversation
                                }
                            },
                            onDismiss = {
                                photoReadyPath = null
                            }
                        )
                    }
                }

                activeAdvancedTarget?.let { target ->
                    AdvancedActionsSheet(
                        target = target,
                        onDismiss = { activeAdvancedTarget = null },
                        onDeleteMessage = { messageId, subjectName ->
                            conversation.deleteMessage(messageId, subjectName)
                        },
                        onDeleteConversation = { convId, subjectName ->
                            conversation.deleteConversation(convId, subjectName)
                        },
                        onBlockNumber = { number ->
                            calls.blockNumber(this@MainActivity, number)
                        },
                        onToggleMute = { convId, subjectName ->
                            conversation.toggleMuteConversation(convId, subjectName)
                        },
                        isConversationMuted = { convId ->
                            conversation.isConversationMuted(convId)
                        },
                        onResendRequested = { msg ->
                            scope.launch { conversation.resend(msg) }
                        },
                        onEditContact = { contact ->
                            if (!ContactEditing.editContact(this@MainActivity, contact.id)) {
                                ContactEditing.saveNumber(
                                    this@MainActivity,
                                    contact.phoneNumber,
                                    contact.name
                                )
                            }
                        },
                        onEditCallRecord = { record ->
                            val contactId = record.contactId
                            if (contactId != null) {
                                ContactEditing.editContact(this@MainActivity, contactId)
                            } else {
                                ContactEditing.saveNumber(this@MainActivity, record.number)
                            }
                        },
                        onCallBack = { record ->
                            viewModel.placeCallWithNumber(
                                this@MainActivity,
                                record.name ?: record.number,
                                record.number
                            )
                        },
                        onCallContact = { contact ->
                            viewModel.placeCall(this@MainActivity, contact)
                        }
                    )
                }


                // The recipient picker and the compose dialog used to hang here. Both existed
                // only to serve the "write to someone" button, and with that gone there was no
                // way into either of them. Writing to someone means opening what they wrote and
                // answering there.
                //
                // ComposeMessageDialog has since found the other door it was kept for: writing
                // to one person out of a group, from the list of everybody in it — see
                // [ConversationScreen] and [GroupMemberDialog]. RecipientPickerDialog is still
                // unreferenced, along with the viewModel.writableRecipients flow that fed it,
                // because choosing a stranger from a list is the part that was never wanted.
            }
            }
        }
    }

    // A dialog, the keyboard or a swipe from the edge can bring Android's buttons back; each time
    // this window has the phone's attention again, they go. See [HiddenNavigationBar].
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) HiddenNavigationBar.apply(this)
    }

    override fun onStart() {
        super.onStart()
        hingeWatch.start()
    }

    override fun onResume() {
        super.onResume()
        HiddenNavigationBar.apply(this)
        checkRestartHint()
        viewModel.refreshContacts()
        viewModel.checkPermissions(this)
        // Watched as well since the calendar is: this read is for coming back after a long time
        // away (the clock has moved on, a permission may have changed) and also restarts the
        // watch if it could not start before. An appointment added on her daughter's laptop
        // still reaches the phone through Google's own sync. Debounced in the view model so that
        // fast app switching does not repeatedly query the calendar provider.
        calendar.refreshAppointments(force = false)
        // Idempotent. Here because a permission granted over adb reaches a running process
        // without any callback, and the service only starts the counter when it starts.
        AppGraph.stepCounter(applicationContext).start()
        offerToBeTheTelephone()
        // Coming back from anywhere, including Android's own location settings — and including a
        // caregiver who changed it there weeks later without ever touching this app.
        MessengerService.refreshLocationEntitlement(applicationContext)
        // And after Android stopped it for having used up a time-limited type: being in front is
        // what gives the hours back.
        MessengerService.restartAfterTimeLimit(applicationContext)
        // The payment card is the one thing on the home screen that can go stale with nobody
        // touching the phone: the unlock window simply runs out. Watched while she is in front
        // of it, and dropped again in onPause.
        payment.watchPaymentState(true)
        // So that an update which kills this screen while she is looking at it puts it back.
        HomeAfterUpdate.markInFront(this, true)
    }

    override fun onPause() {
        super.onPause()
        payment.watchPaymentState(false)
        HomeAfterUpdate.markInFront(this, false)
    }

    override fun onStop() {
        super.onStop()
        hingeWatch.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        // runCatching: unregistering one that never registered throws, and a launcher being torn
        // down is not the place to care.
        runCatching { unregisterReceiver(screenOffReceiver) }
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CALL_PHONE)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_SMS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CALL_LOG)
        }

        // Her appointments. Refused, this costs nothing but the block on her home screen — see
        // data/CalendarRepository, which reads a refusal the same way as a phone with no calendar
        // on it at all.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CALENDAR)
        }

        // Threema calls carry audio over WebRTC, and RECORD_AUDIO is a runtime permission:
        // declaring it in the manifest is not enough. Without the grant a call still connects
        // and still looks completely normal — it just carries silence from her side, which she
        // has no way to diagnose or even describe.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // The step counter at the foot of her home screen. Refused, this costs nothing but the
        // steps block — see data/StepCounter.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // Where the phone is, for the caregiver portal. Both are asked together because Android
        // shows them as one choice with a precise/approximate switch inside it — asking for coarse
        // alone would deny the caregiver the precise option without ever offering it.
        //
        // Declined is a supported outcome, not a broken install: nothing on her screen changes, no
        // position is ever published, and the portal simply has no position line. It is the one
        // permission here that costs her nothing to refuse.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // Nothing to ask for in the batch, which is the ordinary case after the first run —
            // so the follow-up that normally rides on its result needs asking for here instead.
            requestBackgroundLocation()
        }
    }

    /**
     * Ask for location "all the time", once the plain location permission is in hand.
     *
     * Ordered rather than bundled, for the reason on [backgroundLocationLauncher]. Skipped
     * entirely when the foreground grant did not happen: asking for the background half of a
     * permission the app does not have is refused by the system anyway, and would show the
     * caregiver a screen that cannot do anything for them.
     */
    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val foreground = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val background = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

        if (foreground && !background) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showFor(intent)
    }

    /**
     * Everything an arriving intent can ask for, in one place.
     *
     * Whether it is a press of Home is decided **first**, on the intent as it arrived. The dial
     * and write requests mark themselves answered by turning into a plain `ACTION_MAIN` — and a
     * plain `ACTION_MAIN` with no category is exactly what [HomeIntent] reads as the home
     * button. Asked afterwards, every "write to this number" from another app opened the
     * conversation and was sent home again in the same instant, which on the phone looked like
     * the request doing nothing at all.
     */
    private fun showFor(intent: Intent?) {
        val home = HomeIntent.isHome(intent, EXTRA_ATTENTION, EXTRA_MISSED_CALL)
        showForArrival(intent)
        showForDialling(intent)
        showForWriting(intent)
        showForMissedCall(intent)
        showForHome(home)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            homeRequests.intValue += 1
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Which touches were taps, for the restless count: scrolling and pinching are not, and nor
     * are taps on the clock or a screen title, where the caregiver gesture lives — see
     * [CaregiverTapZone] — nor keys on the dialpad ([KeypadTapZone]), where rapid typing is
     * expected, nor reaction emoji buttons ([ReactionTapZone]), where rapid repeated taps (e.g. 😂)
     * are a normal expression of joy or humor rather than distress.
     *
     * Constructed with [TouchTolerance.slopFor] so that platform touch filtering agrees with
     * Compose's expanded slop (~20 dp instead of ~8 dp).
     */
    private val tapFilter by lazy {
        val platformSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        TapFilter(TouchTolerance.slopFor(platformSlop))
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        var filterSaidTap = false
        when (ev?.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touchDownAt = System.currentTimeMillis()
                CaregiverTapZone.touchStarted()
                KeypadTapZone.touchStarted()
                ReactionTapZone.touchStarted()
                UiTrace.touchStarted()
                tapFilter.onDown(ev.x, ev.y)
            }
            android.view.MotionEvent.ACTION_MOVE -> tapFilter.onMove(ev.x, ev.y)
            android.view.MotionEvent.ACTION_POINTER_DOWN,
            android.view.MotionEvent.ACTION_CANCEL -> {
                tapFilter.onCancel()
                UiTrace.touchCancelled()
            }
            android.view.MotionEvent.ACTION_UP -> {
                filterSaidTap = tapFilter.onUp(ev.x, ev.y)
            }
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev?.actionMasked == android.view.MotionEvent.ACTION_UP) {
            if (!CaregiverTapZone.landedOnZone()) {
                val now = System.currentTimeMillis()
                val density = resources.displayMetrics.density
                val outcome = when (UiTrace.lastTouchResult) {
                    UiTrace.TouchResult.ANSWERED -> TouchOutcome.ANSWERED
                    UiTrace.TouchResult.UNANSWERED -> TouchOutcome.UNANSWERED
                    UiTrace.TouchResult.NEITHER -> TouchOutcome.NEITHER
                }
                support.recordTouch(
                    TouchSample(
                        downAt = if (touchDownAt > 0L) touchDownAt else now,
                        upAt = now,
                        xDp = ev.x / density,
                        yDp = ev.y / density,
                        screenWidthDp = resources.configuration.screenWidthDp.toFloat(),
                        screen = UiTrace.screen,
                        isTap = filterSaidTap && !KeypadTapZone.landedOnZone() && !ReactionTapZone.landedOnZone(),
                        outcome = outcome
                    )
                )
            }
        }
        return handled
    }

    /**
     * Ask Android to make this app the telephone.
     *
     * Asked **once per install** unless a caregiver asks again by holding the keys, because the
     * system screen is a question she cannot answer and would be alarming to meet twice. Refusing
     * it costs nothing that was working before: calls carry on going to whichever app held the
     * role, which on her phone is one of ours anyway.
     *
     * Held back until the app may actually place a call. Offering to *be* the telephone before
     * being allowed to *use* one is two system screens in the wrong order, and the second of them
     * would be asking for a job the app cannot yet do.
     *
     * @param force a caregiver asking deliberately, which ignores the once-only rule. That door
     *   has to exist: the system asks at most once by itself, and "not now" pressed by mistake
     *   would otherwise be final.
     */
    private fun offerToBeTheTelephone(force: Boolean = false) {
        caregiverIsSettingUp = force
        if (!askForRole(
                force = force,
                remembered = ASKED_TO_BE_TELEPHONE,
                needs = Manifest.permission.CALL_PHONE,
                ask = DefaultPhoneRole.requestIntent(this),
                launcher = dialerRoleLauncher
            )
        ) {
            // Nothing to ask, so the chain from the first answer to the second never runs.
            offerToBeTheMessagesApp(force = force)
        }
    }

    /**
     * Ask Android to make this app the messages app.
     *
     * Weightier than it sounds, and worth a caregiver understanding before they say yes: from that
     * moment Android stops filing incoming text messages itself and hands each one to this app
     * instead. Picture messages have no implementation here and are dropped — see
     * [ch.heuscher.gentleconnect.sms.MmsDeliverReceiver], which says so plainly.
     *
     * Same rules as the telephone question: once per install, unless a caregiver asks deliberately.
     */
    private fun offerToBeTheMessagesApp(force: Boolean = false) {
        askForRole(
            force = force,
            remembered = ASKED_TO_BE_MESSAGES,
            needs = Manifest.permission.READ_SMS,
            ask = DefaultMessagesRole.requestIntent(this),
            launcher = smsRoleLauncher
        )
    }

    /**
     * The shape both role questions share.
     *
     * @param needs the runtime permission that has to be in hand first. Offering to *be* the
     *   telephone before being allowed to *use* one is two system screens in the wrong order, and
     *   the second would be asking for a job the app cannot yet do.
     * @param ask null when there is nothing to ask for: already held, no such role on this
     *   platform, or a device with no telephony at all. All three want the same silence.
     * @return whether a system screen was actually opened.
     */
    private fun askForRole(
        force: Boolean,
        remembered: String,
        needs: String,
        ask: Intent?,
        launcher: ActivityResultLauncher<Intent>
    ): Boolean {
        if (!force) {
            if (ContextCompat.checkSelfPermission(this, needs) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            if (setupPreferences().getBoolean(remembered, false)) return false
        }
        if (ask == null) return false

        setupPreferences().edit().putBoolean(remembered, true).apply()
        return try {
            launcher.launch(ask)
            true
        } catch (e: Exception) {
            // A device with no screen to show for this. The app carries on the way it always has.
            false
        }
    }

    private fun setupPreferences() = getSharedPreferences(SETUP_PREFERENCES, MODE_PRIVATE)

    private fun checkRestartHint() {
        val currentBoot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val holdsHome = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME)
        } else {
            false
        }
        val prefs = setupPreferences()
        if (!holdsHome && currentBoot > 0) {
            prefs.edit().putInt(KEY_LAST_NOT_HOME_BOOT, currentBoot).apply()
        }
        val lastNotHomeBoot = prefs.getInt(KEY_LAST_NOT_HOME_BOOT, -1)
        val dismissedBoot = prefs.getInt(KEY_DISMISSED_BOOT, -1)
        showRestartHint = RestartHint.shouldShow(
            holdsHome = holdsHome,
            currentBoot = currentBoot,
            lastNotHomeBoot = lastNotHomeBoot,
            dismissedBoot = dismissedBoot
        )
    }

    private fun dismissRestartHint() {
        val currentBoot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (currentBoot > 0) {
            setupPreferences()
                .edit()
                .putInt(KEY_DISMISSED_BOOT, currentBoot)
                .apply()
        }
        showRestartHint = false
    }

    /**
     * Ask the system to confirm the device credential (PIN / pattern / password).
     *
     * A confirmation here is a strong unlock, and a strong unlock is what lets Google Wallet pay
     * from a phone that was opened with 2D face unlock. What it never does is decide the answer:
     * every path out of here ends in a fresh read of the phone's own state, so a phone that
     * cannot show this screen at all says "cannot pay" instead of "ready to pay".
     */
    private fun launchPaymentPinAuth() {
        // Already lit. A second PIN screen would ask her to unlock a phone that is unlocked
        // enough to pay — so the press answers the likelier question instead: how does this
        // stop. See PaymentViewModel.showPaymentSleepHint.
        if (payment.paymentButtonState.value == PaymentButtonState.Ready) {
            payment.showPaymentSleepHint()
            return
        }

        // A hint set on this phone comes first, with the reason the PIN is about to be asked for;
        // its button comes back into [startPaymentPinPrompt]. No hint, no message box — straight
        // to the PIN, as before. See PaymentPinHint.
        val hint = payment.getPaymentPinHint(this)
        if (hint != null) {
            payment.showPaymentPinHint(hint)
            return
        }

        startPaymentPinPrompt()
    }

    /** The PIN itself: the keypad where it can be asked for, the system's confirm screen if not. */
    private fun startPaymentPinPrompt() {
        // The keypad, not the fingerprint reader, on a phone set to PIN on the website and where
        // the system can be asked for it that way. Everywhere else, and if the keypad cannot be
        // raised at all, the system's own confirm screen below. See PaymentAuthMethod.
        val pinOnly = viewModel.remoteSettings.value.paymentAuthMethod == PaymentAuthMethod.PIN
        if (pinOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && showPaymentPinPrompt()) return

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        // Null when no PIN, pattern or password is set — exactly the phone on which a tap at the
        // terminal is refused. It used to be treated as success, which put a green "ready to pay"
        // in front of her precisely when paying was impossible.
        val intent = keyguardManager?.createConfirmDeviceCredentialIntent(
            getString(R.string.payment_auth_title),
            getString(R.string.payment_auth_subtitle)
        )
        if (intent == null) {
            payment.refreshPaymentState()
            return
        }
        // She is paying again. Whatever ended the last payment stops applying here.
        payment.beginPaymentAuth()
        try {
            paymentPinLauncher.launch(intent)
        } catch (e: Exception) {
            payment.refreshPaymentState()
        }
    }

    /**
     * The PIN keypad itself, with no fingerprint sheet in front of it.
     *
     * `createConfirmDeviceCredentialIntent` leaves that choice to the system, and One UI leads
     * with the fingerprint reader whenever prints are enrolled. Three are enrolled on her phone
     * and about half of the touches are rejected, so the screen that opens at the till is the one
     * she is least able to finish — and a rejected fingerprint reads, to her, as the card
     * refusing to pay. Asking for DEVICE_CREDENTIAL and nothing else opens straight on the
     * keypad, which is the one way in that always works.
     *
     * Nothing about what the card then *does* changes: the keystore counts a confirmed device
     * credential as the same strong unlock it counted before — see
     * [ch.heuscher.gentleconnect.data.payment.KeystorePaymentAuthGate] — and the fingerprint
     * still unlocks the phone everywhere else, including at the lock screen, where it also lights
     * the card.
     *
     * Returns false when the sheet could not be raised at all, and the caller falls back to the
     * system's own confirm screen rather than leaving her with no way to pay.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun showPaymentPinPrompt(): Boolean {
        val prompt = try {
            BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.payment_auth_title))
                .setSubtitle(getString(R.string.payment_auth_subtitle))
                // No negative button, and none is permitted with a device credential: the sheet
                // carries its own Cancel.
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
        } catch (e: Exception) {
            return false
        }

        // Both ways the sheet can close end the same way, and for the same reason as
        // [paymentPinLauncher]: whether the card is lit is the phone's answer, read fresh, not
        // this sheet's report. A wrong PIN is neither of them — the sheet stays up, she is still
        // in the middle of it, and there is nothing to re-read yet.
        val listener = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                payment.beginPaymentAuth()
                payment.refreshPaymentState()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                payment.beginPaymentAuth()
                payment.refreshPaymentState()
            }
        }

        // She is paying again. Whatever ended the last payment stops applying here.
        payment.beginPaymentAuth()
        return try {
            prompt.authenticate(
                CancellationSignal(),
                ContextCompat.getMainExecutor(this),
                listener
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Somebody asked the telephone to dial a number.
     *
     * This only ever happens because this app holds the dialler role: a `tel:` link in a browser,
     * a number in a message, another app's "call this shop" button. The number is put on the keys
     * and left there — see [numberToDial], and see [ch.heuscher.gentleconnect.ui.KeypadScreen],
     * which is where the confirmation she still has to give lives.
     *
     * `ACTION_DIAL` with no number at all is a legitimate request too — it means "open the
     * telephone" — and it lands on the keys with an empty field, which is exactly right.
     */
    private fun showForDialling(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_DIAL && action != Intent.ACTION_VIEW) return

        val data = intent.data
        val number = when {
            // Only DIAL means "open the telephone" with nothing named. A VIEW with no address is
            // somebody else's request — the older "write to" form is one — and not for the keys.
            data == null -> if (action == Intent.ACTION_DIAL) "" else return
            data.scheme.equals("tel", ignoreCase = true) -> Uri.decode(data.schemeSpecificPart).orEmpty()
            // A `view` of anything that is not a telephone number is not this app's business.
            else -> return
        }

        // Cleared from the intent so that coming back to the launcher later — a rotation, a return
        // from the address book — does not put a week-old number back under her thumb.
        intent.data = null
        intent.action = Intent.ACTION_MAIN

        numberToDial.value = number.trim()
        // Counted rather than watched, for the same reason the arrival wake is counted: two
        // requests to dial the same number are two requests, and a screen that only reacts to the
        // value changing would ignore the second one.
        dialRequests.intValue += 1
    }

    /**
     * Somebody asked the messages app to write to a number.
     *
     * The counterpart of [showForDialling], and it arrives for the same reason: this app holds the
     * messages role, so every `smsto:` link on the phone comes here. It was accepted by the
     * manifest and then dropped on the floor — [showForDialling] answers only `tel:` — so the
     * request left her wherever she happened to be, most often the home screen, with no sign that
     * anything had been asked for.
     *
     * She lands on the conversation with that person, which is what writing to somebody means in
     * this app: her replies are written under what they wrote, never in a compose form. So the
     * outcome is the same one she gets by tapping their message herself, and there is nothing new
     * for her to learn.
     *
     * Nobody having written from the number yet — somebody she has only ever rung, a shop — opens
     * the line to write on over the message list instead; see [ConversationViewModel.recipientForNumber].
     * `sms:` links arrive as VIEW rather than SENDTO, and "open the messages app" as MAIN with
     * APP_MESSAGING; all three are the same request here.
     * Several recipients are cut to the first: `smsto:` allows a list, this app has no screen that
     * writes to several people at once outside a group, and silently addressing one of the four is
     * better than addressing none.
     */
    private fun showForWriting(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        val addressed = when {
            // "Write to this number": SENDTO from an app's button, VIEW from a link in a browser
            // or a message. Both are the same request, and Chrome sends the second.
            (intent.action == Intent.ACTION_SENDTO || intent.action == Intent.ACTION_VIEW) &&
                WriteRequest.isMessageScheme(data?.scheme) ->
                WriteRequest.firstAddressIn(Uri.decode(data?.schemeSpecificPart))
            // The older way of asking the same thing, still used: the number rides in an extra.
            intent.action == Intent.ACTION_VIEW && intent.type == WriteRequest.LEGACY_MESSAGES_TYPE ->
                WriteRequest.firstAddressIn(intent.getStringExtra(WriteRequest.EXTRA_ADDRESS))
            // "Open the messages app", from anything that asks for whichever one the phone has.
            intent.action == Intent.ACTION_MAIN &&
                intent.hasCategory(Intent.CATEGORY_APP_MESSAGING) -> null
            else -> return
        }

        // Cleared for the reason the dial request is cleared: coming back to the launcher a week
        // later must not reopen a conversation because of something a browser once asked for.
        // Setting the data clears the legacy form's type as well.
        intent.data = null
        intent.action = Intent.ACTION_MAIN
        intent.removeCategory(Intent.CATEGORY_APP_MESSAGING)
        intent.removeExtra(WriteRequest.EXTRA_ADDRESS)

        numberToWriteTo.value = addressed
        writeRequests.intValue += 1
    }

    /**
     * She pressed the notice about a call she missed.
     *
     * Cleared as it is read, like the dial and write requests above: coming back to the launcher
     * tomorrow must not put her on the list of calls because of something she pressed today.
     */
    private fun showForMissedCall(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_MISSED_CALL, false)) return
        intent.removeExtra(EXTRA_MISSED_CALL)
        missedCallRequests.intValue += 1
    }

    /**
     * She pressed the telephone's home button, or tapped the launcher icon.
     *
     * As the default launcher, pressing the hardware or navigation-bar Home button delivers
     * [Intent.ACTION_MAIN] with [Intent.CATEGORY_HOME] to the running task. Without catching this,
     * walking into messages, the post, calls, appointments or caregiver settings leaves her
     * stranded on that screen — the button she expects above all others to take her home does
     * nothing.
     */
    private fun showForHome(pressed: Boolean) {
        if (pressed) homeRequests.intValue += 1
    }

    /**
     * Put this screen in front of her because something arrived.
     *
     * Two flags, both temporary. [setTurnScreenOn] is what actually lights the phone up on the
     * table; [setShowWhenLocked] is what lets her see the card without unlocking anything, which
     * matters because she may well not know she has locked it. Both are taken away again shortly
     * afterwards: a launcher that permanently shows over the lock screen is a phone with no lock
     * at all, which is not this app's decision to make on her behalf.
     */
    private fun showForArrival(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_ATTENTION, false)) return
        // Once. Otherwise every rotation and every return to the app would light the screen again
        // for a message she read hours ago.
        intent.removeExtra(EXTRA_ATTENTION)
        arrivedMessageId.value = intent.getStringExtra(EXTRA_MESSAGE_ID)?.takeIf { it.isNotBlank() }
        intent.removeExtra(EXTRA_MESSAGE_ID)
        wokenForArrival.intValue += 1

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.decorView.postDelayed({
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }, SHOWN_FOR_MS)
    }

    private enum class Screen {
        Home,
        PhoneBook,
        /** The keys, for a number that is nobody's yet. Below [PhoneBook], never a tab. */
        Keypad,
        /** The telephone's own record of calls. Below [PhoneBook], never a tab. */
        RecentCalls,
        Messages,
        /** One person's conversation, reached by tapping a message. Below [Messages], never a tab. */
        Conversation,
        /** Everything that arrived which she can only look at: pictures, and letters. */
        Look,
        /** Everything she has coming. Below [Home], never a tab. */
        Appointments,
        /** Active support request status screen. */
        SupportStatus,
        /**
         * The one screen that is not hers: pairing, credentials, and what did not work. Reached
         * only by five fast taps on the clock, and never a tab — see [CaregiverScreen].
         */
        Caregiver
    }

    /** Everything behind the Call button: where missed calls are marked, and leaving ends them. */
    private val CALLS_TAB = setOf(Screen.PhoneBook, Screen.Keypad, Screen.RecentCalls)

    companion object {
        /** Set on the intent behind the notice in [ch.heuscher.gentleconnect.service.Attention]. */
        const val EXTRA_ATTENTION = "ch.heuscher.gentleconnect.ATTENTION"

        /** Which message that notice was about, so pressing it opens the message and not a screen. */
        const val EXTRA_MESSAGE_ID = "ch.heuscher.gentleconnect.MESSAGE_ID"

        /** Set on the intent behind the missed-call notice, which shows her the list of calls. */
        const val EXTRA_MISSED_CALL = "ch.heuscher.gentleconnect.MISSED_CALL"

        /** How long the phone stays lit, and stays willing to show itself over a lock screen. */
        private const val SHOWN_FOR_MS = 30_000L

        /** Where the once-only questions asked during setup are remembered. */
        private const val SETUP_PREFERENCES = "gentle_connection_setup"

        /** Whether Android has already been asked to make this app the telephone. */
        private const val ASKED_TO_BE_TELEPHONE = "asked_to_be_telephone"

        /** And the same for her text messages. */
        private const val ASKED_TO_BE_MESSAGES = "asked_to_be_messages"

        private const val KEY_LAST_NOT_HOME_BOOT = "last_not_home_boot"
        private const val KEY_DISMISSED_BOOT = "dismissed_boot"
    }
}
