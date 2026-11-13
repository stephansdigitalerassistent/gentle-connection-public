package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.data.db.StoredMessageEntity
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.SendState

/**
 * Between what is on disk and what her screen knows.
 *
 * Enums are stored by name rather than by ordinal, so that adding a value later cannot silently
 * renumber every row already written — an outgoing message that quietly became an incoming one
 * would put her own words on her daughter's side of the conversation. Anything unrecognised on the
 * way back reads as the safe default rather than throwing: a row we cannot fully understand is
 * still a message she wrote, and losing it is worse than losing one of its attributes.
 */
object StoredMessageMapper {

    fun toEntity(message: Message, conversationId: String): StoredMessageEntity =
        StoredMessageEntity(
            id = message.id,
            conversationId = conversationId,
            direction = message.direction.name,
            counterpartId = message.senderId ?: message.senderAddress,
            counterpartName = message.senderName,
            text = message.text,
            timestamp = message.timestamp,
            isRead = message.isRead,
            sendState = message.sendState?.name,
            transport = message.transport.name,
            channel = message.channel.name,
            groupCreator = message.groupIdentity?.creatorIdentity,
            groupId = message.groupIdentity?.groupId,
            photoPath = message.photoPath,
            audioPath = message.audioPath,
            videoPath = message.videoPath,
            reaction = message.reaction,
            wireMessageId = message.wireMessageId,
            quotedId = message.quotedId,
            quotedSenderName = message.quotedSenderName,
            quotedText = message.quotedText,
            quotedIsHers = message.quotedIsHers,
            receivedAt = message.receivedAt
        )

    fun toMessage(entity: StoredMessageEntity): Message =
        Message(
            id = entity.id,
            senderName = entity.counterpartName,
            senderId = entity.counterpartId,
            senderAddress = entity.counterpartId,
            text = entity.text,
            timestamp = entity.timestamp,
            sourcePackage = "",
            isRead = entity.isRead,
            transport = entity.transport.toTransport(),
            channel = entity.channel.toChannel(),
            groupIdentity = GroupIdentity.of(entity.groupCreator, entity.groupId),
            direction = entity.direction.toDirection(),
            sendState = entity.sendState?.toSendState(),
            conversationId = entity.conversationId,
            photoPath = entity.photoPath,
            audioPath = entity.audioPath,
            videoPath = entity.videoPath,
            reaction = entity.reaction,
            wireMessageId = entity.wireMessageId,
            quotedId = entity.quotedId,
            quotedSenderName = entity.quotedSenderName,
            quotedText = entity.quotedText,
            quotedIsHers = entity.quotedIsHers,
            receivedAt = entity.receivedAt
        )

    private fun String.toDirection(): MessageDirection =
        MessageDirection.entries.firstOrNull { it.name == this } ?: MessageDirection.INCOMING

    private fun String.toSendState(): SendState? =
        SendState.entries.firstOrNull { it.name == this }

    private fun String.toTransport(): BridgeTransport =
        BridgeTransport.entries.firstOrNull { it.name == this } ?: BridgeTransport.UNKNOWN

    private fun String.toChannel(): MessageChannel =
        MessageChannel.entries.firstOrNull { it.name == this } ?: MessageChannel.DEVICE
}
