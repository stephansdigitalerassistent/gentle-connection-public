package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.data.db.ThreemaContactEntity
import ch.heuscher.gentleconnect.model.Contact

/**
 * Gives the people in her address book the Threema ID that reaches them.
 *
 * Nothing else in the app supplies one. `ContactsContract` has no such column, and no partner
 * record has ever existed for this device — which is why [MessageRouter] fell through to SMS for
 * every single person until SMS was unlinked. Her own imported Threema backup is the only
 * offline source that maps a name to an identity, and this is where the two meet.
 *
 * ## Why the matching is deliberately timid
 *
 * The two sides are matched on name, because a Threema backup carries no telephone numbers. Name
 * matching is guesswork, and the two ways it can be wrong are not remotely equal:
 *
 * - **No match**: she is told she cannot write to that person. Visible, harmless, correctable.
 * - **Wrong match**: her message goes to a stranger under her name, and neither of them ever
 *   finds out why.
 *
 * So a match must be exact after normalisation *and* unambiguous on both sides. One "Anna
 * Meier" in the address book and two in the backup produces nothing at all. Fuzzy matching,
 * first-name-only matching and nickname matching are all deliberately absent: every one of them
 * trades a visible failure for an invisible one.
 */
object ThreemaContactMatcher {

    /**
     * @return [contacts] with [Contact.threemaId] filled in wherever exactly one imported Threema
     *   contact and exactly one address-book entry share a name. Contacts that already carry an
     *   ID are left alone; anything uncertain is left null.
     */
    fun attachThreemaIds(
        contacts: List<Contact>,
        threemaContacts: List<ThreemaContactEntity>
    ): List<Contact> {
        if (contacts.isEmpty() || threemaContacts.isEmpty()) return contacts

        val unambiguousThreemaIds = threemaContacts
            .mapNotNull { entity ->
                val key = normalize(entity.displayName)
                // displayName falls back to the raw identity when a backup row has no name at
                // all. Matching on that would only ever fire if her address book held someone
                // literally called "ECHO1001", so drop those rather than carry them.
                if (key.isEmpty() || key == normalize(entity.identity)) null else key to entity.identity
            }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.distinct().size == 1 }
            .mapValues { (_, identities) -> identities.first() }

        if (unambiguousThreemaIds.isEmpty()) return contacts

        val namesAppearingOnce = contacts
            .groupingBy { normalize(it.name) }
            .eachCount()
            .filterValues { it == 1 }
            .keys

        return contacts.map { contact ->
            if (!contact.threemaId.isNullOrBlank()) return@map contact
            val key = normalize(contact.name)
            if (key !in namesAppearingOnce) return@map contact
            val identity = unambiguousThreemaIds[key] ?: return@map contact
            contact.copy(threemaId = identity)
        }
    }

    private val WHITESPACE_RUN = Regex("\\s+")

    /** Case and spacing are how the same person is written differently in two address books. */
    private fun normalize(name: String): String =
        name.trim().replace(WHITESPACE_RUN, " ").lowercase()
}
