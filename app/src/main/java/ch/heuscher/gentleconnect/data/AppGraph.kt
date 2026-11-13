package ch.heuscher.gentleconnect.data

import android.content.Context
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.reply.SilentBackgroundMessageReplier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The one copy of everything that holds state and has to be shared across the process.
 *
 * Before this, the home screen and [ArrivalPipeline] each built their own repositories. That was
 * harmless for the ones that only wrap a database table, and a bug for every one that remembers
 * something in memory: a conversation muted on the screen was still announced by the pipeline,
 * because the pipeline's copy had read the muted list once, at startup. Whatever a part of the app
 * needs from this list, it asks here, and gets the same instance as everybody else.
 *
 * Built the way [Messengers] and [AppStores] are — lazily, on first use, from whatever context the
 * caller has — because the first caller can be a broadcast receiver, the messenger service or the
 * home screen, and none of them can be relied on to run first.
 */
object AppGraph {

    private var conversations: ConversationRepository? = null
    private var threemaGroups: ThreemaGroupRepository? = null
    private var caregiverLog: CaregiverLogRepository? = null
    private var muted: MutedConversationsRepository? = null
    private var nudgeMemory: NudgeMemory? = null
    private var seenPictures: SeenPictures? = null
    private var stepCounter: StepCounter? = null
    private var bridgeMessages: BridgeMessageRepository? = null
    private var partners: PartnerRepository? = null
    private var contacts: ContactsRepository? = null
    private var callLog: CallLogRepository? = null
    private var calendar: CalendarRepository? = null
    private var catalog: MessageCatalog? = null
    private var caregiverPublisher: CaregiverPublisher? = null
    private var outbox: Outbox? = null

    /** Where the process-wide flows live: as long as the process, never a screen. */
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun database(context: Context): AppDatabase = AppDatabase.getInstance(context.applicationContext)

    @Synchronized
    fun conversations(context: Context): ConversationRepository =
        conversations ?: ConversationRepository(database(context).messageDao()).also { conversations = it }

    @Synchronized
    fun threemaGroups(context: Context): ThreemaGroupRepository =
        threemaGroups ?: ThreemaGroupRepository(database(context).threemaGroupDao()).also { threemaGroups = it }

    @Synchronized
    fun caregiverLog(context: Context): CaregiverLogRepository =
        caregiverLog ?: CaregiverLogRepository(database(context).caregiverEventDao()).also { caregiverLog = it }

    @Synchronized
    fun muted(context: Context): MutedConversationsRepository =
        muted ?: MutedConversationsRepository(context.applicationContext).also { muted = it }

    @Synchronized
    fun nudgeMemory(context: Context): NudgeMemory =
        nudgeMemory ?: NudgeMemory(context.applicationContext).also { nudgeMemory = it }

    @Synchronized
    fun seenPictures(context: Context): SeenPictures =
        seenPictures ?: SeenPictures(context.applicationContext).also { seenPictures = it }

    @Synchronized
    fun stepCounter(context: Context): StepCounter =
        stepCounter ?: StepCounter(context.applicationContext).also { stepCounter = it }

    /** One Firestore listener for the process, rather than one per screen that asked. */
    @Synchronized
    fun bridgeMessages(context: Context): BridgeMessageRepository =
        bridgeMessages ?: BridgeMessageRepository(context.applicationContext).also { bridgeMessages = it }

    /** One Firestore listener for the process, rather than one per screen that asked. */
    @Synchronized
    fun partners(context: Context): PartnerRepository =
        partners ?: PartnerRepository(context.applicationContext).also { partners = it }

    @Synchronized
    fun contacts(context: Context): ContactsRepository =
        contacts ?: ContactsRepository(context.applicationContext).also { contacts = it }

    @Synchronized
    fun callLog(context: Context): CallLogRepository =
        callLog ?: CallLogRepository(context.applicationContext).also { callLog = it }

    @Synchronized
    fun calendar(context: Context): CalendarRepository =
        calendar ?: CalendarRepository(context.applicationContext).also { calendar = it }

    /**
     * The messages and people, named, shared by the home screen, the arrival pipeline and the
     * caregiver website. Attached on first use and shared eagerly, because the pipeline needs it
     * with the screen off.
     */
    @Synchronized
    fun catalog(context: Context): MessageCatalog =
        catalog ?: MessageCatalog(
            scope = processScope,
            messageStore = AppStores.messages,
            remoteSettingsRepository = Messengers.settings(context)
        ).also {
            catalog = it
            it.attach(context)
        }

    /**
     * Writes a fault down for the caregiver, without anybody waiting for it. A fault report that
     * could itself fail the thing it reports on would be worse than none.
     */
    fun recordFault(context: Context, event: ch.heuscher.gentleconnect.model.CaregiverEvent) {
        val app = context.applicationContext
        processScope.launch { runCatching { caregiverLog(app).record(event) } }
    }

    /** Keeps the caregiver website in step. Call [CaregiverPublisher.start] to set it going. */
    @Synchronized
    fun caregiverPublisher(context: Context): CaregiverPublisher =
        caregiverPublisher ?: CaregiverPublisher(
            scope = processScope,
            catalog = catalog(context),
            settingsRepository = Messengers.settings(context),
            caregiverEvents = caregiverLog(context).events
        ).also { caregiverPublisher = it }

    /**
     * Where everything she sends goes. Sends run in the process scope, so a message on its way
     * finishes, and its row gets its true state, even when the screen that sent it has gone.
     */
    @Synchronized
    fun outbox(context: Context): Outbox {
        outbox?.let { return it }
        val app = context.applicationContext
        val replier = SilentBackgroundMessageReplier(app)
        return Outbox(
            scope = processScope,
            messageStore = AppStores.messages,
            catalog = catalog(app),
            dependencies = Outbox.Dependencies(
                conversations = { conversations(app) },
                bridgeMessages = { bridgeMessages(app) },
                threema = { Messengers.threema(app) },
                whatsApp = { Messengers.whatsApp(app) },
                replier = { replier },
                context = { app },
                recordFault = { event ->
                    processScope.launch { runCatching { caregiverLog(app).record(event) } }
                }
            )
        ).also { outbox = it }
    }
}
