package ch.heuscher.gentleconnect.data

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.ContactPhone
import ch.heuscher.gentleconnect.model.DisplayNames
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.Recipient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Single process-scoped source of conversation links.
 *
 * Derives which addresses and identities belong to the same contact, built from contacts,
 * directory matches, and all address book phone numbers.
 */
object ConversationLinksSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _links = MutableStateFlow(ConversationLinks.EMPTY)
    val links: StateFlow<ConversationLinks> = _links.asStateFlow()
    val conversationLinks: StateFlow<ConversationLinks> = _links.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())

    /**
     * Her address book, read once per refresh rather than once per message.
     *
     * Published here because the arrival pipeline needs it for every WhatsApp message that lands,
     * and querying the contacts provider on each one is a full scan of fourteen hundred people to
     * put one name on one line.
     */
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _nameByIdentity = MutableStateFlow<Map<String, String>>(emptyMap())

    /** What she calls the people behind Threema identities. See [DisplayNames]. */
    val nameByIdentity: StateFlow<Map<String, String>> = _nameByIdentity.asStateFlow()

    private val _phonesByContactId = MutableStateFlow<Map<String, List<ContactPhone>>>(emptyMap())

    /**
     * Every number of everybody, by whose it is.
     *
     * [contacts] answers with one number per person because the screens that show a face want
     * one. This is the other question — how can this person be reached — and the help button asks
     * it: see [ch.heuscher.gentleconnect.model.ContactPhone].
     */
    val phonesByContactId: StateFlow<Map<String, List<ContactPhone>>> = _phonesByContactId.asStateFlow()

    private val _threemaIdByNumber = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Normalised number to Threema identity, as the directory answered. */
    val threemaIdByNumber: StateFlow<Map<String, String>> = _threemaIdByNumber.asStateFlow()

    private val _contactsWithIdentities = MutableStateFlow<List<Contact>>(emptyList())

    /** [contacts] with the Threema IDs attached — what the support group resolves against. */
    val contactsWithIdentities: StateFlow<List<Contact>> = _contactsWithIdentities.asStateFlow()

    private val refreshTrigger = MutableStateFlow(0)

    /**
     * A scan of her address book somebody else has already paid for, waiting to be used once.
     *
     * Reading the contacts provider is the most expensive thing here, and the view model reads it
     * on every refresh already. Rather than both of us scanning, [refresh] may be handed that
     * result and this holds it until the collector below picks it up.
     *
     * Taken exactly once, and never held across a refresh that did not bring one: a rescan
     * triggered by the directory matches changing must read the address book for itself rather
     * than rebuild the links from however stale a list happened to be left here.
     */
    @Volatile
    private var handedScan: List<Contact>? = null

    private var contactsRepository: ContactsRepository? = null
    private var database: AppDatabase? = null
    private var started = false

    private var defaultCountryIso: String = FALLBACK_COUNTRY_ISO
    private var defaultCallingCode: String = FALLBACK_CALLING_CODE

    /**
     * Which addresses may be treated as the same person.
     *
     * Built from the directory matches only, never from [ThreemaContactMatcher]'s name guesses —
     * see [ConversationLinks] for why a wrong merge is the one kind of mistake she cannot detect.
     */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true

        val appContext = context.applicationContext
        readHomeRegion(appContext)
        val contactsRepo = ContactsRepository(appContext)
        contactsRepository = contactsRepo
        val db = AppDatabase.getInstance(appContext)
        database = db

        scope.launch {
            combine(
                db.threemaMatchDao().getAllMatchesFlow(),
                refreshTrigger
            ) { matches, _ ->
                matches.associate { it.phoneNumber to it.identity }
            }.collect { matched ->
                val contacts = handedScan?.also { handedScan = null }
                    ?: contactsRepo.querySystemContacts()
                // All of everybody's numbers, not just the one each [Contact] carries — see
                // [ConversationLinks.from]. Read here rather than folded into [allContacts] because the
                // screens that show people want one number per person and this is the only thing that
                // wants them all.
                val everyPhone = contactsRepo.queryEveryNumber()
                _phonesByContactId.value = everyPhone.groupBy { it.contactId }
                _threemaIdByNumber.value = matched
                // Published before the contacts, because the identities below are attached from
                // these and a collector woken by the new contact list must not read an empty map.
                _contacts.value = contacts
                val everyNumber = everyPhone
                    .mapNotNull { phone -> normalizedNumber(phone.number)?.let { it to phone.contactId } }
                _links.value = ConversationLinks.from(contacts, matched, ::normalizedNumberOf, everyNumber)
            }
        }

        // The names, kept in step with both of the things they are built from.
        //
        // `ContactsContract` has no Threema column, so a contact read from the phone carries no
        // identity at all and a map built from those alone is empty — which is not a missing
        // nicety, it is the arrival notice reading "TSTMBR01 hat geschrieben". The identities come
        // from the directory matches and from the imported backup, and have to be attached before
        // the names are taken, exactly as the screens do it.
        scope.launch {
            combine(
                db.threemaContactDao().getAllContactsFlow(),
                db.threemaMatchDao().getAllMatchesFlow(),
                contacts
            ) { imported, matches, known ->
                val matched = matches.associate { it.phoneNumber to it.identity }
                val withIdentities = ThreemaNumberMatcher.attachThreemaIds(
                    ThreemaContactMatcher.attachThreemaIds(known, imported),
                    matched,
                    ::normalizedNumberOf,
                    ::otherNormalizedNumbersOf
                )
                _contactsWithIdentities.value = withIdentities
                DisplayNames.byIdentity(
                    contacts = withIdentities,
                    imported = imported.map { it.identity to it.displayName }
                )
            }.collect { _nameByIdentity.value = it }
        }
    }

    /**
     * Rebuild the links, optionally from an address book that has just been read elsewhere.
     *
     * @param scanned the caller's own [ContactsRepository.querySystemContacts] result, when it
     *   has one. Passing it spares the phone a second full scan per refresh; omitting it — which
     *   is what a caller with nothing in hand should do — reads the address book here as before.
     */
    fun refresh(scanned: List<Contact>? = null) {
        if (scanned != null) handedScan = scanned
        refreshTrigger.value += 1
    }

    fun normalizedNumberOf(contact: Contact): String? = normalizedNumber(contact.phoneNumber)

    /**
     * Their other numbers, normalised — everything but the one the [Contact] carries.
     *
     * Which one it carries is decided by the order the contacts provider returns rows in, so a
     * person with an office line and a mobile is reachable on a number this app was not looking
     * at. See [ch.heuscher.gentleconnect.model.ContactPhone].
     */
    fun otherNormalizedNumbersOf(contact: Contact): List<String> {
        val phones = _phonesByContactId.value[contact.id] ?: return emptyList()
        val primary = normalizedNumberOf(contact)
        return phones.mapNotNull { normalizedNumber(it.number) }
            .filter { it != primary }
            .distinct()
    }

    fun normalizedNumber(raw: String?): String? {
        val number = raw?.trim()?.ifEmpty { null } ?: return null
        return PhoneNumberNormalizer.matchable(
            frameworkE164 = runCatching {
                PhoneNumberUtils.formatNumberToE164(number, defaultCountryIso)
            }.getOrNull(),
            raw = number,
            defaultCallingCode = defaultCallingCode
        )
    }

    /**
     * Which conversation a message belongs to, or null when there is nothing to file it under.
     *
     * Derived every time rather than trusting what the store recorded, and the stored id is only
     * the fallback. A Threema message that arrived before contact sync had run was filed under a
     * bare identity; once the directory answers, the same person is filed under her contact — and
     * re-deriving means the earlier messages move to join the later ones rather than the thread
     * quietly splitting in two, which is the sort of thing she would experience as her daughter's
     * messages having gone missing.
     *
     * The links win over the contact a message was stamped with. Two address book entries can
     * share a number, and the stamps came from different lookups: the SMS import asks the
     * system, which answered with one of them, while what the app recorded as she sent it named
     * the other. One 👍 to that number showed as two conversations. The links pick one owner per
     * number, so every copy lands in the same place; the stamp only counts for an address the
     * links do not know.
     */
    fun conversationIdOf(message: Message, links: ConversationLinks = _links.value): String? {
        val number = normalizedNumber(message.senderAddress ?: message.senderId)
        val threemaId = message.senderId?.takeIf { message.transport == BridgeTransport.THREEMA }
        return ConversationKey.of(
            groupIdentity = message.groupIdentity,
            confirmedContactId = links.contactIdFor(threemaId, number)
                ?: message.contactId,
            threemaId = threemaId,
            normalizedPhoneNumber = number,
            // A sender that is a name and not a number still gets a conversation of its own — see
            // [ConversationKey.SERVICE_PREFIX]. Only used when nothing above matched, so this never
            // takes a message away from the person it belongs to.
            serviceAddress = message.senderAddress
        ) ?: message.conversationId
    }

    fun conversationIdOf(recipient: Recipient, links: ConversationLinks = _links.value): String? {
        val number = normalizedNumber(recipient.phoneNumber)
        return ConversationKey.of(
            // A group keys on the group, so writing to the family from the picker lands in the
            // same conversation as answering them — rather than opening a second, empty one
            // beside the thread she has been reading all year.
            groupIdentity = recipient.groupIdentity,
            confirmedContactId = links.contactIdFor(recipient.threemaId, number),
            threemaId = recipient.threemaId,
            normalizedPhoneNumber = number
        )
    }

    private fun readHomeRegion(context: Context) {
        val iso = runCatching {
            val telephony = context.applicationContext
                .getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephony?.simCountryIso?.takeIf { it.isNotBlank() }
                ?: telephony?.networkCountryIso?.takeIf { it.isNotBlank() }
        }.getOrNull()?.lowercase()

        defaultCountryIso = iso ?: FALLBACK_COUNTRY_ISO
        defaultCallingCode = CALLING_CODES[defaultCountryIso] ?: FALLBACK_CALLING_CODE
    }

    const val FALLBACK_COUNTRY_ISO = "ch"
    const val FALLBACK_CALLING_CODE = "41"

    /**
     * Only the countries her address book plausibly contains.
     *
     * A wrong calling code produces a number that belongs to somebody else, so this stays
     * short on purpose: a country that is missing here simply means those numbers are not
     * looked up, which is the failure we can live with.
     */
    val CALLING_CODES = mapOf(
        "ch" to "41",
        "de" to "49",
        "at" to "43",
        "fr" to "33",
        "it" to "39",
        "li" to "423"
    )
}
