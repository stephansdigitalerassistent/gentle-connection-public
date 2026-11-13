package ch.heuscher.gentleconnect.model

import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber

/**
 * One person out of several, and every way of reaching them on their own.
 *
 * The list of everybody in a group used to be a list and nothing more — faces and names, with a
 * button that closed it. But a group is where she most often wants *one* person: somebody asked
 * her something in the family conversation and she wants to answer them, by voice, without
 * writing to all eight. Everywhere else in this app a face is something you press to ring
 * somebody ([ch.heuscher.gentleconnect.ui.components.ContactRow]), and the member list was the
 * one place where it was not, which teaches two meanings for the same gesture at exactly the
 * moment she is looking at somebody she wants to reach.
 *
 * So a member can be rung and can be written to, and this is what says whether either is
 * possible — before the button is drawn, never after she has pressed it. That order is the whole
 * point: a name she can press that then fails is worse than a name she cannot press.
 *
 * Pure, and in `model/`, so the rules below are proved in a test rather than on her phone.
 */
data class GroupMemberRoute(
    /** The face this was made from, so the caller can match it back to the row she pressed. */
    val face: GroupFace,
    /** What to call them on her screen; may still be unreadable, which [personName] handles. */
    val name: String,
    val photoUri: String?,
    val phoneNumber: String?,
    val threemaId: String?,
    /** Their WhatsApp number as bare digits — what [Recipient.whatsappId] is everywhere else. */
    val whatsappId: String?
) {

    /**
     * Whether there is anything to ring.
     *
     * A Threema identity counts on its own: [ch.heuscher.gentleconnect.data.CallPlacer] carries
     * a call in the app itself, which is how she rings people abroad, so somebody with an
     * identity and no telephone number is still somebody she can ring.
     */
    val canRing: Boolean
        get() = !phoneNumber.isNullOrBlank() || !threemaId.isNullOrBlank()

    /** The same person as somebody she writes to, for [MessageRouter] to pick a route for. */
    val recipient: Recipient
        get() = Recipient(
            name = name,
            photoUri = photoUri,
            threemaId = threemaId,
            whatsappId = whatsappId,
            phoneNumber = phoneNumber
        )

    /** Whether anything at all can carry a message to them — the router's answer, not ours. */
    val canWrite: Boolean
        get() = recipient.isReachable

    companion object {

        /**
         * How to reach one member of a group, or null when there is no way to reach them.
         *
         * Null is common and is not a fault. A [GroupFace] is drawn from whatever the messenger
         * said, and three kinds of face carry no address at all:
         *
         * - somebody known only by the name they wrote under, whose key is `name:…` — see
         *   [GroupFaces.speakerKey];
         * - a WhatsApp member behind a hidden number, whose key stays the raw `…@lid` address
         *   because nothing could turn it into a number — see
         *   [ch.heuscher.gentleconnect.data.whatsapp.WhatsAppGroups.faceKey];
         * - anybody at all when the conversation is on a transport this does not know.
         *
         * Each of those is a real person in the group and stays in the list with their face and
         * their name. They simply cannot be pressed, which is the honest outcome: the app has
         * their name and not an address, and a button that says otherwise is a lie she would
         * only discover by pressing it.
         *
         * @param transport which messenger the conversation is on, which is what says how to
         *   read the key: a Threema face is filed under the identity, a WhatsApp face under the
         *   bare digits of the telephone number.
         * @param contacts her address book, to put a name, a photograph and — for a Threema
         *   member — a telephone number to the address. A member she has no contact for is still
         *   reachable; she just has only the one route to them.
         */
        fun of(
            face: GroupFace,
            transport: BridgeTransport,
            contacts: List<Contact>
        ): GroupMemberRoute? {
            val key = face.key.trim()
            if (key.isEmpty()) return null
            // A name is not an address, and an address with an `@` in it is one this app could
            // not resolve — a hidden-number LID. Neither can be rung or written to.
            if (key.startsWith(NAME_KEY_PREFIX, ignoreCase = true)) return null
            if (PersonName.looksLikeAddress(key)) return null

            return when (transport) {
                BridgeTransport.THREEMA -> threemaMember(face, key.uppercase(), contacts)
                BridgeTransport.WHATSAPP -> whatsAppMember(face, key, contacts)
                else -> null
            }
        }

        /**
         * A Threema member: the key is their identity, and her address book may hold the rest.
         *
         * The telephone number matters more here than it looks. Without it every call to a
         * Threema member would be an in-app call, and the rule on this phone is the telephone
         * first whenever there is voice service — see `CallRouter`. Her contacts carry the
         * identity already, matched by `ThreemaContactMatcher`, so the number is one lookup away.
         */
        private fun threemaMember(
            face: GroupFace,
            identity: String,
            contacts: List<Contact>
        ): GroupMemberRoute? {
            if (!isIdentity(identity)) return null
            val contact = contacts.firstOrNull {
                it.threemaId?.trim()?.uppercase() == identity
            }
            return GroupMemberRoute(
                face = face,
                name = nameFor(face, contact),
                photoUri = face.photoUri ?: contact?.photoUri,
                phoneNumber = contact?.phoneNumber?.trim()?.ifEmpty { null },
                threemaId = identity,
                whatsappId = null
            )
        }

        /**
         * A WhatsApp member: the key is the bare digits of their number.
         *
         * Matched against her address book by [WhatsAppNumber.sameSubscriber], which is the same
         * comparison the message list already makes — it allows for a number written the
         * national way in her contacts and sent internationally by WhatsApp, and refuses a match
         * on too few digits, because the tail of one number appears in plenty of others.
         *
         * Her contact's number wins when there is one, written the way she wrote it. Otherwise
         * the digits are dialled with a `+`: WhatsApp sends them international, and a number is
         * the only thing this app knows about somebody it has no contact for.
         *
         * A Threema identity from the matching contact is carried along on purpose. She is
         * writing to a person, not to a service, and [MessageRouter] prefers this app's own
         * transport — the same merge [writableRecipients] makes for everybody else.
         */
        private fun whatsAppMember(
            face: GroupFace,
            key: String,
            contacts: List<Contact>
        ): GroupMemberRoute? {
            val digits = WhatsAppNumber.digitsOf(key) ?: return null
            val contact = contacts.firstOrNull {
                WhatsAppNumber.sameSubscriber(it.phoneNumber, digits)
            }
            val number = contact?.phoneNumber?.trim()?.ifEmpty { null }
                ?: if (digits.startsWith("0")) digits else "+$digits"
            return GroupMemberRoute(
                face = face,
                name = nameFor(face, contact),
                photoUri = face.photoUri ?: contact?.photoUri,
                phoneNumber = number,
                threemaId = contact?.threemaId?.trim()?.ifEmpty { null },
                whatsappId = digits
            )
        }

        /**
         * What to call them, in the order the rest of the app calls people things.
         *
         * The name on the face first — that is what their messages arrive under, which for
         * somebody not in her address book is the name they chose for themselves. Her contact
         * second. And if neither is readable, the face's name as it stands, so that the caller
         * can put it through `personName` and draw "Someone" like everywhere else.
         */
        private fun nameFor(face: GroupFace, contact: Contact?): String =
            PersonName.forHer(face.name)
                ?: contact?.name?.let { PersonName.forHer(it) }
                ?: face.name

        /**
         * Whether this key is shaped like a Threema identity.
         *
         * Eight characters of uppercase letters and digits. Deliberately *not*
         * [PersonName.looksLikeIdentity], which additionally insists on a digit: that rule exists
         * to keep KAROLINA from being mistaken for an identity when it is a name, and here the
         * key is never a name — it is what the member list or a message's sender id said. An
         * all-letter identity is rare and real, and refusing it would take the buttons away from
         * whoever has one.
         */
        private fun isIdentity(key: String): Boolean =
            key.length == IDENTITY_LENGTH && key.all { it in 'A'..'Z' || it in '0'..'9' }

        /** What [GroupFaces.speakerKey] puts in front of somebody it knows only by name. */
        private const val NAME_KEY_PREFIX = "name:"

        private const val IDENTITY_LENGTH = 8
    }
}
