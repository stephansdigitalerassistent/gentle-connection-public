package ch.heuscher.gentleconnect.data

import android.content.Context
import android.util.Log
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.db.ThreemaMatchEntity
import ch.heuscher.gentleconnect.data.threema.ThreemaClient
import ch.heuscher.gentleconnect.data.threema.ThreemaConnectionState
import ch.heuscher.gentleconnect.data.threema.ThreemaStartupPolicy
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppBridgeProtocol
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppClient
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppEvent
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppGroups
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppLinkState
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppMessageMapper
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.GroupIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts the messenger clients and listens to them, for as long as the process lives.
 *
 * This used to be the home screen's job. The view model started the clients, and the view model
 * held the only listeners for the Threema connection, the directory's contact matches, the
 * WhatsApp groups and the pairing events. After a start with nobody looking at the screen — a
 * boot, an update, Android reclaiming the process — [ch.heuscher.gentleconnect.service.MessengerService]
 * connected the clients with none of that attached: group messages from people the client had
 * not been told about were rejected, and a connection that failed was never written down.
 *
 * Everything here belongs to the process. The screens read the state flows and ask through the
 * functions; none of them starts or stops a client.
 *
 * **The order of [start] is what makes calls and messages work**, and it is written down in
 * exactly one place, here:
 * 1. [ArrivalPipeline] first, so every message a client hands over has a collector waiting for it.
 *    The pipeline is also the only thing that acknowledges a message, and connecting without it
 *    would lose what arrives.
 * 2. The call manager before the Threema client connects, because it is the collector for
 *    incoming call offers and registers the PhoneAccount with Telecom.
 * 3. The clients: Threema only when an identity exists (see [ThreemaStartupPolicy]), WhatsApp
 *    always.
 */
object MessengerRuntime {

    private const val TAG = "MessengerRuntime"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null
    private var listening = false

    private val _threemaConnectionState =
        MutableStateFlow<ThreemaConnectionState>(ThreemaConnectionState.Disconnected)

    /** Whether the embedded Threema client is connected. Caregiver-facing only. */
    val threemaConnectionState: StateFlow<ThreemaConnectionState> = _threemaConnectionState.asStateFlow()

    private val _whatsAppLinkState = MutableStateFlow(WhatsAppLinkState.NOT_LINKED)

    /** Where the WhatsApp link stands. Caregiver-facing only. */
    val whatsAppLinkState: StateFlow<WhatsAppLinkState> = _whatsAppLinkState.asStateFlow()

    private val _whatsAppPairingCode = MutableStateFlow<String?>(null)

    /**
     * The code a caregiver types into WhatsApp to link this phone.
     *
     * Held only until it is used or the dialog closes. It is the whole of the linking secret and
     * it is short-lived; it goes on the caregiver's screen and nowhere else — never to logcat,
     * never to Firestore, never into the caregiver log.
     */
    val whatsAppPairingCode: StateFlow<String?> = _whatsAppPairingCode.asStateFlow()

    private val _whatsAppError = MutableStateFlow<String?>(null)

    /**
     * What went wrong with WhatsApp, for the caregiver dialog. Her screen carries no error copy;
     * the caregiver's must, or a button is pressed, nothing happens, and nothing says why.
     */
    val whatsAppError: StateFlow<String?> = _whatsAppError.asStateFlow()

    private val _whatsAppGroups = MutableStateFlow<Map<GroupIdentity, String>>(emptyMap())

    /**
     * The WhatsApp groups she is in, by address, named the way her screen names them.
     *
     * Collected as a state, not from the events: the names arrive when the bridge connects, which
     * is usually before any screen exists — see [WhatsAppClient.groups].
     */
    val whatsAppGroups: StateFlow<Map<GroupIdentity, String>> = _whatsAppGroups.asStateFlow()

    private val _whatsAppGroupMembers = MutableStateFlow<Map<GroupIdentity, List<String>>>(emptyMap())

    /**
     * Who is in each of those groups, by WhatsApp address. Empty for a group the bridge has not
     * described yet, and that emptiness means "we do not know", never "nobody".
     */
    val whatsAppGroupMembers: StateFlow<Map<GroupIdentity, List<String>>> = _whatsAppGroupMembers.asStateFlow()

    private val _avatarArrivals = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * The address of every profile picture the WhatsApp bridge has just stored, so that whatever
     * is drawing faces can draw it. Nothing is replayed: a screen that starts later reads the
     * pictures off disk anyway.
     */
    val avatarArrivals: SharedFlow<String> = _avatarArrivals.asSharedFlow()

    private val _contactSyncOutcome =
        MutableStateFlow<ContactSyncPolicy.Outcome>(ContactSyncPolicy.Outcome.Idle)

    /** What the caregiver dialog shows about matching, and whether one is in flight. */
    val contactSyncOutcome: StateFlow<ContactSyncPolicy.Outcome> = _contactSyncOutcome.asStateFlow()

    /** How many addresses the last request carried, so the answer can be reported against it. */
    @Volatile
    private var lastContactSyncSubmitted = 0

    /** When a match was last *sent*, rejected or not. See [ContactSyncPolicy.mayRun]. */
    private var lastContactSyncAttemptAt: Long? = null

    /**
     * Brings the messengers up, in the order the class comment gives, and attaches the listeners
     * once per process.
     *
     * Safe to call any number of times, from the service and from the home screen alike: the
     * pipeline and the listeners start once, and starting a client that is running does nothing.
     * Called again after [Messengers.stopAll], it brings the clients back with the listeners they
     * already had — [Messengers] hands out the same client instances, which is the point.
     */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app

        ArrivalPipeline.start(app)

        val threema = Messengers.threema(app)
        Messengers.callManager(app)
        val whatsApp = Messengers.whatsApp(app)

        if (!listening) {
            listening = true
            listenToThreema(app, threema)
            keepThreemaContactsSeeded(app, threema)
            listenToWhatsApp(app, whatsApp)
        }

        if (ThreemaStartupPolicy.shouldStartAutomatically(Messengers.threemaIdentity(app).hasIdentity())) {
            threema.start()
        }
        whatsApp.start()
    }

    /**
     * Stores her Threema credentials and brings the client up with them.
     *
     * The caregiver's deliberate provisioning act — the only path that is allowed to result in a
     * running Threema client. The key is handed straight to the repository and is never logged,
     * never held in a field, and never shown back on screen.
     *
     * A client already running holds the previous identity, so it is stopped first. Reused, never
     * replaced: every listener attached in [start] closes over this client instance, and a new
     * one would leave them all bound to a stopped client — her messages would stop arriving with
     * a connection that reports itself healthy.
     */
    fun provisionThreema(context: Context, threemaId: String, clientKey: String) {
        if (threemaId.isBlank() || clientKey.isBlank()) return
        val app = context.applicationContext
        Messengers.threemaIdentity(app).saveIdentity(threemaId = threemaId, clientKey = clientKey)
        restartThreema(app)
    }

    /** After an account was restored from a backup: the client goes again under the new identity. */
    fun restartThreema(context: Context) {
        val app = context.applicationContext
        Messengers.threema(app).stop()
        start(app)
    }

    /** The account has been unlinked from this phone; the client stays down until a new one exists. */
    fun threemaUnlinked(context: Context) {
        Messengers.threema(context.applicationContext).stop()
        _threemaConnectionState.value = ThreemaConnectionState.Disconnected
    }

    /**
     * Asks WhatsApp for the code that links this phone to her account.
     *
     * Caregiver-initiated only, from the hidden dialog. The number is hers — the account being
     * linked — and it goes down the pipe to the bridge rather than into its argv, because a
     * process's command line is readable through /proc by anything on the device.
     */
    fun requestWhatsAppPairing(phoneNumber: String) {
        _whatsAppPairingCode.value = null
        _whatsAppError.value = null

        val app = appContext
        if (app == null) {
            _whatsAppError.value = "WhatsApp bridge not running"
            return
        }
        if (!Messengers.whatsApp(app).requestPairingCode(phoneNumber)) {
            // The command never reached the subprocess — a dead pipe, or a bridge that has not
            // started yet. Said out loud, because the button otherwise does nothing at all.
            _whatsAppError.value = "The request did not reach the WhatsApp bridge"
        }
    }

    /** Forget the last WhatsApp error, so a retry starts from a clean screen. */
    fun clearWhatsAppError() {
        _whatsAppError.value = null
    }

    /** Forget the code once the dialog is done with it. */
    fun clearWhatsAppPairingCode() {
        _whatsAppPairingCode.value = null
    }

    /** Unlink this phone from her WhatsApp. Deliberate, and only from the caregiver dialog. */
    fun unlinkWhatsApp() {
        _whatsAppPairingCode.value = null
        appContext?.let { Messengers.whatsApp(it).unlink() }
    }

    /**
     * Matches her address book against Threema, because a caregiver asked.
     *
     * The only way this runs. It used to happen by itself on every connection, which is how the
     * directory came to answer 429 to everything; see [ContactSyncPolicy] for the whole account.
     * Refusing is a real answer here and is reported as one: the dialog is the caregiver's, and
     * the caregiver is somebody who can be told to come back in an hour.
     *
     * [fallback] is who to ask about when her address book cannot be read: the faces on her home
     * screen.
     */
    fun requestContactSync(context: Context, fallback: List<Contact>) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (!ContactSyncPolicy.mayRun(lastContactSyncAttemptAt, now)) {
                _contactSyncOutcome.value = ContactSyncPolicy.Outcome.TooSoon(
                    nextAllowedAt = ContactSyncPolicy.nextAllowedAt(lastContactSyncAttemptAt) ?: now
                )
                return
            }
            lastContactSyncAttemptAt = now
        }
        _contactSyncOutcome.value = ContactSyncPolicy.Outcome.Running

        scope.launch {
            val client = Messengers.threema(app)
            // Wait for the messenger first, exactly as a send does. Writing to a bridge that is
            // not up looks like success — the line goes into a pipe and nothing complains yet —
            // and then no answer ever comes.
            if (!awaitConnected(client)) {
                _contactSyncOutcome.value = ContactSyncPolicy.Outcome.Failed
                return@launch
            }

            submitContacts(app, client, fallback)

            // And an end to waiting. Found on the phone: the bridge subprocess exited in the same
            // 94 milliseconds the request was written to it, and the dialog sat on "Checking…"
            // with nothing coming. Half a minute of silence is a failure, and saying so is the
            // only thing that lets the caregiver try again.
            val answered = withTimeoutOrNull(ContactSyncPolicy.ANSWER_TIMEOUT_MS) {
                contactSyncOutcome.first { it !is ContactSyncPolicy.Outcome.Running }
            }
            if (answered == null) {
                _contactSyncOutcome.value = ContactSyncPolicy.Outcome.Failed
                record(app, CaregiverEventKind.CONTACT_SYNC_FAILED)
            }
        }
    }

    private fun submitContacts(app: Context, client: ThreemaClient, fallback: List<Contact>) {
        val contactsRepository = AppGraph.contacts(app)
        val source = contactsRepository.querySystemContacts().ifEmpty { fallback }

        // Every number of everybody, not one per person. The one each [Contact] carries is
        // whichever row the contacts provider returned first, so asking with those alone left
        // her son's mobile — the number he is actually on Threema under — never looked up at
        // all, while his office landline was asked about and of course said no.
        val everyNumber = contactsRepository.queryEveryNumber()
        val numbers = (everyNumber.map { it.number } + source.map { it.phoneNumber })
            .mapNotNull { ConversationLinksSource.normalizedNumber(it) }
            .distinct()
        Log.i(TAG, "Threema contact sync: submitting ${numbers.size} numbers of ${source.size} contacts")

        // The people none of whose numbers could be read, so nothing was looked up for them at
        // all — each one is somebody who silently cannot be reached. Counted per person rather
        // than per number: one unreadable number beside a good one is not a fault.
        val numbersByContact = everyNumber.groupBy { it.contactId }
        val unreadable = source.count { contact ->
            val theirs = numbersByContact[contact.id].orEmpty().map { it.number }
                .ifEmpty { listOf(contact.phoneNumber) }
            theirs.none { ConversationLinksSource.normalizedNumber(it) != null }
        }
        if (unreadable > 0) {
            record(app, CaregiverEventKind.CONTACTS_NOT_UNDERSTOOD, count = unreadable, total = source.size)
        }
        if (numbers.isEmpty()) {
            _contactSyncOutcome.value = ContactSyncPolicy.Outcome.Matched(0, 0)
            return
        }
        lastContactSyncSubmitted = numbers.size
        if (!client.matchContacts(numbers)) {
            _contactSyncOutcome.value = ContactSyncPolicy.Outcome.Failed
        }
    }

    private suspend fun awaitConnected(client: ThreemaClient): Boolean {
        if (client.connectionState.value is ThreemaConnectionState.Connected) return true
        // Idempotent: returns at once if the supervisor is already running.
        client.start()
        return withTimeoutOrNull(SendRetryPolicy.WAIT_FOR_TRANSPORT_MS) {
            client.connectionState.first { it is ThreemaConnectionState.Connected }
            true
        } ?: false
    }

    private fun listenToThreema(app: Context, client: ThreemaClient) {
        scope.launch {
            client.connectionState.collect { state ->
                _threemaConnectionState.value = state
                // Only an outright failure. Disconnected is the ordinary state between
                // connections and reporting it would bury every real fault under it. The reason
                // string is deliberately not stored: it is written for a developer, and the log
                // is read by somebody who is not one.
                if (state is ThreemaConnectionState.Failed) {
                    record(app, CaregiverEventKind.MESSENGER_CONNECTION_FAILED)
                }
                // Nothing is matched automatically any more — see [requestContactSync].
            }
        }
        scope.launch {
            client.contactMatches.collect { result ->
                if (result.error != null) {
                    _contactSyncOutcome.value = ContactSyncPolicy.Outcome.Failed
                    // Deliberately not clearing anything: a failed lookup must not make people
                    // she could reach five minutes ago unreachable now. Which is exactly why this
                    // needs reporting — nothing breaks visibly, people simply never become
                    // reachable, and no symptom of that ever appears on her phone.
                    record(app, CaregiverEventKind.CONTACT_SYNC_FAILED)
                    return@collect
                }
                val now = System.currentTimeMillis()
                val rows = result.matches.mapNotNull { match ->
                    val number = match.phoneNumber ?: return@mapNotNull null
                    ThreemaMatchEntity(phoneNumber = number, identity = match.identity, matchedAt = now)
                }
                if (rows.isNotEmpty()) AppGraph.database(app).threemaMatchDao().upsertMatches(rows)
                _contactSyncOutcome.value = ContactSyncPolicy.Outcome.Matched(
                    onThreema = result.matches.size,
                    submitted = lastContactSyncSubmitted
                )
                Log.i(TAG, "Threema contact match stored: ${rows.size} of ${result.matches.size}")
            }
        }
    }

    /**
     * Tells the Threema client about everybody it may hear from, whenever it connects and whenever
     * that list grows.
     *
     * Without this, group messages from members the client does not hold in memory are rejected
     * by libthreema on a cold start until somebody is seeded or replied to. It used to run only
     * from the home screen, so a phone restarted with the screen off dropped them.
     */
    private fun keepThreemaContactsSeeded(app: Context, client: ThreemaClient) {
        val groups = AppGraph.threemaGroups(app)
        val db = AppGraph.database(app)
        scope.launch {
            combine(
                client.connectionState.map { it is ThreemaConnectionState.Connected }.distinctUntilChanged(),
                combine(groups.groups, groups.members) { rows, members ->
                    rows.map { it.creatorIdentity } + members.map { it.identity }
                },
                db.threemaContactDao().getAllContactsFlow().map { rows -> rows.map { it.identity } },
                db.threemaMatchDao().getAllMatchesFlow().map { rows -> rows.map { it.identity } },
                combine(
                    AppGraph.partners(app).partners,
                    ConversationLinksSource.contactsWithIdentities
                ) { partners, contacts ->
                    partners.filter { it.transport == BridgeTransport.THREEMA }.map { it.id } +
                        contacts.mapNotNull { it.threemaId }
                }
            ) { connected, fromGroups, imported, matched, known ->
                if (connected) ThreemaContactSeed.identities(fromGroups + imported + matched + known) else emptyList()
            }
                .distinctUntilChanged()
                .collect { identities ->
                    if (identities.isNotEmpty()) client.addContacts(identities)
                }
        }
    }

    private fun listenToWhatsApp(app: Context, client: WhatsAppClient) {
        scope.launch {
            client.linkState.collect { _whatsAppLinkState.value = it }
        }
        scope.launch {
            client.events.collect { event -> onWhatsAppEvent(app, event) }
        }
        scope.launch {
            client.groups.collect { groups ->
                val identities = groups.values.mapNotNull { group ->
                    GroupIdentity.of(WhatsAppMessageMapper.GROUP_CREATOR, group.jid)?.let { it to group }
                }
                _whatsAppGroups.value = identities.mapNotNull { (identity, group) ->
                    val name = WhatsAppGroups.displayName(group.name, group.participants) { count ->
                        app.getString(R.string.group_several_people, count)
                    }?.takeIf { it.isNotBlank() }
                    if (name != null) identity to name else null
                }.toMap()
                // Separate from the names, because a group whose name nobody has set still has
                // people in it — and the faces are how she tells it apart from the rest.
                _whatsAppGroupMembers.value = identities
                    .mapNotNull { (identity, group) ->
                        group.members.takeIf { it.isNotEmpty() }?.let { identity to it }
                    }
                    .toMap()
            }
        }
    }

    private fun onWhatsAppEvent(app: Context, event: WhatsAppEvent) {
        when (event) {
            is WhatsAppEvent.PairingCode -> _whatsAppPairingCode.value = event.code

            // Linked. The code has been spent and must not sit on a screen afterwards.
            is WhatsAppEvent.Paired -> {
                _whatsAppPairingCode.value = null
                _whatsAppError.value = null
            }

            // The code went stale before anybody managed to use it. Taken off the screen, because
            // a code that cannot work is worse than no code: somebody will type it.
            is WhatsAppEvent.PairingExpired -> {
                _whatsAppPairingCode.value = null
                _whatsAppError.value = "The code expired before it was used. Request a new one."
            }

            // The session is gone and cannot come back on its own. The one WhatsApp state worth
            // interrupting a caregiver for — unless a caregiver is the one who ended it: pressing
            // Unlink is somebody at the phone doing exactly what they meant to do.
            is WhatsAppEvent.LoggedOut -> {
                _whatsAppPairingCode.value = null
                if (event.reason != WhatsAppBridgeProtocol.REASON_UNLINKED) {
                    record(app, CaregiverEventKind.MESSENGER_CONNECTION_FAILED)
                }
            }

            // Ordinary and constant. Reporting it would bury every real fault underneath it —
            // the same rule the Threema connection state follows.
            is WhatsAppEvent.Disconnected -> Unit

            // A profile picture arrived; whatever draws faces is told. See [avatarArrivals].
            is WhatsAppEvent.Avatar -> _avatarArrivals.tryEmit(event.jid)

            // Every failure is shown to whoever is holding the dialog. Only the ones that mean
            // nothing can arrive go in the caregiver log, which travels and must stay readable.
            is WhatsAppEvent.Failure -> {
                _whatsAppError.value = event.stage + ": " + event.message
                if (event.stage == "connect" || event.stage == "store") {
                    record(app, CaregiverEventKind.MESSENGER_CONNECTION_FAILED)
                }
            }

            else -> Unit
        }
    }

    /**
     * Writes a fault down for the caregiver. Nothing waits for this and nothing fails if it fails:
     * a fault report that could itself break what it is reporting on would be worse than none.
     */
    private fun record(app: Context, kind: CaregiverEventKind, count: Int = 0, total: Int = 0) {
        scope.launch {
            runCatching {
                AppGraph.caregiverLog(app).record(
                    CaregiverEvent(kind = kind, at = System.currentTimeMillis(), count = count, total = total)
                )
            }.onFailure { Log.w(TAG, "Could not record $kind", it) }
        }
    }
}

/** Which Threema identities to tell the client about. Pure, so the rule can be tested. */
object ThreemaContactSeed {

    /** Every well-formed identity once, upper case: an identity is eight characters, no more. */
    fun identities(raw: Collection<String?>): List<String> =
        raw.mapNotNull { it?.trim()?.uppercase() }
            .filter { it.length == IDENTITY_LENGTH }
            .distinct()

    private const val IDENTITY_LENGTH = 8
}
