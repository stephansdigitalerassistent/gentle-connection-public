package ch.heuscher.gentleconnect.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.model.LookFeed
import ch.heuscher.gentleconnect.model.LookItem
import ch.heuscher.gentleconnect.data.SeenPictures
import ch.heuscher.gentleconnect.model.PictureSeen

import ch.heuscher.gentleconnect.data.AppGraph
import ch.heuscher.gentleconnect.data.AppStores
import ch.heuscher.gentleconnect.data.Messengers
import ch.heuscher.gentleconnect.data.CallPlacer
import ch.heuscher.gentleconnect.data.WearSyncManager
import ch.heuscher.gentleconnect.data.CallLogRepository
import ch.heuscher.gentleconnect.data.ConversationLinksSource
import ch.heuscher.gentleconnect.data.NudgeMemory
import ch.heuscher.gentleconnect.data.GroupWording
import ch.heuscher.gentleconnect.data.MessageCatalog
import ch.heuscher.gentleconnect.data.MessageRouter
import ch.heuscher.gentleconnect.data.MessengerRuntime
import ch.heuscher.gentleconnect.data.Outbox
import ch.heuscher.gentleconnect.data.ContactsRepository
import ch.heuscher.gentleconnect.data.ContactDeduplicator
import ch.heuscher.gentleconnect.data.MediaFingerprint
import ch.heuscher.gentleconnect.data.ConversationLinks
import ch.heuscher.gentleconnect.data.Forwarding
import ch.heuscher.gentleconnect.data.GroupReplyPolicy
import ch.heuscher.gentleconnect.data.LocalMessageStore
import ch.heuscher.gentleconnect.data.MailRepository
import ch.heuscher.gentleconnect.data.PartnerRepository
import ch.heuscher.gentleconnect.data.RemoteSettingsRepository
import ch.heuscher.gentleconnect.data.SettingsRepository
import ch.heuscher.gentleconnect.data.StepCounter
import ch.heuscher.gentleconnect.data.ThreemaContactMatcher
import ch.heuscher.gentleconnect.data.db.ThreemaContactEntity
import ch.heuscher.gentleconnect.data.db.ThreemaGroupEntity
import ch.heuscher.gentleconnect.data.db.ThreemaGroupMemberEntity
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppGroups
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.CallHistory
import ch.heuscher.gentleconnect.model.CallRecord
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.MobileNumber
import ch.heuscher.gentleconnect.model.NotYetWritten
import ch.heuscher.gentleconnect.model.PhoneBook
import ch.heuscher.gentleconnect.model.GroupFace
import ch.heuscher.gentleconnect.model.GroupFaces
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.GroupPicture
import ch.heuscher.gentleconnect.model.GroupVisibility
import ch.heuscher.gentleconnect.model.Home
import ch.heuscher.gentleconnect.model.Mail
import ch.heuscher.gentleconnect.model.MailPresentation
import ch.heuscher.gentleconnect.model.ContactNames
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.MessagePortrait
import ch.heuscher.gentleconnect.model.arrivedAt
import ch.heuscher.gentleconnect.model.MissedCallNotice
import ch.heuscher.gentleconnect.model.PersonName
import ch.heuscher.gentleconnect.model.Nudge
import ch.heuscher.gentleconnect.model.Partner
import ch.heuscher.gentleconnect.model.PhotoCandidate
import ch.heuscher.gentleconnect.model.PhotoEvent
import ch.heuscher.gentleconnect.model.PhotoRecipientRanking
import ch.heuscher.gentleconnect.model.PhotoTarget
import ch.heuscher.gentleconnect.model.Reactions
import ch.heuscher.gentleconnect.model.Recipient
import ch.heuscher.gentleconnect.model.RemoteSettings
import ch.heuscher.gentleconnect.model.StatusBroadcast
import ch.heuscher.gentleconnect.service.MailSyncWorker
import ch.heuscher.gentleconnect.service.MessengerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything the several-people pictures are built from, gathered so the combine fits.
 *
 * [kotlinx.coroutines.flow.combine] takes five flows and there are five of them, which is the
 * whole reason this exists rather than a `Triple`.
 */
private data class GroupSources(
    val threemaGroups: List<ThreemaGroupEntity>,
    val threemaMembers: List<ThreemaGroupMemberEntity>,
    val whatsAppGroups: Set<GroupIdentity>,
    val whatsAppMembers: Map<GroupIdentity, List<String>>
)

/**
 * The home screen: the card at the top, her favourites, the short lists of messages, letters and
 * pictures, the missed calls, and calling somebody from a face.
 *
 * What used to be in here and is not any more has a home of its own: the messages and people come
 * from [MessageCatalog], sending goes through [Outbox], the messengers belong to [MessengerRuntime],
 * and the other screens have their own view models — see [GentleViewModelFactory].
 */
class HomeViewModel(
    val messageStore: LocalMessageStore = AppStores.messages,
    val mailRepository: MailRepository = AppStores.mail,
    val remoteSettingsRepository: SettingsRepository = RemoteSettingsRepository(),
    /**
     * The clock [singleNudge] watches, so a missed-call card can reach its time and go while she
     * is looking at the screen rather than waiting for something else to recompute it.
     *
     * A dependency rather than a reach for [currentTimeMillis], because it is a flow that never
     * ends. A unit test drives this view model with `runTest` and `advanceUntilIdle`, which runs
     * virtual time forward until nothing is scheduled — and an endless timer always has something
     * scheduled, so the test does not fail, it hangs, and takes the build with it. A test passes
     * a clock that does not tick; null means the real one.
     */
    private val nudgeClock: Flow<Long>? = null,
    /** The process's [MessageCatalog]; null builds a private one, which is what tests do. */
    messageCatalog: MessageCatalog? = null,
    /** The process's [Outbox]; null builds one with nothing to send through (tests). */
    messageOutbox: Outbox? = null,
    /** Where a call attempt counts towards restlessness. See [Restlessness]; none in tests. */
    private val restlessness: Restlessness? = null,
    /** The voice that says who she is calling. See [Speech]; silent in tests. */
    private val speech: Speech? = null,
    // Everything below is null in tests, and each thing that needs it then simply does nothing.
    /** Her address book: faces, names, and who is starred. */
    private val contactsRepository: ContactsRepository? = null,
    /** The telephone's call log, for the missed-call card and marks. */
    private val callLogRepository: CallLogRepository? = null,
    /** What she has already answered — see [NudgeMemory]. Followed, never read once. */
    private val nudgeMemory: NudgeMemory? = null,
    /** The partners the old bridge knows, for the people she can write to first. */
    private val partnerRepository: PartnerRepository? = null,
    /** Which pictures she has already looked at. */
    private val seenPicturesStore: SeenPictures? = null,
    /** Her favourites, put on her wrist. */
    private val wearSyncManager: WearSyncManager? = null,
    /** Where the WhatsApp bridge keeps the profile pictures it fetched. */
    private val whatsAppAvatars: File? = null
) : ViewModel() {

    /**
     * The messages and the people they are from, named — see [MessageCatalog]. In the app this is
     * the process's own, attached and shared with the arrival pipeline and the caregiver website.
     * A view model built without one gets a private catalog with nothing attached, shared only
     * while a screen collects it, exactly as these flows behaved when they lived here.
     */
    private val catalog: MessageCatalog = messageCatalog ?: MessageCatalog(
        scope = viewModelScope,
        messageStore = messageStore,
        remoteSettingsRepository = remoteSettingsRepository,
        sharing = SharingStarted.WhileSubscribed(5000)
    )

    /**
     * Where everything she sends goes — see [Outbox]. In the app, the process's own. Without one,
     * an outbox over this view model's fields, which in tests are all empty.
     */
    private val outbox: Outbox = messageOutbox ?: Outbox(
        scope = viewModelScope,
        messageStore = messageStore,
        catalog = catalog,
        dependencies = Outbox.Dependencies()
    )

    /** The groups she is in, by address, as the bridge reports them. */
    private val whatsAppGroups: StateFlow<Map<GroupIdentity, String>> = MessengerRuntime.whatsAppGroups

    /**
     * Who is in each of those groups, by WhatsApp address. Empty for a group the bridge has not
     * described yet, and that emptiness means "we do not know", never "nobody": [GroupFaces.of]
     * falls back to whoever has written, which is what the phone had before.
     */
    private val whatsAppGroupMembers: StateFlow<Map<GroupIdentity, List<String>>> =
        MessengerRuntime.whatsAppGroupMembers

    private val _seenPictureIds = MutableStateFlow<Set<String>>(emptySet())
    val seenPictureIds: StateFlow<Set<String>> = _seenPictureIds

    private val systemContacts: MutableStateFlow<List<Contact>> = catalog.systemContacts
    private var contactsRefreshJob: Job? = null
    private var lastContactsRefreshTime: Long = 0L

    /**
     * Bumped whenever the pictures we can draw people with may have changed on disk.
     *
     * Separate from [systemContacts] because that one answers a different question. It is a
     * StateFlow of a list of data classes, so it stays silent when a rescan finds the address
     * book exactly as it was — which is right for the phone book, and wrong for the faces beside
     * her messages: an avatar arriving from WhatsApp for somebody who is *not* in her address
     * book changes a picture on screen without changing a single contact. Watching only the
     * contacts there would leave that message showing initials until something unrelated moved.
     */
    private val pictureSources = MutableStateFlow(0)
    private val bridgeMessages: MutableStateFlow<List<Message>> = catalog.bridgeMessages

    /**
     * Messages the embedded Threema client received, newest last.
     *
     * Fed from the conversation store once there is one, so these now survive a restart; before
     * the database is attached — and in tests, which construct this ViewModel with nothing — it
     * falls back to holding them in memory, which is what the whole app used to do.
     */
    private val onDeviceMessages: MutableStateFlow<List<Message>> = catalog.onDeviceMessages

    /** Everything she has sent, and everything the Threema client received, from the store. */
    private val storedMessages: MutableStateFlow<List<Message>> = catalog.storedMessages

    private val threemaGroups: MutableStateFlow<List<ThreemaGroupEntity>> = catalog.threemaGroups
    private val threemaGroupMembers: MutableStateFlow<List<ThreemaGroupMemberEntity>> = catalog.threemaGroupMembers

    /**
     * Her own Threema identity, so that she can be left out of the list of people a conversation
     * is described by. Read once at startup; null before then, which only costs her own name
     * appearing in a description for a moment.
     */
    private val ownThreemaId: String?
        get() = catalog.ownThreemaId.value

    /**
     * The two words needed to name several people in her language.
     *
     * A flow rather than a field so the descriptions are rebuilt if this is read again — the
     * ViewModel outlives an Activity, and an Activity is what knows which strings Android resolved.
     */
    private val groupWording: MutableStateFlow<GroupWording> = catalog.groupWording
    private val rawPartners = MutableStateFlow<List<Partner>>(emptyList())
    private val importedContacts: MutableStateFlow<List<ThreemaContactEntity>> = catalog.importedContacts

    /** Normalised phone number to Threema identity, as resolved by the directory server. */
    private val matchedIdentities: MutableStateFlow<Map<String, String>> = catalog.matchedIdentities

    // Clock state updating every minute
    val currentTimeMillis: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000L)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())

    val remoteSettings: StateFlow<RemoteSettings> = remoteSettingsRepository.settings

    /** Her SMS inbox and both messengers, newest first, deduped. See [MessageCatalog.messages]. */
    val messages: StateFlow<List<Message>> = catalog.messages

    /** Her Post screen's letters: everything, or only people she knows — see [MailPresentation.forPostScreen]. */
    val mails: StateFlow<List<Mail>> = combine(mailRepository.mails, remoteSettings) { all, settings ->
        MailPresentation.forPostScreen(all, settings.mailOnlyKnownSenders)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        MailPresentation.forPostScreen(
            mailRepository.mails.value,
            remoteSettings.value.mailOnlyKnownSenders
        )
    )

    /** The faces on her home screen, resolved against her address book. See [MessageCatalog]. */
    val favoriteContacts: StateFlow<List<Contact>> = catalog.favoriteContacts

    /** Her whole address book, for the phone-book screen, ordered by name. See [MessageCatalog]. */
    val allContacts: StateFlow<List<Contact>> = catalog.allContacts

    private fun normalizedNumberOf(contact: Contact): String? =
        ConversationLinksSource.normalizedNumberOf(contact)

    private fun normalizedNumber(raw: String?): String? =
        ConversationLinksSource.normalizedNumber(raw)

    /**
     * Which addresses may be treated as the same person.
     *
     * Built from the directory matches only, never from [ThreemaContactMatcher]'s name guesses —
     * see [ConversationLinks] for why a wrong merge is the one kind of mistake she cannot detect.
     */
    private val conversationLinks: StateFlow<ConversationLinks> = catalog.conversationLinks

    /**
     * Which conversation a message belongs to, or null when there is nothing to file it under.
     *
     * Derived every time rather than trusting what the store recorded, and the stored id is only
     * the fallback. A Threema message that arrived before contact sync had run was filed under a
     * bare identity; once the directory answers, the same person is filed under her contact — and
     * re-deriving means the earlier messages move to join the later ones rather than the thread
     * quietly splitting in two, which is the sort of thing she would experience as her daughter's
     * messages having gone missing.
     */
    private fun conversationIdOf(message: Message, links: ConversationLinks): String? =
        ConversationLinksSource.conversationIdOf(message, links)

    private fun conversationIdOf(recipient: Recipient, links: ConversationLinks): String? =
        ConversationLinksSource.conversationIdOf(recipient, links)

    // The names and descriptions her screen uses — all of it from [MessageCatalog], so that the
    // arrival pipeline and the caregiver website name people exactly the way this screen does.
    private val nameByIdentity: StateFlow<Map<String, String>> = catalog.nameByIdentity
    private val contactNames: StateFlow<ContactNames> = catalog.contactNames
    private val groupDescriptions: StateFlow<Map<GroupIdentity, String>> = catalog.groupDescriptions
    private val groupReplyDecisions: StateFlow<Map<GroupIdentity, GroupReplyPolicy.Decision>> =
        catalog.groupReplyDecisions
    private val namedMessages: StateFlow<List<Message>> = catalog.namedMessages
    private val namedStoredMessages: StateFlow<List<Message>> = catalog.namedStoredMessages

    /** Whether these several people may be answered right now. See [MessageCatalog.replyDecisionFor]. */
    private fun replyDecisionFor(group: GroupIdentity): GroupReplyPolicy.Decision =
        catalog.replyDecisionFor(group)

    // Resolved raw partners enriched with imported contact display names and photos
    private val resolvedPartners: StateFlow<List<Partner>> = combine(
        rawPartners,
        importedContacts
    ) { partners, contacts ->
        if (contacts.isEmpty()) return@combine partners
        val contactMap = contacts.associateBy { it.identity.trim().uppercase() }
        partners.map { partner ->
            val cleanId = partner.id.trim().uppercase()
            val match = contactMap[cleanId]
            if (match != null) {
                val name = if (PersonName.isUnresolved(partner.displayName, partner.id)) {
                    match.displayName
                } else {
                    partner.displayName
                }
                val photo = partner.photoUrl ?: match.photoPath
                partner.copy(displayName = name, photoUrl = photo)
            } else {
                partner
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Partners she can initiate conversations with (excluding partners already in favorite contacts)
    val initiatingPartners: StateFlow<List<Partner>> = combine(
        resolvedPartners,
        favoriteContacts
    ) { partners, favorites ->
        val favThreemaIds = favorites.mapNotNull { it.threemaId?.trim()?.uppercase() }.toSet()
        val favPhoneNumbers = favorites.map { cleanPhoneNumber(it.phoneNumber) }.filter { it.isNotBlank() }.toSet()
        val favNames = favorites.map { it.name.trim().lowercase() }.toSet()

        partners.filter { partner ->
            val tId = partner.id.trim().uppercase()
            val pNum = cleanPhoneNumber(partner.phoneNumber ?: "")
            val pName = partner.displayName.trim().lowercase()

            !favThreemaIds.contains(tId) &&
                    (pNum.isBlank() || !favPhoneNumbers.contains(pNum)) &&
                    !favNames.contains(pName)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Everyone she can write to first, with every route to them merged onto one person.
     *
     * Favourites and partners overlap constantly — the same woman is a starred contact with a
     * telephone number *and* a Threema partner. Listed separately she would appear twice in the
     * picker, and picking the wrong one would silently cost money or silently fail. Merged on
     * name, so she sees one Claire-Lise and the routing is left to [MessageRouter].
     */
    val writableRecipients: StateFlow<List<Recipient>> = combine(
        favoriteContacts,
        resolvedPartners,
        groupDescriptions,
        groupReplyDecisions,
        remoteSettings
    ) { favorites, partners, groupNames, groupDecisions, settings ->
        val byName = LinkedHashMap<String, Recipient>()

        favorites.forEach { contact ->
            val key = contact.name.trim().lowercase()
            if (key.isBlank()) return@forEach
            byName[key] = Recipient(
                name = contact.name,
                photoUri = contact.photoUri,
                threemaId = contact.threemaId,
                phoneNumber = contact.phoneNumber
            )
        }

        partners.filter { it.canInitiate }.forEach { partner ->
            val key = partner.displayName.trim().lowercase()
            if (key.isBlank()) return@forEach
            val existing = byName[key]
            val threemaId = partner.id.takeIf { partner.transport == BridgeTransport.THREEMA }
            val whatsappId = partner.id.takeIf { partner.transport == BridgeTransport.WHATSAPP }
            byName[key] = Recipient(
                name = existing?.name ?: partner.displayName,
                photoUri = existing?.photoUri ?: partner.photoUrl,
                // Never overwrite a route that is already known — a partner document says
                // nothing about the telephone number in her address book.
                threemaId = existing?.threemaId?.ifBlank { null } ?: threemaId,
                whatsappId = existing?.whatsappId?.ifBlank { null } ?: whatsappId,
                phoneNumber = existing?.phoneNumber?.ifBlank { null } ?: partner.phoneNumber
            )
        }

        // The several people, on the same list as the one person. She thinks of the family group
        // as somebody she writes to, and nothing on this list says which kind of thing a row is.
        //
        // Only groups that can actually be reached: a group whose membership we were never told
        // is absent here for the same reason its reply field is absent — the alternative is a
        // name she can pick, write to, and never hear about again.
        val hiddenGroups = GroupVisibility.hiddenKeys(settings.hiddenGroups)
        groupDecisions.forEach { (group, decision) ->
            if (decision !is GroupReplyPolicy.Decision.SendTo) return@forEach
            // A group she is not shown is not one she can be offered to write to. Its name in the
            // picker would open a conversation with nothing in it.
            if (GroupVisibility.keyOf(group) in hiddenGroups) return@forEach
            val name = groupNames[group]?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            val key = name.lowercase()
            if (byName.containsKey(key)) return@forEach
            byName[key] = Recipient(name = name, groupIdentity = group)
        }

        // Someone with no route is not offered. Presenting a name she cannot reach and then
        // failing after she has written the message is worse than not listing her.
        byName.values.filter { it.isReachable }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Everything she has, with repeats folded away — the one list both screens, the home card and
     * the arrival announcements read from. See [MessageCatalog.visibleMessages].
     */
    val visibleMessages: StateFlow<List<Message>> = catalog.visibleMessages

    /**
     * Everybody she could write to and has no conversation with yet, in the phone book's order —
     * the tail of the message list. See [NotYetWritten].
     *
     * Keyed exactly the way messages are filed, so a person counts as having a conversation when
     * their messages would land in one, whichever route they came by.
     */
    val notYetWrittenTo: StateFlow<List<Contact>> = combine(
        allContacts,
        favoriteContacts,
        visibleMessages,
        conversationLinks
    ) { contacts, favourites, messages, links ->
        val conversations = messages.mapNotNullTo(HashSet()) { conversationIdOf(it, links) }
        NotYetWritten.from(
            inOrder = PhoneBook.forHer(contacts, favourites, "").all,
            conversations = conversations,
            keyOf = { contact ->
                conversationIdOf(
                    Recipient(name = contact.name, threemaId = contact.threemaId, phoneNumber = contact.phoneNumber),
                    links
                )
            },
            // Threema, or a mobile number — not merely any route: the owner's rule, and what keeps
            // a phone book of landlines and businesses off the end of her messages. See
            // [MobileNumber].
            reachable = {
                (!it.threemaId.isNullOrBlank() || MobileNumber.isMobile(normalizedNumberOf(it))) &&
                    MessageRouter.canReach(it.threemaId, null, it.phoneNumber)
            }
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The pictures people have posted, newest first. See [MessageCatalog.statusPosts]. */
    val statusPosts: StateFlow<List<Message>> = catalog.statusPosts

    fun markPictureSeen(messageId: String) = markPicturesSeen(listOf(messageId))

    /**
     * Every picture of an album at once, which is what closing the viewer means.
     *
     * One write rather than thirty-five: each of these both rewrites the set and touches the disk,
     * and closing an album of Martin Nauer's would otherwise do it once per photograph.
     */
    fun markPicturesSeen(messageIds: List<String>) {
        val fresh = messageIds.filter { it !in _seenPictureIds.value }
        if (fresh.isEmpty()) return

        val updated = PictureSeen.pruned(
            seen = _seenPictureIds.value,
            added = fresh,
            present = statusPosts.value.map { it.id }.toSet()
        )
        _seenPictureIds.value = updated
        viewModelScope.launch(Dispatchers.IO) {
            seenPicturesStore?.save(updated)
        }
    }

    /**
     * Leaving the View screen counts as having seen everything on it.
     *
     * Purely local: pictures are remembered only in local preferences (never reporting seen back
     * to WhatsApp or Threema), and mail read state is stored only in the local database (never
     * written back to an IMAP server). Once she has walked away from her post, the amber frames
     * and bold letters are gone so that the next time she looks, only things that arrived since
     * will ask for her attention.
     */
    fun markLookFeedAsRead() {
        val pictureIds = statusPosts.value.map { it.id }
        markPicturesSeen(pictureIds)
        mailRepository.markAllAsRead()
    }

    /**
     * The faces on her home screen: the first five favourites, and no more.
     *
     * Separate from [favoriteContacts] rather than a cap on it, because that flow is also what
     * the phone book falls back to when her address book cannot be read at all, and what
     * [writableRecipients] is built from. Cutting it there would quietly take three people out of
     * the list of everybody she can write to — which is the opposite of what this is for.
     */
    val homeFavourites: StateFlow<List<Contact>> = favoriteContacts
        .map { contacts -> Home.favourites(contacts) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The short list on her home screen — people only, and only a few. See [Home.messages].
     *
     * The Message tab still reads [visibleMessages] and still shows everything.
     */
    val unreadMessages: StateFlow<List<Message>> = visibleMessages
        .map { distinctList -> Home.messages(distinctList) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The face on each message row, by [Message.id] — see [MessagePortrait].
     *
     * Read by both screens that list messages, so the same message cannot arrive wearing a
     * photograph on one of them and initials on the other.
     *
     * Built off [visibleMessages] rather than off the short home list, because the Message tab
     * shows everything the home screen shows and more; one map serves both, and resolving it
     * twice would ask her address book the same questions twice over.
     *
     * The whole resolution runs on [Dispatchers.IO]. Each entry is potentially a query against
     * ContactsContract, and [photoResolvers] is what keeps a list of twenty messages from twenty
     * people to twenty queries rather than twenty times the rows on the screen.
     */
    val messagePhotos: StateFlow<Map<String, String>> = combine(
        visibleMessages,
        resolvedPartners,
        // Her whole address book rather than the five faces on her home screen. The favourites
        // are a shortlist somebody else chose, and they are not where the Threema identities are:
        // a favourite is resolved from stored settings that carry a name and a picture, while the
        // identity that a message is filed under is attached to the contact her *address book*
        // holds. Asking the shortlist put initials on her father's conversation while his
        // photograph sat in the phone book two screens away.
        allContacts,
        pictureSources,
        combine(threemaGroups, whatsAppGroups) { threema, whatsApp -> threema to whatsApp.keys }
    ) { messages, partners, favourites, _, (groups, whatsAppGroupIds) ->
        withContext(Dispatchers.IO) {
            val (emailResolver, phoneResolver) = photoResolvers()
            val (byPartnerId, byName) = portraitIndex(favourites, partners)
            val byGroup = groupPhotos(groups, whatsAppGroupIds)

            MessagePortrait.photosFor(
                messages = messages,
                photoByPartnerId = byPartnerId,
                photoByName = byName,
                photoByGroup = byGroup,
                byPhone = phoneResolver,
                byEmail = emailResolver
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Every several-people conversation as it is drawn: its own picture and the people in it,
     * whoever wrote last in front — see [GroupFaces].
     *
     * Read by the message rows (a square of stacked faces instead of one round one), by the top
     * of an open group conversation, by the face beside each run of bubbles, and by the faces the
     * reply is addressed to. One map for all four, so the same person cannot wear a photograph in
     * one place and initials in the next.
     *
     * Built from every message rather than the one-per-conversation list, because who wrote last
     * and who has written at all are questions about the whole conversation. The expensive part
     * — asking her address book for a face — is done once per person, not once per message.
     */
    val groupPictures: StateFlow<Map<GroupIdentity, GroupPicture>> = combine(
        combine(namedMessages, namedStoredMessages) { arrived, stored -> arrived + stored },
        // A WhatsApp group's picture is a file the bridge drops in later, so its arrival
        // (pictureSources) has to redraw the groups as well as the people.
        combine(
            threemaGroups,
            threemaGroupMembers,
            whatsAppGroups,
            whatsAppGroupMembers,
            pictureSources
        ) { groups, members, whatsApp, whatsAppMembers, _ ->
            GroupSources(groups, members, whatsApp.keys, whatsAppMembers)
        },
        combine(nameByIdentity, contactNames) { names, contactNames -> names to contactNames },
        resolvedPartners,
        allContacts
    ) { messages, sources, (names, contactNames), partners, contacts ->
        val groups = sources.threemaGroups
        val members = sources.threemaMembers
        val whatsAppGroupIds = sources.whatsAppGroups
        withContext(Dispatchers.IO) {
            val (emailResolver, phoneResolver) = photoResolvers()
            val (byPartnerId, byName) = portraitIndex(contacts, partners)
            val byGroup = groupPhotos(groups, whatsAppGroupIds)
            val own = ownThreemaId

            fun photoOf(address: String?, id: String?, name: String) = MessagePortrait.personPhoto(
                address = address?.takeUnless { it.endsWith("@g.us", ignoreCase = true) },
                senderId = id?.takeUnless { it.endsWith("@g.us", ignoreCase = true) },
                name = name,
                photoByPartnerId = byPartnerId,
                photoByName = byName,
                byPhone = phoneResolver,
                byEmail = emailResolver
            )

            // Asked once per person: the same speaker is behind hundreds of messages.
            val speakerFaces = HashMap<String, GroupFace>()
            val faceOf = { message: Message ->
                val key = GroupFaces.speakerKey(message) ?: message.id
                speakerFaces.getOrPut(key) {
                    GroupFace(
                        key = key,
                        name = message.senderName,
                        photoUri = photoOf(message.senderAddress, message.senderId, message.senderName)
                    )
                }
            }

            val membersByGroup = members.groupBy { GroupIdentity(it.creatorIdentity, it.groupId) }
            val groupMessages = messages.filter { it.isGroupMessage }
            val allGroups = groupMessages.mapNotNull { it.groupIdentity }.toSet() +
                membersByGroup.keys +
                sources.whatsAppMembers.keys

            val byGroupMessages = groupMessages.groupBy { it.groupIdentity }
            allGroups.associateWith { group ->
                // The creator is put back and she is taken out: GroupFaces.memberIdentities.
                val identities = GroupFaces.memberIdentities(
                    group = group,
                    members = membersByGroup[group].orEmpty().map { it.identity },
                    own = own
                )
                val threemaFaces = identities.map { id ->
                    val name = contactNames.byIdentity[id] ?: names[id] ?: id
                    GroupFace(key = id, name = name, photoUri = photoOf(null, id, name))
                }
                // A WhatsApp member is a telephone number, so she is looked up in the address
                // book the same way every WhatsApp message already is: by the end of the number.
                // Failing that the number itself, which is what her phone has always shown for a
                // stranger — and failing even that an address, which PersonName turns into
                // "Someone" rather than putting a JID on her screen.
                val whatsAppFaces = WhatsAppGroups.memberAddresses(sources.whatsAppMembers[group])
                    .mapNotNull { address ->
                        val key = WhatsAppGroups.faceKey(address) ?: return@mapNotNull null
                        val number = WhatsAppNumber.numberFromJid(address)
                        val known = number
                            ?.let { WhatsAppNumber.subscriberKey(it) }
                            ?.let { contactNames.byNumber[it] }
                        val name = known ?: number ?: address
                        GroupFace(
                            key = key,
                            name = name,
                            photoUri = photoOf(number?.let { "+$it" }, number, name)
                        )
                    }
                val memberFaces = threemaFaces + whatsAppFaces
                GroupPicture(
                    photoUri = byGroup[group],
                    faces = GroupFaces.of(group, memberFaces, byGroupMessages[group].orEmpty(), faceOf)
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * The photographs she has for people, keyed the two ways [MessagePortrait] looks them up: by
     * messenger address, and by name.
     *
     * Her address book first and partners after, so that a photograph the caregiver chose in her
     * address book is not quietly replaced by whatever avatar a messenger happens to be serving
     * for the same person.
     */
    private fun portraitIndex(
        contacts: List<Contact>,
        partners: List<Partner>
    ): Pair<Map<String, String>, Map<String, String>> {
        val byPartnerId = HashMap<String, String>()
        val byName = HashMap<String, String>()
        contacts.forEach { contact ->
            val photo = contact.photoUri
            if (photo.isNullOrBlank()) return@forEach
            contact.threemaId?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let {
                byPartnerId.putIfAbsent(it, photo)
            }
            contact.name.trim().lowercase().takeIf { it.isNotEmpty() }?.let {
                byName.putIfAbsent(it, photo)
            }
        }
        partners.forEach { partner ->
            val photo = partner.photoUrl
            if (photo.isNullOrBlank()) return@forEach
            partner.id.trim().uppercase().takeIf { it.isNotEmpty() }?.let {
                byPartnerId.putIfAbsent(it, photo)
            }
            partner.displayName.trim().lowercase().takeIf { it.isNotEmpty() }?.let {
                byName.putIfAbsent(it, photo)
            }
        }
        return byPartnerId to byName
    }

    /**
     * A group's own picture, which is the only portrait a group row is allowed to draw as itself.
     *
     * Threema's come with the imported group. WhatsApp's are fetched by the bridge when it reports
     * the group, and stored under the digits of the group's address, the same way it stores a
     * person's under their number. A group nobody gave a picture has no file and keeps the icon.
     */
    private fun groupPhotos(
        groups: List<ThreemaGroupEntity>,
        whatsAppGroupIds: Set<GroupIdentity>
    ): Map<GroupIdentity, String> {
        val threema = groups.mapNotNull { group ->
            val photo = group.photoPath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GroupIdentity(group.creatorIdentity, group.groupId) to photo
        }
        val avatars = whatsAppAvatars
        val whatsApp = if (avatars == null) emptyList() else whatsAppGroupIds.mapNotNull { group ->
            val digits = group.groupId.substringBefore('@').filter { it.isDigit() }
            val file = File(avatars, "$digits.jpg").takeIf { digits.isNotEmpty() && it.length() > 0 }
                ?: return@mapNotNull null
            group to "file://${file.absolutePath}"
        }
        return (whatsApp + threema).toMap()
    }

    /**
     * Whether the Message button carries a mark — somebody has written and she has not seen it.
     *
     * The same question the card at the top of home asks, so the two can never disagree; see
     * [Home.marksTheMessageTab] for why it is people only and why it is never a count. Read off
     * the same list the Message tab itself shows, so the mark and what she finds behind it are
     * about the same messages.
     */
    val messagesMarked: StateFlow<Boolean> = visibleMessages
        .map { messages -> Home.marksTheMessageTab(messages) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * The letters on her home screen — from people she knows, unopened first, and only a few.
     *
     * The Post tab reads [mails] and shows everything, strangers included. Here the home screen
     * has room for a handful and picks unread first, so one morning of circulars would take
     * every slot and push a letter from her son off the screen she actually stands in front of.
     * See [MailPresentation.forHomeScreen].
     */
    val homeMails: StateFlow<List<Mail>> = mails
        .map { all -> Home.mails(MailPresentation.forHomeScreen(all)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The pictures on her home screen — newest first, and only a few. See [Home.statuses]. */
    val homeStatuses: StateFlow<List<Message>> = statusPosts
        .map { all -> Home.statuses(all) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The two ways a face is looked up while a feed is being built, each remembering what it has
     * already been asked.
     *
     * "This person has no photograph" has to be remembered exactly as firmly as a photograph is.
     * It is the common case — a letter from a stranger, a number not in her address book — and
     * `getOrPut` cannot remember it, because it reads a stored null as "never asked" and would go
     * back to the address book for every card on the screen. That is the query this whole
     * arrangement exists to avoid.
     *
     * Fresh per build rather than kept on the view model: a caregiver adding a photograph should
     * show up on her next refresh, not after the app is restarted.
     */
    private fun photoResolvers(): Pair<(String) -> String?, (String) -> String?> {
        val repo = contactsRepository
        val emailCache = mutableMapOf<String, String?>()
        val phoneCache = mutableMapOf<String, String?>()
        val cachedContacts = systemContacts.value

        val byEmail: (String) -> String? = { email ->
            if (emailCache.containsKey(email)) emailCache[email]
            else repo?.findContactByEmail(email)?.photoUri.also { emailCache[email] = it }
        }

        val byPhone: (String) -> String? = { phone ->
            if (phoneCache.containsKey(phone)) {
                phoneCache[phone]
            } else {
                val targetNorm = ContactDeduplicator.normalizeNumber(phone)
                val inMemoryMatch = if (targetNorm.isNotBlank()) {
                    cachedContacts.firstOrNull {
                        val sysNorm = ContactDeduplicator.normalizeNumber(it.phoneNumber)
                        sysNorm == targetNorm || (targetNorm.length >= 7 && sysNorm.endsWith(targetNorm.takeLast(7)))
                    }
                } else null

                val photo = if (inMemoryMatch != null && inMemoryMatch.photoUri != null) {
                    inMemoryMatch.photoUri
                } else {
                    repo?.findContactByPhoneNumber(phone)?.photoUri
                }
                photo.also { phoneCache[phone] = it }
            }
        }

        return byEmail to byPhone
    }

    /**
     * Everything that has arrived for her to look at, with contact photos pre-resolved off the UI thread.
     */
    val lookFeed: StateFlow<List<LookItem>> = combine(
        mails,
        statusPosts,
        pictureSources
    ) { mailList, statuses, _ ->
        withContext(Dispatchers.IO) {
            val (emailResolver, phoneResolver) = photoResolvers()

            LookFeed.build(
                mails = mailList,
                statuses = statuses,
                emailPhotoResolver = emailResolver,
                phonePhotoResolver = phoneResolver,
                mediaKeyOf = MediaFingerprint::keyOf
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The items for the Look section on her home screen, newest first, with contact photos pre-resolved.
     */
    val homeLookFeed: StateFlow<List<LookItem>> = combine(
        homeMails,
        // Every picture, not [homeStatuses]. The cut to what fits on home has to happen after the
        // bursts are folded, or it is made against the wrong things: thirty-five pictures posted
        // in nine seconds are one item here, and taking two beforehand would both take the whole
        // section for one man's afternoon and leave the row able to say "2 pictures" about
        // thirty-five. See [StatusBurst].
        statusPosts,
        pictureSources
    ) { mailList, statuses, _ ->
        withContext(Dispatchers.IO) {
            val (emailResolver, phoneResolver) = photoResolvers()

            LookFeed.build(
                mails = mailList,
                statuses = statuses,
                emailPhotoResolver = emailResolver,
                phonePhotoResolver = phoneResolver,
                mediaKeyOf = MediaFingerprint::keyOf
            ).take(Home.LIST_LENGTH)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * How many letters are waiting that she would want to know about.
     *
     * Counts only unread mail from somebody she knows — see [MailPresentation.callsForAttention].
     * Before, everything shown was from somebody she knew, so "unread" and "worth telling her
     * about" were the same number. Now that strangers are shown too they have come apart, and
     * counting both would make this badge read "14" on a quiet week and mean nothing.
     */
    val unreadMailCount: StateFlow<Int> = combine(mails) { mailArray ->
        MailPresentation.attentionCount(mailArray[0])
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Everything she has, read and unread. The home screen needs this so that the way into
    // her mail does not read "no mail" the moment she has opened everything.
    val totalMailCount: StateFlow<Int> = combine(mails) { mailArray ->
        mailArray[0].size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * How far back the missed calls have been dealt with — see [MissedCallNotice].
     *
     * A flow rather than a plain read of [NudgeMemory], so that pressing "Call back" takes the
     * card off the screen at once instead of the next time something else happens to recompute it.
     */
    private val missedCallsDealtWithUpTo = MutableStateFlow(0L)

    /**
     * She has answered the call the card is about, so the card has done its job.
     *
     * The moment recorded is the call's own, not this instant: a call that comes in while she is
     * pressing the button is still news. Nothing here knows *how* she dealt with it — pressing
     * "Call back" is the only route in, because it is the only one the phone can be sure about.
     */
    fun markMissedCallDealtWith(callAt: Long) {
        val moment = MissedCallNotice.dealtWith(callAt, missedCallsDealtWithUpTo.value)
        missedCallsDealtWithUpTo.value = moment
        nudgeMemory?.dealtWithMissedCall(moment)
    }

    /**
     * Follows the memory for as long as the view model lives, because somebody outside it writes
     * there too: "Call back" in the missed-call notice is pressed in a broadcast receiver, and the
     * telephone notices her ringing somebody back — see
     * [ch.heuscher.gentleconnect.telecom.CallActionReceiver]. This used to be a read on resume, so
     * any way back to the home screen that skipped onResume showed a card for a call she had
     * already returned, which is the whole thing [MissedCallNotice] exists to stop.
     *
     * Never backwards: whatever this process has already recorded stands, so a moment read from
     * disk cannot resurrect a card the button in the app has just taken away.
     */
    private fun followMissedCallMemory(memory: NudgeMemory) {
        viewModelScope.launch {
            memory.missedCallsDealtWithUpTo.collect { remembered ->
                missedCallsDealtWithUpTo.value =
                    MissedCallNotice.dealtWith(remembered, missedCallsDealtWithUpTo.value)
            }
        }
    }

    /** The telephone's newest missed calls, read while she is on the calls tab. */
    private val missedCallLog = MutableStateFlow<List<Nudge.MissedCallNudge>>(emptyList())

    /**
     * Every missed call still worth marking on the calls tab — each person who rang and has not
     * been rung back, not only the newest one the card is about. See [MissedCallNotice.stillOpen].
     */
    val openMissedCalls: StateFlow<List<Nudge.MissedCallNudge>> = combine(
        missedCallLog,
        missedCallsDealtWithUpTo,
        remoteSettings,
        nudgeClock ?: currentTimeMillis
    ) { calls, dealtWithUpTo, settings, now ->
        MissedCallNotice.stillOpen(calls, dealtWithUpTo, now, settings.missedCallHours)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Keeps [missedCallLog] true for as long as she is on the calls tab, the way
     * [watchRecentCalls] keeps her list of calls true: a call that rings and is missed while she
     * is looking at the list is marked while she is looking. Suspends until the tab goes away.
     */
    suspend fun watchMissedCalls() {
        val repo = callLogRepository ?: return
        missedCallLog.value = repo.recentMissedCalls()
        repo.changes().collect {
            missedCallLog.value = repo.recentMissedCalls()
        }
    }

    /**
     * She has left the calls tab, where every open missed call was marked, so they are over.
     *
     * Leaving is the gesture because there is no other: she was shown who rang, and whether she
     * rang them back is hers to decide — the card offering the same call again tomorrow would be
     * the phone nagging her about a choice she has already made. Up to the newest call she was
     * shown and no further; see [MissedCallNotice.allSeen].
     */
    fun missedCallsSeen() {
        // Worked out here rather than read off [openMissedCalls], which only runs while a screen
        // is drawing it and may be a minute behind.
        val open = MissedCallNotice.stillOpen(
            missedCallLog.value,
            missedCallsDealtWithUpTo.value,
            System.currentTimeMillis(),
            remoteSettings.value.missedCallHours
        )
        if (open.isEmpty()) return
        val moment = MissedCallNotice.allSeen(open, missedCallsDealtWithUpTo.value)
        missedCallsDealtWithUpTo.value = moment
        nudgeMemory?.dealtWithMissedCall(moment)
    }

    /**
     * The one card at the top of her home screen: the newest thing worth interrupting her for.
     *
     * Read off [visibleMessages], like the list underneath it and like the mark on the Message
     * button. This used to read the unfiltered flow, and a group the caregiver had switched off
     * still got the loudest place on her screen — with nothing behind it, because the list below
     * and the tab mark had both left that message out, and the card's "Read" button opened a
     * conversation she was not being shown. Hiding a group has to mean hiding it everywhere or it
     * does not mean anything; see [GroupVisibility] and [Home.marksTheMessageTab].
     */
    val singleNudge: StateFlow<Nudge?> = combine(
        visibleMessages,
        remoteSettings,
        missedCallsDealtWithUpTo,
        // The clock, so that a card with an hour on it leaves by itself while she is looking at
        // the screen. Without it the card would sit there until something else happened to
        // recompute this, and "it goes after an hour" would be true only for somebody who put the
        // phone down and picked it up again. See [nudgeClock] for why it arrives from outside.
        nudgeClock ?: currentTimeMillis
    ) { msgList, settings, dealtWithUpTo, now ->
        if (!settings.enableNudge) return@combine null

        val missedCallNudge = callLogRepository?.let { repo ->
            withContext(Dispatchers.IO) {
                repo.fetchLatestMissedCall()
            }
        }?.takeIf {
            MissedCallNotice.worthShowing(
                callAt = it.timestamp,
                dealtWithUpTo = dealtWithUpTo,
                now = now,
                forHours = settings.missedCallHours
            )
        }
        // A person, not a company. The card at the top of her screen was being spent on parking
        // apps and roaming tariffs — see [Home].
        val newestUnreadMsg = Home.worthANudge(msgList)

        if (missedCallNudge != null && (newestUnreadMsg == null || missedCallNudge.timestamp > newestUnreadMsg.timestamp)) {
            missedCallNudge
        } else if (newestUnreadMsg != null) {
            if (newestUnreadMsg.isMissedCall) {
                Nudge.MissedCallNudge(
                    contactName = newestUnreadMsg.senderName,
                    timestamp = newestUnreadMsg.timestamp,
                    // A missed call that reached her through a messenger rather than through the
                    // telephone. The address is a number often enough to be worth passing on, but
                    // only when it is one: a WhatsApp group is written `1203633…@g.us`, and its
                    // digits would match somebody in her address book on the last seven of them
                    // sooner or later — a card offering to ring a person who did not call.
                    number = newestUnreadMsg.senderAddress
                        ?.takeIf { !PersonName.looksLikeAddress(it) }
                        .orEmpty(),
                    message = newestUnreadMsg
                )
            } else {
                Nudge.MessageNudge(newestUnreadMsg)
            }
        } else {
            null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * What the activity starts each time it is created: the things that need an activity, or that
     * must be asked for again when one appears. Safe to call on every creation — a dark-mode switch,
     * a new font size or a language change rebuilds the activity and calls this again, and nothing
     * here starts anything twice.
     *
     * Everything this view model needs for itself arrives through its constructor and is started
     * once, in its `init` at the bottom of the class.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        checkPermissions(context)
        // The activity's own strings, so the words match the language it was just built in.
        readGroupWording(context)
        // Before the clients, not after: without it Android cuts the network of this whole
        // process the moment she opens another app, and every socket they open dies with EPERM.
        MessengerService.start(app)
        // The service starts the messengers too, but it may be refused the foreground — and then
        // this is the only thing that does. Starting twice is harmless; see [MessengerRuntime.start].
        MessengerRuntime.start(app)
        // Safe to call every time: the repository ignores this once it already has a database.
        mailRepository.attachPersistence(app)
        MailSyncWorker.schedulePeriodic(app)
        MailSyncWorker.enqueueOneTimeSync(app)
        // The groups, senders, posters and faults the website shows — process-wide, so that they
        // keep going with the screen off. The service starts it too; starting twice does nothing.
        AppGraph.caregiverPublisher(app).start()
    }

    /**
     * Reads the two words used to name several people, in whichever language Android resolved.
     *
     * Not a hardcoded pair: a phone that fell back to the English strings would otherwise show her
     * "Anna, Peter und Ruth" underneath an English screen, which is the same mistake the clock on
     * the home screen once made.
     */
    private fun readGroupWording(context: Context) {
        groupWording.value = GroupWording(
            conjunction = context.getString(R.string.group_and),
            othersFormat = context.getString(R.string.group_others),
            oneOther = context.getString(R.string.group_one_other)
        )
    }

    fun checkPermissions(context: Context) {
        loadSmsMessages(context)
    }

    fun loadSmsMessages(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            messageStore.loadSmsFromPhone(context)
        }
    }

    fun refreshContacts(force: Boolean = false) {
        // A forced refresh answers something that has actually changed — the contacts permission
        // has just been granted, or the repository has only now been built — so it supersedes a
        // scan already running rather than being dropped by it. That order matters on first run:
        // the scan in flight was started before she granted the permission, so it read an empty
        // address book, and letting it win would leave her home screen without faces until the
        // debounce below expired.
        if (force) {
            contactsRefreshJob?.cancel()
        } else {
            if (contactsRefreshJob?.isActive == true) return
            if (System.currentTimeMillis() - lastContactsRefreshTime < CONTACTS_REFRESH_DEBOUNCE_MS) return
        }

        contactsRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            val contacts = contactsRepository?.querySystemContacts().orEmpty()

            // Cancellation is cooperative and querySystemContacts() is a blocking read that never
            // checks for it, so a superseded scan arrives here holding its stale answer. Without
            // this it would publish that over the fresh one it was cancelled in favour of.
            ensureActive()

            systemContacts.value = contacts

            // Handed the scan we just paid for. It keeps its own copy of her address book for the
            // arrival pipeline, and used to read it again here — off the main thread, so nothing
            // she could feel, but still a second full contacts-provider scan per refresh.
            ConversationLinksSource.refresh(contacts)

            // Tell the website who is starred, so it has people to arrange. Sent from here rather
            // than from the favourites flow because that one also runs when nothing about the address
            // book has changed; the repository drops an unchanged list anyway. Derived from the scan
            // we just performed so her address book is only read once. Id and name only —
            // see SettingsRepository.publishStarredContacts.
            val starred = contactsRepository?.starredContacts(contacts).orEmpty()
            remoteSettingsRepository.publishStarredContacts(starred)

            pictureSources.value += 1
            lastContactsRefreshTime = System.currentTimeMillis()
        }
    }

    /**
     * Tapping a face or contact places a call routed through CallRouter (roaming-aware).
     */
    fun placeCall(context: Context, contact: Contact) {
        placeCallWithDetails(
            context = context,
            name = contact.name,
            phoneNumber = contact.phoneNumber,
            threemaId = contact.threemaId,
            photoUri = contact.photoUri
        )
    }

    fun placeCallWithNumber(context: Context, name: String, rawNumber: String) {
        placeCallWithDetails(
            context = context,
            name = name,
            phoneNumber = rawNumber,
            threemaId = null,
            photoUri = null
        )
    }

    /**
     * The routing and the ringing both live in [CallPlacer], because her watch places calls too
     * and a tap there must do exactly what a tap on the same face here does. `name` is kept only
     * for what is said aloud when it cannot be done at all.
     */
    fun placeCallWithDetails(
        context: Context,
        name: String,
        phoneNumber: String?,
        threemaId: String?,
        photoUri: String? = null
    ) {
        // Ringing the number back is what deals with a missed call, wherever in the app she
        // pressed to do it — her daughter's face, her list of calls, the address book. Here
        // rather than at any of those buttons, because every one of them ends up in this
        // function and none of them knows what card is on the home screen. See [Home.answeredBy].
        markMissedCallAnsweredBy(phoneNumber)
        val callTarget = phoneNumber?.takeIf { it.isNotBlank() } ?: name.takeIf { it.isNotBlank() }
        if (callTarget != null) {
            restlessness?.recordCallAttempt(callTarget)
        }
        if (name.isNotBlank()) {
            speech?.say(context.getString(R.string.calling_announcement, name))
        }
        CallPlacer.place(
            context = context,
            phoneNumber = phoneNumber,
            threemaId = threemaId,
            onFailed = { failure -> showCallFailedFeedback(context, failure, name, photoUri) }
        )
    }

    /**
     * Takes the missed-call card away when the call being placed is the answer to it.
     *
     * The card is read from [singleNudge] rather than from the call log again: this is about the
     * one thing on her screen, and it is the same value the screen is drawing. Nothing happens
     * when the card is about a message, when it is about somebody else, or when there is no card
     * — the common case by far, and the reason this stays a question and not a side effect.
     */
    private fun markMissedCallAnsweredBy(phoneNumber: String?) {
        val card = singleNudge.value as? Nudge.MissedCallNudge ?: return
        if (!Home.answeredBy(card, phoneNumber)) return
        markMissedCallDealtWith(card.timestamp)
    }

    private fun showCallFailedFeedback(
        context: Context,
        failure: ch.heuscher.gentleconnect.data.CallFailure,
        name: String?,
        photoUri: String?
    ) {
        val resId = when (failure) {
            ch.heuscher.gentleconnect.data.CallFailure.NOT_FROM_ABROAD -> R.string.call_failed_not_from_abroad
            ch.heuscher.gentleconnect.data.CallFailure.NO_CONNECTION -> R.string.call_failed_no_connection
            ch.heuscher.gentleconnect.data.CallFailure.NOTHING_TO_CALL -> R.string.call_not_possible
        }
        ch.heuscher.gentleconnect.voip.CallFailedActivity.start(
            context = context,
            messageResId = resId,
            contactName = name?.ifBlank { null },
            contactPhotoUri = photoUri?.ifBlank { null }
        )
    }

    /**
     * Everybody she has written to, offered as somewhere to send a photograph on to, newest
     * first.
     *
     * Built from what she has done rather than from a list somebody keeps: see
     * [Forwarding.recentRecipients], which also decides who a photograph can reach at all.
     *
     * Read from [storedMessages] and not from [messages], which is where this list was empty on
     * a real phone. [messages] carries what has *arrived* — [onDeviceMessages] is filtered to
     * INCOMING, because that is all the screens want from it — so her own hundred and fifty sent
     * messages are not in it, and her own sent messages are the whole of the evidence here.
     * Both are read, because a message she sent thirty seconds ago is live before it is stored.
     */
    val forwardRecipients: StateFlow<List<Recipient>> = combine(
        writableRecipients,
        allContacts,
        namedStoredMessages,
        messages
    ) { recipients, contacts, stored, live ->
        // Her whole address book behind the people she can write to, so that somebody she wrote
        // to once is offered under the name she filed them under and with their face, rather
        // than as eleven digits with a "4" for a portrait. The favourites come first, because
        // the name on one of those was chosen for this phone.
        val known = recipients + contacts.map { contact ->
            Recipient(
                name = contact.name,
                photoUri = contact.photoUri,
                threemaId = contact.threemaId,
                phoneNumber = contact.phoneNumber
            )
        }
        Forwarding.recentRecipients(known, stored + live)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Outgoing telephone calls placed within the ranking window (last 60 days).
     *
     * Refreshed when the call log changes or when midnight rolls over, without querying
     * on each recomposition.
     */
    private val placedCallsFlow: StateFlow<List<CallRecord>> = flow {
        val repo = callLogRepository ?: run {
            emit(emptyList())
            return@flow
        }
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val (today, _) = StepCounter.dayBounds(now)
            val since = today - 60L * 24 * 60 * 60 * 1000L
            emit(repo.placedCallsSince(since))

            val nextDay = today + 24 * 60 * 60 * 1000L + 1000L
            val delayUntilMidnight = (nextDay - System.currentTimeMillis()).coerceAtLeast(60_000L)
            withTimeoutOrNull(delayUntilMidnight) {
                repo.changes().first()
            }
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Who she can send a photograph to, in her interaction ranking order.
     *
     * Combines people she has written to on Threema/WhatsApp ([forwardRecipients]), contacts that
     * can receive media ([writableRecipients]), and groups she has actively written or reacted to.
     * Ranked by her own interaction history over the last 60 days (excluding today).
     */
    val photoRecipients: StateFlow<List<PhotoTarget>> = combine(
        combine(forwardRecipients, writableRecipients, groupPictures) { forward, writable, pictures ->
            Triple(forward, writable, pictures)
        },
        combine(namedStoredMessages, messages) { stored, live -> stored + live },
        placedCallsFlow,
        favoriteContacts
    ) { (forward, writable, pictures), allMessages, placedCalls, favourites ->
        val (todayStart, _) = StepCounter.dayBounds(System.currentTimeMillis())

        val rawPeople = forward + writable.filter { Forwarding.canCarryMedia(it) }

        fun isSamePerson(a: Recipient, b: Recipient): Boolean {
            val nameA = a.name.trim().lowercase()
            val nameB = b.name.trim().lowercase()
            if (nameA.isNotBlank() && nameA == nameB) return true

            val tA = a.threemaId?.trim()
            val tB = b.threemaId?.trim()
            if (!tA.isNullOrBlank() && !tB.isNullOrBlank() && tA.equals(tB, ignoreCase = true)) return true

            val wA = a.whatsappId?.trim()
            val wB = b.whatsappId?.trim()
            if (!wA.isNullOrBlank() && !wB.isNullOrBlank() && WhatsAppNumber.sameSubscriber(wA, wB)) return true

            val pA = a.phoneNumber?.trim()
            val pB = b.phoneNumber?.trim()
            if (!pA.isNullOrBlank() && !pB.isNullOrBlank() && CallHistory.sameNumber(pA, pB)) return true

            if (!pA.isNullOrBlank() && !wB.isNullOrBlank() && WhatsAppNumber.sameSubscriber(pA, wB)) return true
            if (!wA.isNullOrBlank() && !pB.isNullOrBlank() && WhatsAppNumber.sameSubscriber(wA, pB)) return true

            return false
        }

        fun mergeTwoRecipients(a: Recipient, b: Recipient): Recipient {
            val name = if (a.name.isNotBlank()) a.name else b.name
            val photoUri = a.photoUri ?: b.photoUri
            val threemaId = a.threemaId?.ifBlank { null } ?: b.threemaId?.ifBlank { null }
            val whatsappId = a.whatsappId?.ifBlank { null } ?: b.whatsappId?.ifBlank { null }
            val phoneNumber = a.phoneNumber?.ifBlank { null } ?: b.phoneNumber?.ifBlank { null }
            return Recipient(
                name = name,
                photoUri = photoUri,
                threemaId = threemaId,
                whatsappId = whatsappId,
                phoneNumber = phoneNumber,
                groupIdentity = null
            )
        }

        val mergedPeople = mutableListOf<Recipient>()
        for (raw in rawPeople) {
            if (raw.groupIdentity != null) continue
            val matches = mergedPeople.filter { isSamePerson(it, raw) }
            if (matches.isEmpty()) {
                mergedPeople.add(raw)
            } else {
                mergedPeople.removeAll(matches)
                var combined = raw
                for (m in matches) {
                    combined = mergeTwoRecipients(combined, m)
                }
                mergedPeople.add(combined)
            }
        }

        val eligibleGroups = writable.filter { r ->
            val group = r.groupIdentity
            group != null && !StatusBroadcast.isStatus(group)
        }

        fun personKey(r: Recipient): String {
            val nameKey = r.name.trim().lowercase()
            if (nameKey.isNotBlank()) return nameKey
            return r.threemaId ?: r.whatsappId ?: r.phoneNumber ?: "person_${r.hashCode()}"
        }

        fun groupKey(group: GroupIdentity): String =
            "group:${group.creatorIdentity}:${group.groupId}"

        val candidates = mutableListOf<PhotoCandidate>()
        val personRecipientsByKey = mutableMapOf<String, Recipient>()
        val groupRecipientsByKey = mutableMapOf<String, Recipient>()

        for (p in mergedPeople) {
            val key = personKey(p)
            personRecipientsByKey[key] = p
            candidates.add(
                PhotoCandidate(
                    key = key,
                    name = p.name,
                    threemaIds = setOfNotNull(p.threemaId?.ifBlank { null }),
                    whatsappNumbers = setOfNotNull(p.whatsappId?.ifBlank { null }),
                    phoneNumbers = setOfNotNull(p.phoneNumber?.ifBlank { null }),
                    group = null
                )
            )
        }

        for (g in eligibleGroups) {
            val group = g.groupIdentity ?: continue
            val key = groupKey(group)
            groupRecipientsByKey[key] = g
            candidates.add(
                PhotoCandidate(
                    key = key,
                    name = g.name,
                    threemaIds = emptySet(),
                    whatsappNumbers = emptySet(),
                    phoneNumbers = emptySet(),
                    group = group
                )
            )
        }

        val sentEvents = allMessages
            .filter { it.direction == MessageDirection.OUTGOING }
            .map { m ->
                PhotoEvent(
                    address = (m.senderId ?: m.senderAddress)?.trim(),
                    group = m.groupIdentity,
                    timestamp = m.timestamp
                )
            }

        val reactionEvents = allMessages
            .filter { Reactions.ownOf(it.reaction) != null }
            .map { m ->
                PhotoEvent(
                    address = (m.senderId ?: m.senderAddress)?.trim(),
                    group = m.groupIdentity,
                    timestamp = m.timestamp
                )
            }

        val callEvents = placedCalls.map { c ->
            PhotoEvent(
                address = c.number,
                group = null,
                timestamp = c.timestamp
            )
        }

        val favouriteOrder = favourites.map { it.name.trim().lowercase() }

        val rankedKeys = PhotoRecipientRanking.rank(
            candidates = candidates,
            sent = sentEvents,
            reactions = reactionEvents,
            placedCalls = callEvents,
            favouriteOrder = favouriteOrder,
            todayStart = todayStart,
            windowDays = 60
        )

        rankedKeys.mapNotNull { key ->
            val person = personRecipientsByKey[key]
            if (person != null) {
                PhotoTarget(recipient = person, picture = null, memberCount = 0)
            } else {
                val groupRecipient = groupRecipientsByKey[key]
                if (groupRecipient != null) {
                    val group = groupRecipient.groupIdentity
                    val picture = pictures[group]
                    val count = picture?.faces?.size ?: 0
                    PhotoTarget(recipient = groupRecipient, picture = picture, memberCount = count)
                } else {
                    null
                }
            }
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Sending, and the read marks that go with opening a message, belong to [Outbox]: a send runs
    // in the process's scope there, so a message on its way survives this screen going away.

    /** A heart for a partner, which is a message like any other. See [Outbox.sendHeartToPartner]. */
    suspend fun sendHeartToPartner(partner: Partner): Boolean = outbox.sendHeartToPartner(partner)

    fun markMessageAsRead(message: Message) = outbox.markMessageAsRead(message)

    fun markMessageAsReadById(messageId: String) = outbox.markMessageAsReadById(messageId)

    fun markMailAsRead(mailId: String) {
        mailRepository.markAsRead(mailId)
    }

    fun markAllMailAsRead() {
        mailRepository.markAllAsRead()
    }

    /** Fetches her post now rather than at the next scheduled sync. */
    fun triggerMailSync(context: Context) {
        MailSyncWorker.enqueueOneTimeSync(context.applicationContext)
    }

    private fun cleanPhoneNumber(raw: String): String {
        return raw.replace(Regex("[^0-9+]"), "")
    }

    override fun onCleared() {
        super.onCleared()
        // A screen closing is not the phone going away. The messenger clients and VoIP registration
        // are owned by [Messengers] and outlive this ViewModel for as long as the process runs.
        // Stopping bridges or tearing down the PhoneAccount here would disconnect her while
        // MessengerService keeps the notification up in the foreground. Teardown happens when
        // MessengerService is destroyed.
    }

    // Last in the class, so every flow above exists before anything here writes to one.
    init {
        nudgeMemory?.let { memory ->
            // What she has already answered, back from disk and followed from then on — the
            // notification's "Call back" and the telephone write to the same memory. Read before
            // the card is ever drawn, so a restart does not put yesterday's call back on screen.
            missedCallsDealtWithUpTo.value = memory.missedCallsDealtWithUpTo.value
            followMissedCallMemory(memory)
        }
        wearSyncManager?.let { wear ->
            // Her watch gets the same faces as the home screen, in the same order. All of her
            // favourites, not the five the home screen has room to draw — the watch decides for
            // itself how many it can hold.
            viewModelScope.launch { favoriteContacts.collect { faces -> wear.syncFavourites(faces) } }
        }
        seenPicturesStore?.let { store ->
            viewModelScope.launch(Dispatchers.IO) { _seenPictureIds.value = store.load() }
        }
        partnerRepository?.let { repo ->
            viewModelScope.launch { repo.partners.collect { list -> rawPartners.value = list } }
        }
        // A profile picture arrived from the bridge, so any screen showing faces has a new picture
        // to draw. Raised directly rather than left to the contacts rescan to notice: a sync brings
        // avatars in a burst, the rescan is debounced against exactly that, and the picture may
        // belong to somebody who is not in her address book at all.
        viewModelScope.launch {
            MessengerRuntime.avatarArrivals.collect { jid ->
                pictureSources.value += 1
                // A group's picture belongs to nobody in her address book: redrawing is enough,
                // and every group reports one on every connection.
                if (!jid.endsWith("@g.us", ignoreCase = true)) refreshContacts()
            }
        }
        // Her address book, read once straight away so the faces are there on the first frame.
        if (contactsRepository != null) refreshContacts(force = true)
    }

    private companion object {
        const val TAG = "HomeViewModel"

        /**
         * How soon her address book may be rescanned after a successful read.
         *
         * Every time she returns to the home screen Android fires onResume, but her contacts have
         * not changed in the three seconds she spent reading a text. Thirty seconds is short enough
         * that someone newly starred by a family member appears promptly, and long enough to spare
         * the phone a full database scan on every single tap of the home button.
         */
        const val CONTACTS_REFRESH_DEBOUNCE_MS = 30_000L
    }
}
