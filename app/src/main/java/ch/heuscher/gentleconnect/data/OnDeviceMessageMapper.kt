package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.data.threema.ThreemaIncomingMessage
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel

import ch.heuscher.gentleconnect.model.QuoteMarker

/**
 * Turns what the embedded Threema client hands up into the one message shape her screen knows.
 *
 * The counterpart to [BridgeMapper], and pure for the same reason: this is where a real message
 * from a real person either becomes visible or silently does not.
 *
 * [Message.senderName] is left as the raw Threema ID on purpose. Resolving `ECHO1001` to "Anna"
 * happens one layer up, against the contacts imported from her Threema backup, exactly as it
 * already does for bridge messages — putting it here would mean two places that decide what she
 * is called.
 */
object OnDeviceMessageMapper {

    /** Prefix keeps ids distinct from bridge and SMS ids, which are numbered independently. */
    /**
     * What a Threema message is filed under.
     *
     * Not private any more: an attachment arrives after the message it belongs to and has to be
     * matched to the same row, so whoever does the matching needs the same prefix rather than a
     * second copy of the string.
     */
    const val ID_PREFIX = "threema_"

    fun fromThreema(incoming: ThreemaIncomingMessage, receivedAt: Long = 0L): Message? {
        val sender = incoming.sender.trim().uppercase()
        if (sender.isEmpty()) return null
        if (incoming.text.isBlank()) return null

        val parsed = QuoteMarker.parse(incoming.text)
        // If stripping the quote marker leaves the body blank, fall back to the original unparsed
        // text rather than dropping the message — her daughter's message vanishing with no trace
        // is the worst outcome this app has.
        val text = if (parsed.body.isBlank()) incoming.text else parsed.body
        val quotedId = if (parsed.body.isBlank()) null else parsed.wireId?.let { ID_PREFIX + it }

        return Message(
            id = ID_PREFIX + incoming.messageId,
            senderName = sender,
            senderId = sender,
            text = text,
            timestamp = incoming.timestamp,
            // Nothing dispatches a reply by package any more; the channel decides that now.
            sourcePackage = "",
            transport = BridgeTransport.THREEMA,
            channel = MessageChannel.ON_DEVICE,
            // Carried through, not decoration: this is what stops a reply to a group of eight
            // going privately to whichever one of them happened to write.
            groupIdentity = GroupIdentity.of(incoming.groupCreator, incoming.groupId),
            quotedId = quotedId,
            receivedAt = receivedAt
        )
    }
}
