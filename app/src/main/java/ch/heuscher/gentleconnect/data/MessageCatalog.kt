package ch.heuscher.gentleconnect.data

import android.content.Context
import ch.heuscher.gentleconnect.R
import ch.heuscher.gentleconnect.data.db.ThreemaContactEntity
import ch.heuscher.gentleconnect.data.db.ThreemaGroupEntity
import ch.heuscher.gentleconnect.data.db.ThreemaGroupMemberEntity
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppGroups
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.ContactNames
import ch.heuscher.gentleconnect.model.DisplayNames
import ch.heuscher.gentleconnect.model.GroupFaces
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.MessageVisibility
import ch.heuscher.gentleconnect.model.PersonName
import ch.heuscher.gentleconnect.model.RemoteSettings
import ch.heuscher.gentleconnect.model.StatusPosterVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The two words needed to name several people, in whichever language Android resolved.
 *
 * Held rather than looked up at the point of use because [GroupDescription] is pure — it must not
 * reach for a Context, or it could not be tested, and the "and N others" cutoff is exactly the
 * sort of rule that is wrong in a way nobody notices.
 */
data class GroupWording(
    val conjunction: String = "and",
    val othersFormat: String = "%d others",
    val oneOther: String = "1 other"
) {
    /** "7 others", and "1 other" rather than "1 others". */
    fun others(count: Int): String =
        if (count == 1) oneOther else String.format(othersFormat, count)
}

/**
 * Every message the phone has, named the way her screen names it, and the people they are from.
 *
 * This chain of flows used to live in the home screen's view model, so the only list of messages
 * with names on it existed while the screen did. Everything else that needed one — the arrival
 * pipeline deciding what to say out loud, the caregiver website's lists of groups, senders and
 * posters — either built a cruder list of its own or only ran while somebody was looking. They now
 * read this one, so the phone announces exactly what the home card shows, by construction.
 *
 * One instance per process, from [AppGraph.catalog], fed by [attach] and shared eagerly. A test or
 * a view model without a context builds its own with nothing attached: the source flows are then
 * empty unless written to directly, which is what the view model's no-database fallbacks do.
 */
class MessageCatalog(
    private val scope: CoroutineScope,
    private val messageStore: LocalMessageStore,
    private val remoteSettingsRepository: SettingsRepository,
    private val sharing: SharingStarted = SharingStarted.Eagerly
) {

    /** Used to resolve the favourites against her address book. Null until [attach]. */
    @Volatile
    var contactsRepository: ContactsRepository? = null

    val remoteSettings: StateFlow<RemoteSettings> = remoteSettingsRepository.settings

    /** Messages from the old server-side bridge, from Firestore. */
    val bridgeMessages = MutableStateFlow<List<Message>>(emptyList())

    /** Everything she has sent, and everything the Threema client received, from the store. */
    val storedMessages = MutableStateFlow<List<Message>>(emptyList())

    /**
     * Messages the embedded Threema client received, newest last — the incoming on-device rows of
     * [storedMessages]. Written directly only where there is no database, which is in tests.
     */
    val onDeviceMessages = MutableStateFlow<List<Message>>(emptyList())

    val threemaGroups = MutableStateFlow<List<ThreemaGroupEntity>>(emptyList())
    val threemaGroupMembers = MutableStateFlow<List<ThreemaGroupMemberEntity>>(emptyList())
    val importedContacts = MutableStateFlow<List<ThreemaContactEntity>>(emptyList())

    /** Normalised phone number to Threema identity, as resolved by the directory server. */
    val matchedIdentities = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Her address book, as last scanned. See [ConversationLinksSource.contacts]. */
    val systemContacts = MutableStateFlow<List<Contact>>(emptyList())

    /**
     * Her own Threema identity, so that she can be left out of the list of people a conversation
     * is described by. Null until it has been read, which only costs her own name appearing in a
     * description for a moment.
     */
    val ownThreemaId = MutableStateFlow<String?>(null)

    /**
     * The two words needed to name several people in her language. A flow so the descriptions are
     * rebuilt when an activity reads them again in another language.
     */
    val groupWording = MutableStateFlow(GroupWording())

    /** The WhatsApp groups, named by the bridge. */
    private val whatsAppGroups: StateFlow<Map<GroupIdentity, String>> = MessengerRuntime.whatsAppGroups

    /**
     * Which addresses may be treated as the same person. Built from the directory matches only —
     * see [ConversationLinks] for why a wrong merge is the one mistake she cannot detect.
     */
    val conversationLinks: StateFlow<ConversationLinks> = ConversationLinksSource.conversationLinks

    private var attached = false

    /**
     * Feeds the sources from the database, the address book, Firestore and the messenger runtime.
     * Once per instance; later calls do nothing.
     */
    @Synchronized
    fun attach(context: Context) {
        if (attached) return
        attached = true
        val app = context.applicationContext
        contactsRepository = AppGraph.contacts(app)
        ConversationLinksSource.start(app)
        groupWording.value = GroupWording(
            conjunction = app.getString(R.string.group_and),
            othersFormat = app.getString(R.string.group_others),
            oneOther = app.getString(R.string.group_one_other)
        )
        val db = AppGraph.database(app)
        scope.launch(Dispatchers.IO) {
            if (ownThreemaId.value == null) {
                ownThreemaId.value = Messengers.threemaIdentity(app).getIdentity()?.threemaId?.trim()?.uppercase()
            }
        }
        scope.launch {
            AppGraph.conversations(app).messages.collect { stored ->
                storedMessages.value = stored
                // What the Threema client received comes back off disk, so a restart does not
                // lose it. Bridge messages persist in Firestore and SMS in the system inbox.
                onDeviceMessages.value = stored.filter {
                    it.direction == MessageDirection.INCOMING && it.channel == MessageChannel.ON_DEVICE
                }
            }
        }
        val groups = AppGraph.threemaGroups(app)
        scope.launch { groups.groups.collect { threemaGroups.value = it } }
        scope.launch { groups.members.collect { threemaGroupMembers.value = it } }
        scope.launch { db.threemaContactDao().getAllContactsFlow().collect { importedContacts.value = it } }
        scope.launch {
            db.threemaMatchDao().getAllMatchesFlow().collect { matches ->
                matchedIdentities.value = matches.associate { it.phoneNumber to it.identity }
            }
        }
        scope.launch { AppGraph.bridgeMessages(app).messages.collect { bridgeMessages.value = it } }
        // The address book, kept for the arrival pipeline and refreshed by whoever scans it. The
        // home screen also writes here straight after a scan, so its faces do not wait a round.
        scope.launch { ConversationLinksSource.contacts.collect { systemContacts.value = it } }
    }

    fun normalizedNumberOf(contact: Contact): String? =
        ConversationLinksSource.normalizedNumberOf(contact)

    fun conversationIdOf(message: Message, links: ConversationLinks): String? =
        ConversationLinksSource.conversationIdOf(message, links)

    // Everything that arrived over a messenger — the server-side bridge and the embedded Threema
    // client alike — with sender IDs resolved to the names from her imported Threema backup.
    // Both sources need identical treatment: an unresolved ECHO1001 on her screen is unreadable
    // whichever route carried it.
    val resolvedMessengerMessages: StateFlow<List<Message>> = combine(
        bridgeMessages,
        onDeviceMessages,
        importedContacts
    ) { bridged, onDevice, contacts ->
        val msgs = bridged + onDevice
        if (contacts.isEmpty()) return@combine msgs
        val contactMap = contacts.associateBy { it.identity.trim().uppercase() }
        msgs.map { msg ->
            val senderId = msg.senderId?.trim()?.uppercase()
            val match = if (senderId != null) contactMap[senderId] else null
            if (match != null && PersonName.isUnresolved(msg.senderName, msg.senderId)) {
                msg.copy(senderName = match.displayName)
            } else {
                msg
            }
        }
    }.stateIn(scope, sharing, emptyList())

    // Combined message stream (her SMS inbox + the server-side bridge), newest first, deduped
    val messages: StateFlow<List<Message>> = combine(
        messageStore.messages,
        resolvedMessengerMessages
    ) { notifMsgs, bMsgs ->
        MessageMerger.mergeMessages(notifMsgs, bMsgs)
    }.stateIn(scope, sharing, emptyList())

    // Resolved favorite faces backed by ContactsContract read / RemoteSettings fallback
    val favoriteContacts: StateFlow<List<Contact>> = combine(
        remoteSettings,
        systemContacts,
        importedContacts,
        matchedIdentities
    ) { settings, contacts, threemaContacts, matched ->
        val resolved = contactsRepository?.resolveFavouritesFrom(
            contacts,
            settings.favoriteContacts,
            settings.favouriteOrder
        )
        val source = if (!resolved.isNullOrEmpty()) resolved else settings.favoriteContacts
        // The faces on her home screen are also the list of people she can write to
        // (writableRecipients), so they need the same Threema IDs the phone book gets.
        withThreemaIds(source, threemaContacts, matched)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, sharing, remoteSettingsRepository.settings.value.favoriteContacts)

    /**
     * Her whole address book, for the phone-book screen.
     *
     * The favourites row on the home screen is a shortlist somebody else chose for her; this is
     * everyone. Ordered by name because that is the order she would look for someone in, and
     * because the underlying query returns contacts in whatever order the address book stores
     * them.
     */
    val allContacts: StateFlow<List<Contact>> = combine(
        systemContacts,
        favoriteContacts,
        importedContacts,
        matchedIdentities
    ) { contacts, favorites, threemaContacts, matched ->
        // Fall back to the favourites when contacts cannot be read at all, so the screen is
        // never simply empty while she can plainly see faces on the home screen.
        val source = contacts.ifEmpty { favorites }
        withThreemaIds(source, threemaContacts, matched)
            .sortedBy { it.name.trim().lowercase() }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, sharing, emptyList())

    /**
     * Gives contacts the Threema IDs that reach them, from both sources we have.
     *
     * `ContactsContract` has no Threema column, so without this nobody in her address book has an
     * ID and [MessageRouter] can never pick Threema for anyone.
     *
     * Order matters. The name match runs first and the number match second, so that where the two
     * disagree the number wins: names are guesswork, whereas a number was resolved by the
     * directory server itself.
     */
    private fun withThreemaIds(
        contacts: List<Contact>,
        threemaContacts: List<ThreemaContactEntity>,
        matched: Map<String, String>
    ): List<Contact> =
        ThreemaNumberMatcher.attachThreemaIds(
            ThreemaContactMatcher.attachThreemaIds(contacts, threemaContacts),
            matched,
            ::normalizedNumberOf
        )

    /**
     * The names she knows people by, keyed on their Threema identity.
     *
     * Her address book first and the imported Threema backup second — written in that order so the
     * address book wins, because that is the name she chose for that person. The raw identity is
     * the last resort, and it is a bad one: `ECHO1001` is not a person to her.
     */
    val nameByIdentity: StateFlow<Map<String, String>> = combine(
        allContacts,
        importedContacts
    ) { contacts, imported ->
        // The rule itself lives in [DisplayNames], because the arrival pipeline has to answer the
        // same question when it announces a message out loud and the two must not drift.
        DisplayNames.byIdentity(contacts, imported.map { it.identity to it.displayName })
    }.stateIn(scope, sharing, emptyMap())

    /**
     * The names she gave the people whose Threema identity the directory confirmed.
     *
     * Applied to every message from them, not only to the ones that arrived unnamed: see
     * [DisplayNames.byContact]. This is the rule that puts "Dad" on a conversation the backup
     * filed under "Johann Hartmann".
     */
    val contactNames: StateFlow<ContactNames> = allContacts
        .map { contacts ->
            ContactNames(
                byIdentity = DisplayNames.byContact(contacts),
                byNumber = DisplayNames.byNumber(contacts)
            )
        }
        .stateIn(scope, sharing, ContactNames())

    /**
     * How several people are named on her screen, per group.
     *
     * Never "group", never a messenger's name — see [GroupDescription]. A group we have been told
     * nothing about simply has no entry here, and the caller falls back to whoever wrote rather
     * than to a placeholder she would have to interpret.
     */
    val threemaGroupDescriptions: StateFlow<Map<GroupIdentity, String>> = combine(
        threemaGroups,
        threemaGroupMembers,
        nameByIdentity,
        groupWording
    ) { groups, members, names, wording ->
        val membersByGroup = members.groupBy { GroupIdentity(it.creatorIdentity, it.groupId) }
        val rowsByGroup = groups.associateBy { GroupIdentity(it.creatorIdentity, it.groupId) }
        val own = ownThreemaId.value

        (rowsByGroup.keys + membersByGroup.keys).mapNotNull { group ->
            // She is not one of the people a conversation is described by, and the creator — whom
            // the setup message leaves out — is: GroupFaces.memberIdentities.
            val memberNames = GroupFaces
                .memberIdentities(group, membersByGroup[group].orEmpty().map { it.identity }, own)
                .map { names[it] ?: it }
            val described = GroupDescription.of(
                groupName = rowsByGroup[group]?.name,
                memberNames = memberNames,
                conjunction = wording.conjunction
            ) { count -> wording.others(count) }
            described?.let { group to it }
        }.toMap()
    }.stateIn(scope, sharing, emptyMap())

    /**
     * What every group is called, whichever messenger it is on.
     *
     * Two sources that never overlap: Threema groups are described from their control messages and
     * their member names, WhatsApp groups are named by the bridge. Merged here so that everything
     * downstream — her screen, the conversation titles, the caregiver log — asks one question and
     * gets one answer, rather than growing a second code path for the second messenger.
     */
    val groupDescriptions: StateFlow<Map<GroupIdentity, String>> =
        combine(threemaGroupDescriptions, whatsAppGroups) { threema, whatsapp -> threema + whatsapp }
            .stateIn(scope, sharing, emptyMap())


    /**
     * Which of the groups may be written back to, and who a reply would have to reach.
     *
     * Kept next to [groupDescriptions] because they answer the two halves of the same question:
     * what these people are called, and whether she can answer them. Both are derived from the
     * control messages, and a group we have heard from but never been sent a member list for gets
     * a name here and no reply — see [GroupReplyPolicy] for why guessing at a membership is the
     * one thing this must not do.
     */
    val groupReplyDecisions: StateFlow<Map<GroupIdentity, GroupReplyPolicy.Decision>> = combine(
        threemaGroups,
        threemaGroupMembers
    ) { groups, _ ->
        groups.associate { row ->
            val group = GroupIdentity(row.creatorIdentity, row.groupId)
            group to replyDecisionFor(group)
        }
    }.stateIn(scope, sharing, emptyMap())

    /**
     * Whether these several people may be answered right now, asked at the moment of asking.
     *
     * Read straight from the two group flows rather than from [groupReplyDecisions], which is
     * shared only while something is collecting it. A send is not a subscriber, so going through
     * the shared map would mean a reply refused as "we do not know who is in this group" purely
     * because no screen happened to be looking at the time.
     */
    fun replyDecisionFor(group: GroupIdentity): GroupReplyPolicy.Decision {
        fun isThisGroup(creator: String, id: String) =
            creator.equals(group.creatorIdentity, ignoreCase = true) &&
                id.equals(group.groupId, ignoreCase = true)

        return GroupReplyPolicy.decide(
            group = group,
            ownIdentity = ownThreemaId.value,
            knownMembers = threemaGroupMembers.value
                .filter { isThisGroup(it.creatorIdentity, it.groupId) }
                .map { it.identity },
            lastSetupAt = threemaGroups.value
                .firstOrNull { isThisGroup(it.creatorIdentity, it.groupId) }
                ?.lastSetupAt
                ?: 0L
        )
    }

    /**
     * Everything that arrived, with the names her screen needs on it.
     *
     * Separate from [messages] because resolving a name needs her address book, and her address
     * book is read further down this file than the message streams are. The three screens that
     * show messages all read from here.
     */
    val namedMessages: StateFlow<List<Message>> = combine(
        messages,
        groupDescriptions,
        nameByIdentity,
        groupReplyDecisions,
        contactNames
    ) { list, descriptions, names, replyDecisions, contactNames ->
        named(list, descriptions, names, replyDecisions, contactNames)
    }.stateIn(scope, sharing, emptyList())

    /**
     * The same, for what is in the database rather than what has just arrived.
     *
     * These are two streams and both reach her screen: [messages] is what the bridges and the
     * notification listener have handed over since the app started, [storedMessages] is the
     * database — which is where an imported backup lives, all 2,315 messages of it. Naming only
     * the first meant the rule that puts "Dad" on her father's conversation worked on the message
     * he sent this morning and on none of the four years behind it: the list showed him under the
     * name the backup carried, "Johann Hartmann", which is not what she calls him.
     *
     * Deliberately a second flow rather than a fifth argument to the combine above, because
     * [combine] takes five and this needs the same five.
     */
    val namedStoredMessages: StateFlow<List<Message>> = combine(
        storedMessages,
        groupDescriptions,
        nameByIdentity,
        groupReplyDecisions,
        contactNames
    ) { list, descriptions, names, replyDecisions, contactNames ->
        named(list, descriptions, names, replyDecisions, contactNames)
    }.stateIn(scope, sharing, emptyList())

    /**
     * What each message is called on her screen, applied to a list of them.
     *
     * Shared by the two flows above so that a message cannot be named one way when it arrives and
     * another way once it has been stored — which she would see as her son's name changing on the
     * same conversation between one launch and the next.
     */
    private fun named(
        list: List<Message>,
        descriptions: Map<GroupIdentity, String>,
        names: Map<String, String>,
        replyDecisions: Map<GroupIdentity, GroupReplyPolicy.Decision>,
        contactNames: ContactNames
    ): List<Message> {
        if (descriptions.isEmpty() && names.isEmpty() && replyDecisions.isEmpty() && contactNames.isEmpty) {
            return list
        }
        return list.map { message ->
            val identity = message.senderId?.trim()?.uppercase()
            // Her address book, found by telephone number rather than by Threema identity — which
            // is the only way most people on this phone can be found at all. Without it a name is
            // whatever was known the moment the message was stored: a WhatsApp message that
            // landed before the address book had been read kept the name the sender chose for
            // themselves, and one contact of hers calls herself "." on WhatsApp.
            val byNumber = contactNames.byNumber[
                WhatsAppNumber.subscriberKey(message.senderId ?: message.senderAddress)
            ]
            val resolved = when {
                // What she calls him wins, whatever name the message came in wearing. Her father
                // is "Johann 'Papa Opa' Hartmann" in the Threema backup and Dad in her address
                // book, and it was the backup's name on the conversation until this line existed.
                identity != null && contactNames.byIdentity[identity] != null ->
                    message.copy(senderName = contactNames.byIdentity.getValue(identity))
                // The same rule, for a person her address book knows by number.
                byNumber != null -> message.copy(senderName = byNumber)
                // Otherwise a name is only filled in where there was none worth showing: a bare
                // ECHO1001 is unreadable, but a name from the messenger is better than nothing.
                identity != null && looksUnresolved(message) ->
                    names[identity]?.let { message.copy(senderName = it) } ?: message
                else -> message
            }
            val group = message.groupIdentity ?: return@map resolved
            resolved.copy(
                groupName = descriptions[group] ?: resolved.groupName,
                // Whether the reply field is offered at all. Absent rather than explained: a
                // group whose membership nobody has told us about is not a fault she caused or
                // can fix, so it goes to the caregiver and not to her screen.
                // WhatsApp groups are answerable the moment they arrive: the group has a real
                // address and one message reaches everybody, so there is no membership to learn
                // and no partial delivery to report. Threema has neither of those luxuries —
                // see WhatsAppGroups for the whole difference.
                groupCanBeAnswered = if (message.transport == BridgeTransport.WHATSAPP) {
                    WhatsAppGroups.canBeAnswered(group)
                } else {
                    replyDecisions[group] is GroupReplyPolicy.Decision.SendTo
                }
            )
        }
    }

    /** A name that is really an address, or no name at all. Anything else she was given on purpose. */
    private fun looksUnresolved(message: Message): Boolean =
        PersonName.isUnresolved(message.senderName, message.senderId)

    /**
     * Everything she has, with repeats folded away — the one list both screens read from.
     *
     * Her carrier sent the same roaming tariff four times, so the message list opened on four
     * identical cards while the home screen, which had always collapsed them, showed one. The
     * same message must not look like one thing or four depending on how far she has scrolled,
     * so the folding happens once, here, and neither screen decides for itself.
     *
     * The list arrives newest first, and [distinctBy] keeps the first it sees, so what survives
     * is the most recent copy.
     */
    val visibleMessages: StateFlow<List<Message>> = combine(
        namedMessages,
        namedStoredMessages,
        conversationLinks,
        remoteSettings
    ) { arrived, stored, links, settings ->
        MessageVisibility.visible(arrived, stored, settings) { message ->
            conversationIdOf(message, links)
        }
    }.stateIn(scope, sharing, emptyList())

    /**
     * The pictures people have posted, newest first.
     *
     * The other half of the filter above. Both the live arrivals and the stored rows are read, so
     * what is on the shelf survives a restart and does not wait for the next person to post.
     *
     * No unread state is kept and none is shown. Nobody is waiting for her to look at these, and
     * WhatsApp would tell the poster if she had — see the Look screen for why that is never done.
     */
    val statusPosts: StateFlow<List<Message>> = combine(
        namedMessages,
        namedStoredMessages,
        remoteSettings
    ) { arrived, stored, settings ->
        // Posters the caregiver switched off are left out here, the one place every screen that
        // draws a status reads from — see [StatusPosterVisibility].
        StatusPosterVisibility.visible(
            allStatuses(arrived, stored),
            StatusPosterVisibility.hiddenKeys(settings.hiddenStatusPosters)
        )
    }.stateIn(scope, sharing, emptyList())

    fun allStatuses(arrived: List<Message>, stored: List<Message>): List<Message> =
        (arrived + stored)
            .filter { it.isStatus }
            .distinctBy { it.id }
            .sortedByDescending { it.timestamp }
}
