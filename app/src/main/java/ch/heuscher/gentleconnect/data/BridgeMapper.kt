package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.Partner
import ch.heuscher.gentleconnect.model.QuoteMarker

/**
 * Defensive mapper between Firestore document maps and domain models (Message, Partner, Outbox).
 * Free of Android or Firebase dependencies for JVM unit testability.
 */
object BridgeMapper {

    fun mapToMessage(documentId: String, data: Map<String, Any?>?): Message? {
        if (data == null) return null

        val senderNameRaw = (data["senderName"] as? String) ?: ""
        val senderId = (data["senderId"] as? String)?.ifBlank { null }
        val transport = BridgeTransport.fromWire(data["transport"] as? String)
        val rawText = (data["text"] as? String) ?: ""
        // Quote markers embedded in the text (such as Threema's `> quote #...`) are stripped so
        // that raw hexadecimal and marker headers never reach her screen or read-aloud.
        // We strip ONLY and do not set quotedId on this path: a bridge message's id is the
        // Firestore document id, not `threema_<hex>`, so the id spaces do not line up and a
        // quotedId set there could never resolve. We carry quotedSenderName and quotedText
        // directly from the document so that the quote citation card can still be drawn.
        val text = QuoteMarker.parse(rawText).body
        val timestamp = parseAsLong(data["timestamp"], 0L)
        val isRead = (data["isRead"] as? Boolean) ?: false
        val reaction = data["reaction"] as? String
        val quotedSenderName = (data["quotedSenderName"] as? String)?.ifBlank { null }
        val quotedText = (data["quotedText"] as? String)?.ifBlank { null }

        val senderName = when {
            senderNameRaw.isNotBlank() -> senderNameRaw
            senderId != null -> senderId
            else -> "Unbekannte Person"
        }

        return Message(
            id = documentId,
            senderName = senderName,
            senderAddress = senderId,
            contactId = null,
            text = text,
            timestamp = timestamp,
            // sourcePackage only tells the SMS replier whether a message came from the SMS
            // inbox. A bridged message is answered through the outbox, so it names no package.
            sourcePackage = "",
            isRead = isRead,
            reaction = reaction,
            isMissedCall = false,
            senderId = senderId,
            transport = transport,
            channel = MessageChannel.BRIDGE,
            quotedId = null,
            quotedSenderName = quotedSenderName,
            quotedText = quotedText,
            // The Firestore bridge contract carries no receipt clock. We leave receivedAt at 0
            // so that it falls back to timestamp (when it was written). Stamping "now" here
            // when a snapshot arrives would make every old document look freshly received on
            // every launcher restart, waking her for last week's messages.
            receivedAt = 0L
        )
    }

    fun mapToPartner(documentId: String, data: Map<String, Any?>?): Partner? {
        if (data == null) return null

        val transport = BridgeTransport.fromWire(data["transport"] as? String)
        // Document ids are "<transport>:<id>"; fall back to stripping that prefix so a
        // document missing its own `id` field is still usable rather than discarded.
        val id = (data["id"] as? String)?.ifBlank { null }
            ?: documentId.substringAfter(':', documentId)
        val displayNameRaw = (data["displayName"] as? String) ?: ""
        val phoneNumber = data["phoneNumber"] as? String
        val photoUrl = data["photoUrl"] as? String
        val lastContactAt = parseAsLong(data["lastContactAt"], 0L)
        val canInitiate = (data["canInitiate"] as? Boolean) ?: true

        val displayName = if (displayNameRaw.isNotBlank()) displayNameRaw else id

        return Partner(
            id = id,
            transport = transport,
            displayName = displayName,
            phoneNumber = phoneNumber,
            photoUrl = photoUrl,
            lastContactAt = lastContactAt,
            canInitiate = canInitiate
        )
    }

    fun createOutboxReactionMap(
        to: String,
        transport: BridgeTransport,
        emoji: String,
        inReplyToMessageId: String? = null,
        createdAt: Long = System.currentTimeMillis()
    ): Map<String, Any?> {
        return mapOf(
            "to" to to,
            "transport" to transport.wireName,
            "kind" to "reaction",
            "emoji" to emoji,
            "text" to null,
            "inReplyToMessageId" to inReplyToMessageId,
            "createdAt" to createdAt,
            "status" to "pending"
        )
    }

    fun createOutboxTextMap(
        to: String,
        transport: BridgeTransport,
        text: String,
        inReplyToMessageId: String? = null,
        createdAt: Long = System.currentTimeMillis()
    ): Map<String, Any?> {
        return mapOf(
            "to" to to,
            "transport" to transport.wireName,
            "kind" to "text",
            "emoji" to null,
            "text" to text,
            "inReplyToMessageId" to inReplyToMessageId,
            "createdAt" to createdAt,
            "status" to "pending"
        )
    }

    private fun parseAsLong(value: Any?, default: Long): Long {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: default
            else -> default
        }
    }
}
