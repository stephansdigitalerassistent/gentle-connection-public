package ch.heuscher.gentleconnect.data.mail

import ch.heuscher.gentleconnect.model.Mail
import kotlin.math.abs

/**
 * Utility for deduplicating and merging mail items from different sources (e.g. Notification listener vs IMAP sync).
 */
object MailDeduplicator {

    /** Default timestamp tolerance for matching mail without Message-ID: 5 minutes (300,000 ms). */
    const val DEFAULT_TIMESTAMP_TOLERANCE_MS = 5 * 60 * 1000L

    /**
     * Checks if two [Mail] items represent the same underlying email.
     */
    fun isDuplicate(
        mailA: Mail,
        mailB: Mail,
        toleranceMs: Long = DEFAULT_TIMESTAMP_TOLERANCE_MS
    ): Boolean {
        // 1. Same row.
        if (mailA.id == mailB.id) return true

        // 2. Primary match: Message-ID. Authoritative whenever both sides have one.
        val idA = normalizeMessageId(mailA.messageId)
        val idB = normalizeMessageId(mailB.messageId)
        if (idA.isNotEmpty() && idB.isNotEmpty()) {
            return idA.equals(idB, ignoreCase = true)
        }

        // 3. Everything below is a guess: same sender, similar subject, close in time. That
        // guess is only ever safe ACROSS the two sources, where it is reconciling one real
        // mail seen twice. Within a single source it is plain wrong — two mails from the same
        // person in the same thread minutes apart match on every one of those signals, and
        // merging them silently loses one of her letters.
        if (isFromMailbox(mailA) == isFromMailbox(mailB)) return false

        val timeDiff = abs(mailA.timestamp - mailB.timestamp)
        if (timeDiff > toleranceMs) {
            return false
        }

        val sendersMatch = sendersMatch(mailA, mailB)
        if (!sendersMatch) {
            return false
        }

        // Subject match: if both have non-empty subject, check normalized subject match
        val normSubA = normalizeSubject(mailA.subject)
        val normSubB = normalizeSubject(mailB.subject)
        if (normSubA.isNotEmpty() && normSubB.isNotEmpty()) {
            return normSubA.equals(normSubB, ignoreCase = true)
        }

        return true
    }

    /**
     * Merges two matching mails, preferring the IMAP copy for body, messageId, uid, uidValidity, and senderAddress,
     * while preserving read state (`isRead = true` if either was read).
     */
    fun mergeMails(mailA: Mail, mailB: Mail): Mail {
        val isImapA = mailA.uid != null || mailA.body.isNotBlank()
        val isImapB = mailB.uid != null || mailB.body.isNotBlank()

        val primary = if (isImapB && !isImapA) mailB else mailA
        val secondary = if (primary === mailA) mailB else mailA

        val mergedRead = primary.isRead || secondary.isRead

        val body = primary.body.ifBlank { secondary.body.ifBlank { primary.snippet } }
        val messageId = primary.messageId ?: secondary.messageId
        val uid = primary.uid ?: secondary.uid
        val uidValidity = primary.uidValidity ?: secondary.uidValidity
        val senderAddress = primary.senderAddress.ifBlank { secondary.senderAddress }
        val senderName = primary.senderName.ifBlank { secondary.senderName }
        // Whichever copy actually has files wins. Only the IMAP path ever saves any, and a copy
        // fetched before this existed has none — so preferring "not empty" over "primary" is what
        // keeps her photographs across a re-fetch instead of quietly emptying the letter.
        val attachments = primary.attachments.ifEmpty { secondary.attachments }

        return primary.copy(
            senderName = senderName,
            senderAddress = senderAddress,
            body = body,
            messageId = messageId,
            uid = uid,
            uidValidity = uidValidity,
            isRead = mergedRead,
            isFromKnownSender = primary.isFromKnownSender || secondary.isFromKnownSender,
            attachments = attachments
        )
    }

    /**
     * Deduplicates a list of mails, combining duplicates using [mergeMails].
     */
    fun deduplicate(
        mails: List<Mail>,
        toleranceMs: Long = DEFAULT_TIMESTAMP_TOLERANCE_MS
    ): List<Mail> {
        val result = mutableListOf<Mail>()
        for (mail in mails) {
            val existingIndex = result.indexOfFirst { isDuplicate(it, mail, toleranceMs) }
            if (existingIndex >= 0) {
                val merged = mergeMails(result[existingIndex], mail)
                result[existingIndex] = merged
            } else {
                result.add(mail)
            }
        }
        return result.sortedByDescending { it.timestamp }
    }

    /**
     * True if this mail came from the mailbox itself rather than from a notification. Only the
     * IMAP path assigns a UID.
     */
    private fun isFromMailbox(mail: Mail): Boolean = mail.uid != null

    fun normalizeMessageId(messageId: String?): String {
        if (messageId.isNullOrBlank()) return ""
        return messageId.trim().removeSurrounding("<", ">").trim()
    }

    private fun sendersMatch(mailA: Mail, mailB: Mail): Boolean {
        val addrA = mailA.senderAddress.trim().lowercase()
        val addrB = mailB.senderAddress.trim().lowercase()
        if (addrA.isNotEmpty() && addrB.isNotEmpty()) {
            return addrA == addrB
        }

        val nameA = mailA.senderName.trim().lowercase()
        val nameB = mailB.senderName.trim().lowercase()
        if (nameA.isNotEmpty() && nameB.isNotEmpty()) {
            return nameA == nameB
        }

        return false
    }

    private fun normalizeSubject(subject: String): String {
        var s = subject.trim()
        val prefixes = listOf("re:", "fwd:", "fw:", "aw:", "wg:")
        var changed = true
        while (changed) {
            changed = false
            for (p in prefixes) {
                if (s.startsWith(p, ignoreCase = true)) {
                    s = s.substring(p.length).trim()
                    changed = true
                }
            }
        }
        return s
    }
}
