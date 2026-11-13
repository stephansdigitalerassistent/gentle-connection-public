package ch.heuscher.gentleconnect.data.mail

import ch.heuscher.gentleconnect.model.Mail
import ch.heuscher.gentleconnect.model.MailAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailDeduplicationTest {

    @Test
    fun `matching by Message-ID detects duplicates`() {
        val notifMail = Mail(
            id = "notif_1",
            senderName = "Anna",
            senderAddress = "anna@example.com",
            subject = "Fotos",
            snippet = "Fotos vom Garten",
            timestamp = 100000L,
            messageId = "<msg12345@example.com>"
        )

        val imapMail = Mail(
            id = "imap_101",
            senderName = "Anna Smith",
            senderAddress = "anna@example.com",
            subject = "Fotos",
            snippet = "Fotos vom Garten",
            timestamp = 100500L,
            body = "Hallo, hier sind alle Fotos vom Garten!",
            messageId = "msg12345@example.com",
            uid = 101L,
            uidValidity = 1L
        )

        assertTrue(MailDeduplicator.isDuplicate(notifMail, imapMail))
    }

    @Test
    fun `fallback match by sender and timestamp within tolerance`() {
        val notifMail = Mail(
            id = "notif_2",
            senderName = "Anna",
            senderAddress = "anna@example.com",
            subject = "Re: Kaffeetrinken",
            snippet = "Ja gerne!",
            timestamp = 200000L,
            messageId = null
        )

        val imapMail = Mail(
            id = "imap_102",
            senderName = "Anna",
            senderAddress = "anna@example.com",
            subject = "Kaffeetrinken",
            snippet = "Ja gerne!",
            timestamp = 202000L, // 2 seconds later
            body = "Ja gerne, ich komme um 15 Uhr vorbei.",
            messageId = null,
            uid = 102L,
            uidValidity = 1L
        )

        assertTrue(MailDeduplicator.isDuplicate(notifMail, imapMail))
    }

    @Test
    fun `notification path and IMAP produces one row and prefers IMAP copy`() {
        val notifMail = Mail(
            id = "notif_3",
            senderName = "Anna",
            senderAddress = "",
            subject = "Gruss",
            snippet = "Liebe Grusse",
            timestamp = 300000L,
            isRead = true
        )

        val imapMail = Mail(
            id = "imap_103",
            senderName = "Anna",
            senderAddress = "anna@example.com",
            subject = "Gruss",
            snippet = "Liebe Grusse",
            timestamp = 300100L,
            isRead = false,
            body = "Liebe Grusse aus dem Urlaub!",
            messageId = "<msg999@example.com>",
            uid = 103L,
            uidValidity = 1L
        )

        val deduplicated = MailDeduplicator.deduplicate(listOf(notifMail, imapMail))

        assertEquals(1, deduplicated.size)
        val merged = deduplicated.first()

        assertEquals("Liebe Grusse aus dem Urlaub!", merged.body)
        assertEquals("anna@example.com", merged.senderAddress)
        assertEquals(103L, merged.uid)
        assertTrue(merged.isRead) // Preserves her having already read it on phone
    }

    @Test
    fun `two real letters from the same person minutes apart stay two letters`() {
        // Same sender, same thread, close together in time — every signal the fallback match
        // uses says "duplicate", and both came from notifications, so neither is the other's
        // second sighting. Merging them would silently lose one of her letters.
        val first = Mail(
            id = "notif_10",
            senderName = "Anna",
            senderAddress = "anna@example.com",
            subject = "Kaffeetrinken",
            snippet = "Passt dir Freitag?",
            timestamp = 500_000L
        )
        val second = Mail(
            id = "notif_11",
            senderName = "Anna",
            senderAddress = "anna@example.com",
            subject = "Re: Kaffeetrinken",
            snippet = "Ach, und bring bitte die Fotos mit.",
            timestamp = 620_000L // two minutes later
        )

        assertFalse(MailDeduplicator.isDuplicate(first, second))
        assertEquals(2, MailDeduplicator.deduplicate(listOf(first, second)).size)
    }

    @Test
    fun `two mailbox letters from the same person minutes apart stay two letters`() {
        val first = Mail(
            id = "imap_10",
            senderName = "Anna",
            senderAddress = "anna@example.com",
            subject = "Kaffeetrinken",
            snippet = "Passt dir Freitag?",
            timestamp = 500_000L,
            body = "Passt dir Freitag?",
            uid = 10L,
            uidValidity = 1L
        )
        val second = first.copy(
            id = "imap_11",
            snippet = "Ach, und bring bitte die Fotos mit.",
            timestamp = 620_000L,
            body = "Ach, und bring bitte die Fotos mit.",
            uid = 11L
        )

        assertFalse(MailDeduplicator.isDuplicate(first, second))
        assertEquals(2, MailDeduplicator.deduplicate(listOf(first, second)).size)
    }

    @Test
    fun `the same row offered twice is still one row`() {
        val mail = Mail(
            id = "notif_12",
            senderName = "Anna",
            senderAddress = "anna@example.com",
            subject = "Gruss",
            snippet = "Liebe Grüsse",
            timestamp = 700_000L
        )

        assertTrue(MailDeduplicator.isDuplicate(mail, mail.copy(isRead = true)))
        assertEquals(1, MailDeduplicator.deduplicate(listOf(mail, mail.copy(isRead = true))).size)
    }

    @Test
    fun `different Message-IDs are never merged however similar the mails look`() {
        val first = Mail(
            id = "imap_20",
            senderName = "Anna",
            senderAddress = "anna@example.com",
            subject = "Fotos",
            snippet = "…",
            timestamp = 800_000L,
            messageId = "<one@example.com>",
            uid = 20L
        )
        val second = first.copy(id = "notif_20", messageId = "<two@example.com>", uid = null)

        assertFalse(MailDeduplicator.isDuplicate(first, second))
    }

    @Test
    fun `merging never empties a letter of its photographs`() {
        // The copy with the files is not always the one merging picks as primary — a letter
        // stored before attachments existed has a uid and a body and wins on both counts. Losing
        // the files here would be silent: the letter is still there, and the photograph her
        // daughter sent simply stops being under it.
        val stored = Mail(
            id = "imap_31",
            senderName = "Anna",
            senderAddress = "anna@example.com",
            subject = "Fotos",
            snippet = "Fotos vom Garten",
            timestamp = 900_000L,
            body = "Hier die Fotos",
            messageId = "<msg31@example.com>",
            uid = 31L
        )
        val refetched = stored.copy(
            attachments = listOf(
                MailAttachment("Enkel.jpg", "image/jpeg", "/data/mail/a/Enkel.jpg")
            )
        )

        assertEquals(
            listOf("Enkel.jpg"),
            MailDeduplicator.mergeMails(stored, refetched).attachments.map { it.fileName }
        )
        assertEquals(
            listOf("Enkel.jpg"),
            MailDeduplicator.mergeMails(refetched, stored).attachments.map { it.fileName }
        )
    }
}
