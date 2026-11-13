package ch.heuscher.gentleconnect.model

/**
 * Which face belongs on a message row.
 *
 * Every other list in the app draws the sender's photograph — her favourites, her post, the
 * pictures people put up — and the message list was the one that did not. [Message] carries no
 * photograph of its own (an SMS is a number and some text), so the row was drawn with a photo of
 * `null` and fell back to initials for everybody, including the people whose portraits were
 * sitting in her address book all along. Two letters in a circle is what this app shows when
 * there is genuinely no face to show; showing it when there is one makes the fallback a lie, and
 * she reads the picture before she reads the name.
 *
 * Resolving belongs here, once, rather than at each of the two screens that draw a message row:
 * the home list and the Message tab show the same message, and a face that appeared on one of
 * them and not the other would read as two different people.
 *
 * Pure, and given its lookups rather than reaching for them, so the ordering below can be proved
 * in CI without an address book.
 */
object MessagePortrait {

    /**
     * A photograph for each of [messages] that has one, keyed by [Message.id].
     *
     * Messages with no face resolved are absent rather than mapped to null: the row already knows
     * what to draw when nothing comes back, and an entry saying "no photograph" is indistinguishable
     * from one that was never looked up.
     *
     * @param photoByPartnerId messenger address (Threema ID, WhatsApp number) to photograph,
     *   keyed uppercase — see [Partner].
     * @param photoByName person's name to photograph, keyed lowercase, for the people she knows
     *   whose message carries no address we can match.
     * @param byPhone her address book, asked by telephone number. Expensive; called at most once
     *   per distinct number, and the caller is expected to remember the answers.
     * @param byEmail the same, asked by e-mail address.
     */
    fun photosFor(
        messages: List<Message>,
        photoByPartnerId: Map<String, String> = emptyMap(),
        photoByName: Map<String, String> = emptyMap(),
        photoByGroup: Map<GroupIdentity, String> = emptyMap(),
        byPhone: (String) -> String? = { null },
        byEmail: (String) -> String? = { null }
    ): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        messages.forEach { message ->
            val photo = photoFor(message, photoByPartnerId, photoByName, photoByGroup, byPhone, byEmail)
            if (photo != null) found[message.id] = photo
        }
        return found
    }

    /**
     * The face for one message, or null when there is nobody to show.
     *
     * Null is a real answer twice over, and both are deliberate:
     *
     * A group row names the group, not whoever spoke last. Putting her nephew's face on a row
     * labelled "Familie" would say the family group was him, and the next message would change
     * the portrait without changing the conversation. So a group row draws the group's *own*
     * picture when there is one — [photoByGroup], which a Threema backup carries and the group
     * setup messages do not — and nothing at all when there is not. What it must never draw is a
     * person.
     *
     * A company has no face — see [SenderKind]. The row draws a mark instead, which is the whole
     * point of the distinction, and a portrait resolved here would never be used anyway.
     *
     * A message of hers is emphatically *not* the third case, however much it looks like one. On
     * an outgoing message [Message.senderName] and [Message.senderAddress] name who she wrote
     * *to* — see LocalMessageStore, which files both halves of a conversation under the
     * counterpart on purpose. Skipping her own messages here would therefore have taken the face
     * off exactly the rows that say "Your message: 👍", which are the conversations she is most
     * likely to be in the middle of.
     */
    fun photoFor(
        message: Message,
        photoByPartnerId: Map<String, String> = emptyMap(),
        photoByName: Map<String, String> = emptyMap(),
        photoByGroup: Map<GroupIdentity, String> = emptyMap(),
        byPhone: (String) -> String? = { null },
        byEmail: (String) -> String? = { null }
    ): String? {
        if (message.isGroupMessage) {
            val group = message.groupIdentity ?: return null
            return photoByGroup[group]?.takeIf { it.isNotBlank() }
        }
        if (message.senderKind != SenderKind.PERSON) return null
        return personPhoto(
            address = message.senderAddress,
            senderId = message.senderId,
            name = message.senderName,
            photoByPartnerId = photoByPartnerId,
            photoByName = photoByName,
            byPhone = byPhone,
            byEmail = byEmail
        )
    }

    /**
     * The face of one person, found by whatever is known about them: an address, a messenger
     * identity, a name. The person half of [photoFor], on its own so that the people in a group —
     * some of whom have never written, and so have no message to ask about — are found by exactly
     * the same rules as the people who have.
     */
    fun personPhoto(
        address: String?,
        senderId: String?,
        name: String,
        photoByPartnerId: Map<String, String> = emptyMap(),
        photoByName: Map<String, String> = emptyMap(),
        byPhone: (String) -> String? = { null },
        byEmail: (String) -> String? = { null }
    ): String? {
        // Her address book first. It is the caregiver's own answer to who this person is, it is
        // the photograph every other screen already draws, and behind it sits the messenger
        // avatar we cached — see ContactsRepository.findWhatsAppAvatar.
        val where = address?.trim().orEmpty()
        if (where.isNotEmpty()) {
            val fromBook = if (where.contains('@') && !where.contains("s.whatsapp.net")) {
                byEmail(where)
            } else {
                byPhone(where)
            }
            if (!fromBook.isNullOrBlank()) return fromBook
        }

        // Then whoever the messenger says wrote. A Threema ID is not in her address book unless
        // somebody put it there, and the partner document usually carries a picture.
        val id = senderId?.trim()?.uppercase()
        if (!id.isNullOrEmpty()) {
            photoByPartnerId[id]?.takeIf { it.isNotBlank() }?.let { return it }
        }

        // And last the name, which is the only thing left when a message arrives with neither a
        // number we can match nor an address the messenger knows.
        val key = name.trim().lowercase()
        if (key.isNotEmpty()) {
            photoByName[key]?.takeIf { it.isNotBlank() }?.let { return it }
        }

        return null
    }
}
