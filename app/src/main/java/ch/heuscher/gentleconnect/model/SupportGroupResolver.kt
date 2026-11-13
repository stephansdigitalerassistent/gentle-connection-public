package ch.heuscher.gentleconnect.model

/**
 * The support group as the phone can actually reach it.
 *
 * The portal picks members from her starred favourites and stores only who — a contact id and a
 * name — because the website never sees her address book. Number, photograph and Threema ID come
 * from the phone, so they are the ones she already has, and a spelling fixed in the contacts app
 * reaches the help button without anybody touching the website.
 *
 * **Every one of a person's numbers is considered, not just the one [Contact] happens to carry.**
 * See [ContactPhone] for what that cost the day it did not: a son reachable on Threema, on
 * WhatsApp and by SMS, alerted on none of them, because the first row the contacts provider
 * returned for him was an office landline.
 *
 * Members typed in by hand (the first version of the portal) pass through unchanged.
 *
 * Pure, because CI here runs JVM unit tests only.
 */
object SupportGroupResolver {

    /**
     * @param configured the group as stored in [RemoteSettings.supportGroup], in the caregiver's order.
     * @param contacts her address book, with Threema IDs already attached.
     * @param phonesOf every number this contact has. Empty means the address book has not been
     *   read that way, and then [Contact.phoneNumber] is used alone, exactly as before.
     * @param threemaIdFor the identity the Threema directory returned for a number, or null.
     * @param whatsAppNumberFor the number to reach a contact on WhatsApp, or null when there is no
     *   sign they use it. A guess here would count as delivered and hold back the SMS backup.
     * @return the members that can be reached. A picked contact no longer in her address book is
     *   dropped: without a number there is nothing to send to. While the address book has not
     *   been read yet, a picked member keeps what the portal stored so the button does not vanish.
     */
    fun resolve(
        configured: List<SupportMember>,
        contacts: List<Contact>,
        phonesOf: (Contact) -> List<ContactPhone> = { emptyList() },
        threemaIdFor: (String) -> String? = { null },
        whatsAppNumberFor: (String) -> String? = { null }
    ): List<SupportMember> {
        val byId = contacts.associateBy { it.id }
        return configured
            .mapNotNull { member ->
                val contactId = member.contactId ?: return@mapNotNull member
                if (contacts.isEmpty()) return@mapNotNull member
                val contact = byId[contactId] ?: return@mapNotNull null

                val phones = phonesOf(contact)
                    .filter { it.number.isNotBlank() }
                    .ifEmpty {
                        listOf(ContactPhone(contact.phoneNumber, contact.id, PhoneKind.OTHER))
                            .filter { it.number.isNotBlank() }
                    }
                if (phones.isEmpty() && contact.threemaId.isNullOrBlank()) return@mapNotNull null

                member.copy(
                    name = contact.name.ifBlank { member.name },
                    // The one number for writing to and for her button to dial.
                    phoneNumber = ContactPhones.bestForMessages(phones) ?: contact.phoneNumber,
                    // Whichever of their numbers the directory knows. The identity already on the
                    // contact wins, because it came from the same directory or from her own
                    // Threema backup, and it is not tied to one number at all.
                    threemaId = contact.threemaId?.takeIf { it.isNotBlank() }
                        ?: phones.firstNotNullOfOrNull { threemaIdFor(it.number)?.takeIf { id -> id.isNotBlank() } },
                    // Likewise: somebody is on WhatsApp under one of their numbers or under none.
                    whatsAppNumber = phones.firstNotNullOfOrNull { whatsAppNumberFor(it.number)?.takeIf { n -> n.isNotBlank() } },
                    buttonPhotoUri = contact.photoUri?.takeIf { it.isNotBlank() },
                    // Everything else they can ring from.
                    otherNumbers = phones.map { it.number }
                        .filter { it != ContactPhones.bestForMessages(phones) }
                        .distinct()
                )
            }
            .distinctBy { it.id }
    }
}
