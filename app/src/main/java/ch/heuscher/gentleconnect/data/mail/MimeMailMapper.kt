package ch.heuscher.gentleconnect.data.mail

import ch.heuscher.gentleconnect.model.Mail
import javax.mail.internet.MimeMessage

/**
 * Turns one whole MIME message into the [Mail] her Post screen draws.
 *
 * Separate from the fetcher so the part that decides what she reads — who it is from, the words,
 * the pictures — does not care how the bytes arrived. It was IMAP once and is the Gmail API now.
 */
object MimeMailMapper {

    fun toMail(
        mimeMsg: MimeMessage,
        /** Used as the id only when the letter carries no Message-ID of its own. */
        fallbackId: String,
        uid: Long?,
        uidValidity: Long?,
        isRead: Boolean,
        /** When the mailbox received it, for a letter whose Date header is missing. */
        fallbackTimestamp: Long?,
        senderFilter: MailSenderFilter,
        unknownSenderLabel: String = "",
        attachmentStore: MailAttachmentStore? = null
    ): Mail {
        val rawFrom = mimeMsg.getHeader("From")?.joinToString(", ")
            ?: mimeMsg.from?.joinToString(", ") { it.toString() }
            ?: ""
        val senderAddress = MailSenderFilter.parseFromHeader(rawFrom)
        val senderName = extractSenderName(rawFrom, senderAddress)
            .ifBlank { unknownSenderLabel }

        val rawMsgId = mimeMsg.messageID ?: mimeMsg.getHeader("Message-ID")?.firstOrNull()
        val messageId = MailDeduplicator.normalizeMessageId(rawMsgId).ifEmpty { null }

        val subject = mimeMsg.subject ?: ""
        val body = MimeBodyExtractor.extractBody(mimeMsg)
        val snippet = MimeBodyExtractor.extractSnippet(body).ifEmpty { subject }

        val timestamp = mimeMsg.sentDate?.time
            ?: fallbackTimestamp
            ?: System.currentTimeMillis()

        val mailId = messageId ?: fallbackId

        // After the id, because that is what the files are filed under, and after the body,
        // because a letter whose words extracted is worth more than one whose photographs saved.
        // Failure here is silent by construction: the store returns what it kept, which may be
        // nothing.
        val attachments = attachmentStore
            ?.let { MimeAttachments.collect(mimeMsg, mailId, it) }
            .orEmpty()

        return Mail(
            id = mailId,
            senderName = senderName,
            senderAddress = senderAddress,
            subject = subject,
            snippet = snippet,
            timestamp = timestamp,
            isRead = isRead,
            body = body,
            messageId = messageId,
            uid = uid,
            uidValidity = uidValidity,
            isFromKnownSender = senderFilter.isAllowed(rawFrom),
            attachments = attachments
        )
    }

    internal fun extractSenderName(rawFrom: String, parsedAddress: String): String {
        if (rawFrom.isBlank()) return parsedAddress
        val beforeAngled = rawFrom.substringBefore("<").trim().removeSurrounding("\"").trim()
        if (beforeAngled.isNotBlank() && beforeAngled != parsedAddress) {
            return beforeAngled
        }
        return parsedAddress
    }
}
