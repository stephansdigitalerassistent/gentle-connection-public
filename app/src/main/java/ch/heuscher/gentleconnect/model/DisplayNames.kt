package ch.heuscher.gentleconnect.model

import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber

/**
 * The two ways her address book can be looked up: by Threema identity, and by telephone number.
 *
 * One object because both are wanted at the same moment and by the same code, and because the
 * flows that carry them are already as wide as [kotlinx.coroutines.flow.combine] goes.
 */
data class ContactNames(
    val byIdentity: Map<String, String> = emptyMap(),
    val byNumber: Map<String, String> = emptyMap()
) {
    val isEmpty: Boolean get() = byIdentity.isEmpty() && byNumber.isEmpty()
}

/**
 * The names she knows people by, keyed on their Threema identity or on their telephone number.
 *
 * Her address book first and the imported Threema backup second — written in that order so the
 * address book wins, because that is the name she chose for that person. The raw identity is the
 * last resort, and it is a bad one: `ECHO1001` is not a person to her.
 *
 * Pure, and shared rather than copied, because two places now need it: the screens, which resolve
 * every message they draw, and the arrival pipeline, which resolves the one message it is about to
 * announce out loud. A notice that says ECHO1001 has written is a notice about nobody.
 */
object DisplayNames {

    /**
     * @param imported identity to display name, as the Threema backup import stored them.
     */
    /**
     * Only the names *she* gave people, keyed on the Threema identity they were confirmed to.
     *
     * Separate from [byIdentity], which falls back to the backup's own names, because these two
     * answer different questions. This one is "what does she call this person", and the answer
     * outranks whatever name a message arrived carrying: her father is filed in the backup as
     * "Johann 'Papa Opa' Hartmann" and she has always called him Dad. A contact only has an
     * identity here when the Threema directory confirmed it from the telephone number in her
     * address book — see ThreemaNumberMatcher — so this is never a guess at who somebody is.
     */
    fun byContact(contacts: List<Contact>): Map<String, String> {
        val names = HashMap<String, String>()
        contacts.forEach { contact ->
            val identity = contact.threemaId?.trim()?.uppercase()?.ifEmpty { null } ?: return@forEach
            val name = contact.name.trim().ifEmpty { null } ?: return@forEach
            names[identity] = name
        }
        return names
    }

    /**
     * What her address book calls each telephone number, keyed so a message can find it.
     *
     * The counterpart of [byContact], for everybody who is not on Threema. Without it a name is
     * whatever was known at the moment the message was stored and is never looked at again: a
     * WhatsApp message that arrived before the address book had been read kept the name the
     * sender chose for themselves — one contact of hers is called "." on WhatsApp — or, with no
     * name at all in it, the bare digits. Both were frozen into the database and shown for good.
     */
    fun byNumber(contacts: List<Contact>): Map<String, String> {
        val names = HashMap<String, String>()
        contacts.forEach { contact ->
            val key = WhatsAppNumber.subscriberKey(contact.phoneNumber) ?: return@forEach
            val name = contact.name.trim().ifEmpty { null } ?: return@forEach
            // The first wins: two entries for one number are one person written down twice, and
            // the list arrives in the order the phone book shows.
            if (!names.containsKey(key)) names[key] = name
        }
        return names
    }

    fun byIdentity(
        contacts: List<Contact>,
        imported: List<Pair<String, String>>
    ): Map<String, String> {
        val names = HashMap<String, String>()
        imported.forEach { (rawIdentity, rawName) ->
            val identity = rawIdentity.trim().uppercase().ifEmpty { null } ?: return@forEach
            val name = rawName.trim().ifEmpty { null } ?: return@forEach
            names[identity] = name
        }
        contacts.forEach { contact ->
            val identity = contact.threemaId?.trim()?.uppercase()?.ifEmpty { null } ?: return@forEach
            val name = contact.name.trim().ifEmpty { null } ?: return@forEach
            names[identity] = name
        }
        return names
    }

    /**
     * What to call the sender of [message] out loud, given [names].
     *
     * The person who wrote comes first, exactly as it does on the card at the top of her home
     * screen — see [Nudge.MessageNudge]. The two are the same event and must not disagree: a
     * telephone that says one name aloud and shows another on the screen is asking her to work out
     * which of them is right.
     *
     * The name the message already carries is preferred where it is a real one, then the name
     * behind the identity, then the group it was sent to, and only then whatever was there — which
     * for a message that arrived over Threema is the identity itself, and is the case this exists
     * to avoid.
     */
    fun forAnnouncement(message: Message, names: Map<String, String>): String {
        if (!PersonName.isUnresolved(message.senderName, message.senderId)) return message.senderName
        val identity = message.senderId?.trim()?.uppercase()
        if (identity != null) {
            names[identity]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        message.groupName?.takeIf { it.isNotBlank() }?.let { return it }
        return message.senderName
    }
}
