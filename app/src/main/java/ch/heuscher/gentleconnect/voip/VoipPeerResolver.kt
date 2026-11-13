package ch.heuscher.gentleconnect.voip

import android.content.Context
import ch.heuscher.gentleconnect.data.CallLogRepository
import ch.heuscher.gentleconnect.data.ContactsRepository
import ch.heuscher.gentleconnect.data.db.AppDatabase
import ch.heuscher.gentleconnect.data.db.ThreemaContactEntity
import ch.heuscher.gentleconnect.model.PersonName

/**
 * Resolved details for a VoIP call peer.
 *
 * @param displayName The name to show on screen (or raw identity as last resort).
 * @param photoUri Photo URI (content://, file://, or null).
 * @param isKnownContact Whether the peer was found in address book or imported contacts.
 */
data class ResolvedVoipPeer(
    val displayName: String,
    val photoUri: String?,
    val isKnownContact: Boolean
)

/**
 * Resolves contact details for a Threema VoIP call peer.
 *
 * Resolution priority:
 * 1. Address-book contact: ThreemaMatchDao maps identity -> phone number, and ContactsRepository /
 *    CallLogRepository resolves that number to a name and real photoUri.
 * 2. Imported Threema contact: ThreemaContactDao.getContactByIdentity(identity) -> displayName and photoPath.
 * 3. Raw identity fallback (marked as unknown contact).
 */
object VoipPeerResolver {

    suspend fun resolvePeer(context: Context, identity: String): ResolvedVoipPeer {
        val database = AppDatabase.getInstance(context)

        // 1. Address-book contact match
        val match = runCatching { database.threemaMatchDao().getMatchByIdentity(identity) }.getOrNull()
        var addressBookContact: Pair<String, String?>? = null
        if (match != null && match.phoneNumber.isNotBlank()) {
            val contact = runCatching {
                ContactsRepository(context).findContactByPhoneNumber(match.phoneNumber)
            }.getOrNull()
            if (contact != null && contact.name.isNotBlank()) {
                addressBookContact = contact.name to contact.photoUri
            } else {
                val caller = runCatching {
                    CallLogRepository(context).lookupContact(match.phoneNumber)
                }.getOrNull()
                if (caller != null && caller.name.isNotBlank()) {
                    addressBookContact = caller.name to caller.photoUri
                }
            }
        }

        // 2. Imported Threema contact match
        val importedContact = runCatching {
            database.threemaContactDao().getContactByIdentity(identity)
        }.getOrNull()

        // 3. Pure resolution logic
        return resolve(addressBookContact, importedContact, identity)
    }

    /**
     * Pure resolution function over (address-book match, imported contact, identity).
     * Has zero Android imports and is directly unit-testable on JVM.
     */
    fun resolve(
        addressBookMatch: Pair<String, String?>?,
        importedContact: ThreemaContactEntity?,
        identity: String
    ): ResolvedVoipPeer {
        // `?: fall through` rather than `?: theRawName`. PersonName.forHer returns null for text
        // that is not a name — an identity, an address — and handing that back anyway would put
        // exactly the string this resolver exists to keep off her screen onto it, labelled as
        // somebody she knows.
        if (addressBookMatch != null) {
            val name = PersonName.forHer(addressBookMatch.first)
            if (name != null) {
                return ResolvedVoipPeer(
                    displayName = name,
                    photoUri = addressBookMatch.second,
                    isKnownContact = true
                )
            }
        }

        // ThreemaContactEntity.displayName is a getter that falls back to the identity when the
        // contact carries no name at all, so it is never blank and "not blank" answers nothing.
        // A row that only knows an ID is not a person we can name.
        if (importedContact != null) {
            val name = importedContact.displayName
                .takeIf { !it.equals(identity, ignoreCase = true) }
                ?.let { PersonName.forHer(it) }
            if (name != null) {
                val photoUri = importedContact.photoPath?.let { path ->
                    if (path.startsWith("content:") || path.startsWith("file:")) path else "file://$path"
                }
                return ResolvedVoipPeer(
                    displayName = name,
                    photoUri = photoUri,
                    isKnownContact = true
                )
            }
        }

        return ResolvedVoipPeer(
            displayName = identity,
            photoUri = null,
            isKnownContact = false
        )
    }
}
