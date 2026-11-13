package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppNumber
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.Recipient

/** The photograph, film or voice message on a message, and which of the three it is. */
data class ForwardableMedia(
    /** Where it is on this phone. */
    val path: String,
    val kind: Kind
) {
    enum class Kind { PHOTO, VIDEO, VOICE }
}

/**
 * Sending on something somebody sent her.
 *
 * Everything here is arithmetic over what she already has — her messages and the people she can
 * write to — so that the screen and the sending can be argued about separately from the rules.
 */
object Forwarding {

    /**
     * How many people are offered: everybody she has written to, in the order she last wrote to
     * them.
     *
     * It was five while there was room for five. The list scrolls, and cutting it meant somebody
     * she writes to every fortnight was simply absent with no way to reach her — a shorter list
     * she cannot use is not simpler than a longer one she can. The order does the work the cut
     * used to: whoever she wrote to last is at the top, under her thumb.
     */
    const val EVERYBODY: Int = Int.MAX_VALUE

    /**
     * What there is to send on, or null when the message is only words.
     *
     * A film is sent as the film, never as its still: the still exists because a film takes time
     * to arrive, and forwarding the picture of a film as though it were the film would be a quiet
     * lie. A message carrying both is therefore a film.
     */
    fun mediaOf(message: Message): ForwardableMedia? = when {
        !message.videoPath.isNullOrBlank() ->
            ForwardableMedia(message.videoPath, ForwardableMedia.Kind.VIDEO)
        !message.audioPath.isNullOrBlank() ->
            ForwardableMedia(message.audioPath, ForwardableMedia.Kind.VOICE)
        !message.photoPath.isNullOrBlank() ->
            ForwardableMedia(message.photoPath, ForwardableMedia.Kind.PHOTO)
        else -> null
    }

    /**
     * Whether a photograph can be sent to this person at all.
     *
     * Threema and WhatsApp both can now: each bridge puts the bytes up and the message it sends
     * names them. An SMS cannot and never could — this app does not do MMS — so somebody she can
     * only reach by telephone number is not offered, which is the rule everywhere on her screen:
     * something she cannot do is not shown, rather than shown and then failing after she has
     * chosen it.
     *
     * A group is not offered either, and for a heavier reason: a group message is the same
     * message encrypted separately for every member, and a photograph fanned out to a membership
     * we are not certain of reaches some of them and looks exactly like reaching all of them.
     * See `GroupReplyPolicy`, which exists for that.
     */
    fun canCarryMedia(recipient: Recipient): Boolean =
        recipient.groupIdentity == null &&
            (!recipient.threemaId.isNullOrBlank() || !recipient.whatsappId.isNullOrBlank())

    /**
     * The people she wrote to last, newest first.
     *
     * Built from her own outgoing messages, and from nothing else. This is the list of who she
     * last sent something to — which is what it says on the screen — so the messages are the
     * evidence and the address book is only asked for a face and a better spelling of the name.
     *
     * It used to be the other way round: the address book filtered by the messages. That list
     * came out **empty on a phone with a hundred and fifty sent messages on it**, and the button
     * to send a photograph on therefore appeared nowhere at all. Two things had to line up for a
     * person to survive it and neither did. A favourite is built with a telephone number and no
     * WhatsApp id, so `canCarryMedia` threw her out before anything was matched; and the name
     * WhatsApp gives a person — `Laura "Lala Lulu" Hartmann` — is not the name in her address
     * book, so the fallback on names missed her too. Whom she wrote to is not a fact the address
     * book knows and the messages do not; it is the other way round.
     *
     * Only Threema and WhatsApp, because only those two can carry an attachment. An SMS cannot,
     * and offering a person she can only text is offering something that fails after she has
     * chosen it.
     */
    fun recentRecipients(
        recipients: List<Recipient>,
        messages: List<Message>,
        limit: Int = EVERYBODY
    ): List<Recipient> {
        val lastWrittenTo = LinkedHashMap<String, Pair<Recipient, Long>>()

        messages.asSequence()
            .filter { it.direction == MessageDirection.OUTGOING }
            // A message to several people says nothing about who to send a photograph to, and a
            // status is posted at everybody and answered by nobody. See `GroupReplyPolicy`.
            .filter { it.groupIdentity == null }
            .forEach { message ->
                val target = targetOf(message, recipients) ?: return@forEach
                val key = addressOf(target)
                val previous = lastWrittenTo[key]
                // Absent, rather than absent-or-zero: a message with no timestamp at all is still
                // a message she sent, and treating "never" and "at the epoch" as the same number
                // dropped that person off the list entirely.
                if (previous == null || message.timestamp > previous.second) {
                    lastWrittenTo[key] = target to message.timestamp
                }
            }

        return lastWrittenTo.values
            .sortedByDescending { (_, at) -> at }
            .take(limit)
            .map { (recipient, _) -> recipient }
    }

    /**
     * The person one of her messages went to, as somewhere a photograph can be sent.
     *
     * The address is the message's own, so the photograph goes back down the road the message
     * went down. Her address book is consulted only to dress it: the face she recognises, and
     * the name she gave them rather than the one they gave themselves.
     */
    private fun targetOf(message: Message, recipients: List<Recipient>): Recipient? {
        val address = message.senderId?.trim().orEmpty()
        if (address.isEmpty() || address.startsWith(STATUS_ADDRESS)) return null
        if (WhatsAppNumber.isGroup(address)) return null

        val known = recipients.firstOrNull { knows(it, address, message.senderName) }
        val name = known?.name?.takeIf { it.isNotBlank() } ?: message.senderName

        return when (message.transport) {
            BridgeTransport.THREEMA -> Recipient(
                name = name,
                photoUri = known?.photoUri,
                threemaId = address,
                phoneNumber = known?.phoneNumber
            )
            BridgeTransport.WHATSAPP -> {
                // Stored as a bare number here and as a JID there, depending on which end wrote
                // the row. Both forms are the same person and both must end up as one entry.
                val number = WhatsAppNumber.numberFromJid(address)
                    ?: WhatsAppNumber.digitsOf(address)
                    ?: return null
                Recipient(
                    name = name,
                    photoUri = known?.photoUri,
                    whatsappId = number,
                    phoneNumber = known?.phoneNumber ?: number
                )
            }
            // An SMS. Nothing this app can attach a photograph to — it does not do MMS.
            else -> null
        }
    }

    /** Whether this is the person that message went to, by any of the ways of naming them. */
    private fun knows(recipient: Recipient, address: String, name: String): Boolean {
        val threema = recipient.threemaId?.trim()
        if (!threema.isNullOrEmpty() && threema.equals(address, ignoreCase = true)) return true
        if (WhatsAppNumber.sameSubscriber(recipient.whatsappId, address)) return true
        if (WhatsAppNumber.sameSubscriber(recipient.phoneNumber, address)) return true
        return name.isNotBlank() && name.trim().equals(recipient.name.trim(), ignoreCase = true)
    }

    /** One entry per person, whichever messenger the message went over. */
    private fun addressOf(recipient: Recipient): String =
        (recipient.threemaId ?: recipient.whatsappId).orEmpty().trim().lowercase()

    /** WhatsApp's own address for a status, which is not a person and not somewhere to send to. */
    private const val STATUS_ADDRESS = "status@"
}
