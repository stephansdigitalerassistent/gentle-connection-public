package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.Contact

/**
 * Attaches Threema IDs to her contacts using the identities the directory server returned for
 * their phone numbers.
 *
 * The counterpart to [ThreemaContactMatcher], and the one to trust when they disagree. That one
 * matches on name, which is guesswork; this one matches on the number the server itself resolved,
 * which is not. So a number match overwrites a name match, and never the other way round.
 */
object ThreemaNumberMatcher {

    /**
     * @param identityByNumber normalised number (digits only) to Threema identity, as returned by
     *   the match endpoint.
     * @param normalizedNumberOf the same normalisation that produced the numbers that were sent —
     *   if the two disagree, nothing matches at all.
     * @param otherNormalizedNumbersOf their remaining numbers, already normalised, tried in order
     *   when the one the contact carries is not in the directory. A [Contact] holds whichever
     *   number the contacts provider returned first, which for somebody with an office line and a
     *   mobile is a coin toss — and losing it meant the family member was on Threema and this app
     *   did not know it. Not a guess: every number here belongs to this person, and the identity
     *   comes from the directory either way.
     */
    fun attachThreemaIds(
        contacts: List<Contact>,
        identityByNumber: Map<String, String>,
        normalizedNumberOf: (Contact) -> String?,
        otherNormalizedNumbersOf: (Contact) -> List<String> = { emptyList() }
    ): List<Contact> {
        if (contacts.isEmpty() || identityByNumber.isEmpty()) return contacts

        return contacts.map { contact ->
            val identity = normalizedNumberOf(contact)?.let { identityByNumber[it] }
                ?: otherNormalizedNumbersOf(contact).firstNotNullOfOrNull { identityByNumber[it] }
                ?: return@map contact
            if (contact.threemaId == identity) contact else contact.copy(threemaId = identity)
        }
    }
}
