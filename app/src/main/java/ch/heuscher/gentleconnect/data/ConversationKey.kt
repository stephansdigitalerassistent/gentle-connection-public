package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.GroupIdentity

/**
 * Which conversation something belongs to.
 *
 * **One person is one conversation, even when they reach her two different ways.** Her daughter
 * texting from a train and writing on Threema from home is one person and belongs in one place;
 * splitting her across two threads by transport would make the app's plumbing her problem.
 *
 * So the key is the contact she is matched to, and only falls back to the raw address when she is
 * matched to nobody. See [ConversationLinks] for the deliberately narrow rule about when a match
 * counts.
 */
object ConversationKey {

    const val CONTACT_PREFIX = "contact:"
    const val THREEMA_PREFIX = "threema:"
    const val PHONE_PREFIX = "phone:"
    const val GROUP_PREFIX = "group:"

    /**
     * A sender that is a name rather than a number — "Zur Rose", "RoamingInfo", "SwissPass".
     *
     * These have no telephone behind them and so had no conversation at all, which was not a
     * cosmetic gap. Tapping one opened a screen with no name and nothing in it, because the
     * conversation it was asked for did not exist; the messages could not be read anywhere; and
     * nothing in them could ever be marked as seen, because being seen is something that happens to
     * a conversation. Every pharmacy, carrier and parking app on her phone was in that state.
     */
    const val SERVICE_PREFIX = "service:"

    /**
     * @param groupIdentity wins over everything else. Several people are not a person, however
     *   well we know the one of them who happened to write.
     * @param confirmedContactId the contact this address resolves to — **only** when the link was
     *   confirmed by the Threema directory or is the address book's own telephone number. Never a
     *   name guess; see [ConversationLinks].
     * @param normalizedPhoneNumber digits only, no `+`, as [PhoneNumberNormalizer] produces.
     * @param serviceAddress the sender's address when it is a name rather than a number, which is
     *   the last thing tried — a company that also has a telephone number is keyed on the number
     *   like anybody else. Case is folded, because the same shop writes to her as "Zur Rose" one
     *   month and "ZUR ROSE" the next and that is one conversation.
     * @return null when there is nothing to key on, which means the message cannot be filed and
     *   the caller must not invent a conversation for it.
     */
    fun of(
        groupIdentity: GroupIdentity? = null,
        confirmedContactId: String? = null,
        threemaId: String? = null,
        normalizedPhoneNumber: String? = null,
        serviceAddress: String? = null
    ): String? {
        groupIdentity?.let { return GROUP_PREFIX + it.creatorIdentity + ":" + it.groupId }
        confirmedContactId?.trimmedOrNull()?.let { return CONTACT_PREFIX + it }
        threemaId?.trimmedOrNull()?.let { return THREEMA_PREFIX + it.uppercase() }
        normalizedPhoneNumber?.trimmedOrNull()?.let { return PHONE_PREFIX + it }
        serviceAddress?.trimmedOrNull()?.let { return SERVICE_PREFIX + it.lowercase() }
        return null
    }

    private fun String.trimmedOrNull(): String? = trim().ifEmpty { null }
}

/**
 * The links that are allowed to merge two addresses into one conversation.
 *
 * **Merge only on a directory-confirmed link.** [ThreemaNumberMatcher] works from identities the
 * Threema directory itself resolved from a phone number; [ThreemaContactMatcher] works from names,
 * which is guesswork. A wrong merge splices two people's conversations into one thread — her
 * doctor's messages appearing under her sister's name — and she has no way to detect that, because
 * a thread with the wrong name on it looks exactly like a thread with the right name on it.
 *
 * So a name match may still fill in a contact's Threema ID for *routing* a reply, where being wrong
 * means a message bounces and somebody notices. It may never be enough to *merge two
 * conversations*, where being wrong is silent.
 */
class ConversationLinks(
    private val contactIdByIdentity: Map<String, String>,
    private val contactIdByNumber: Map<String, String>
) {

    fun contactIdFor(threemaId: String?, normalizedPhoneNumber: String?): String? {
        val identity = threemaId?.trim()?.uppercase()?.ifEmpty { null }
        if (identity != null) contactIdByIdentity[identity]?.let { return it }
        val number = normalizedPhoneNumber?.trim()?.ifEmpty { null }
        if (number != null) contactIdByNumber[number]?.let { return it }
        return null
    }

    companion object {
        val EMPTY = ConversationLinks(emptyMap(), emptyMap())

        /**
         * @param directoryMatches normalised phone number to Threema identity, exactly as the
         *   directory server answered — this is the only source an identity link may come from.
         * @param normalizedNumberOf how a contact's telephone number is written for matching;
         *   passed in rather than computed here so this stays testable off-device.
         * @param everyNumber normalised number to contact id, for **all** of the address book's
         *   numbers rather than the one [Contact] can hold. A person with a mobile and two
         *   office lines is one entry here per line, and without them a message on the line that
         *   is not their [Contact.phoneNumber] links to nobody — while the SMS import, which asks
         *   the system by number and so finds every one of them, links it to the contact. The
         *   same message then existed under two keys and she saw the same thread twice. Found on
         *   the phone, on a contact with three numbers.
         */
        fun from(
            contacts: List<Contact>,
            directoryMatches: Map<String, String>,
            normalizedNumberOf: (Contact) -> String?,
            everyNumber: List<Pair<String, String>> = emptyList()
        ): ConversationLinks {
            if (contacts.isEmpty()) return EMPTY
            val byIdentity = HashMap<String, String>()
            val byNumber = HashMap<String, String>()

            contacts.forEach { contact ->
                val contactId = contact.id.trim().ifEmpty { return@forEach }
                val number = normalizedNumberOf(contact) ?: return@forEach
                // First contact wins, so two address book entries sharing a number do not take
                // turns owning the conversation as the list is re-read.
                byNumber.putIfAbsent(number, contactId)
                val identity = directoryMatches[number]?.trim()?.uppercase()?.ifEmpty { null }
                if (identity != null) byIdentity.putIfAbsent(identity, contactId)
            }

            // The rest of each person's numbers, after the one [Contact] has room for. Added
            // second so the primary keeps its claim on a number two people share.
            everyNumber.forEach { (number, contactId) ->
                val cleanNumber = number.trim().ifEmpty { return@forEach }
                val cleanContactId = contactId.trim().ifEmpty { return@forEach }
                byNumber.putIfAbsent(cleanNumber, cleanContactId)
            }

            return ConversationLinks(byIdentity, byNumber)
        }
    }
}
