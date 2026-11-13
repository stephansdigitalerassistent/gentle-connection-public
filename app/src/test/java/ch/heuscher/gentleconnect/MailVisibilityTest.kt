package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.MailRepository
import ch.heuscher.gentleconnect.data.db.toEntity
import ch.heuscher.gentleconnect.data.db.toMail
import ch.heuscher.gentleconnect.model.Mail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the rule that makes her Post screen show everything rather than only what is new:
 * reading a mail must never remove it, and the same notification arriving twice must never
 * duplicate it or reset it to unread.
 *
 * These exercise the in-memory path. The database path is the same contract expressed as
 * `OnConflictStrategy.IGNORE` plus an UPDATE, which cannot run without an Android device.
 */
class MailVisibilityTest {

    private fun mail(id: String, timestamp: Long, isRead: Boolean = false) = Mail(
        id = id,
        senderName = "Anna",
        senderAddress = "anna@example.com",
        subject = "Fotos vom Garten",
        snippet = "Schau mal, die Rosen bluehen",
        timestamp = timestamp,
        isRead = isRead
    )

    @Test
    fun `a mail she has read stays in the list`() {
        val repository = MailRepository()
        repository.addMail(mail("a", 1_000L))

        repository.markAsRead("a")

        assertEquals(1, repository.mails.value.size)
        assertTrue(repository.mails.value.first().isRead)
    }

    @Test
    fun `the same notification arriving twice does not duplicate the mail`() {
        val repository = MailRepository()
        repository.addMail(mail("a", 1_000L))
        repository.addMail(mail("a", 1_000L))

        assertEquals(1, repository.mails.value.size)
    }

    @Test
    fun `re-processing a notification does not mark a read mail unread again`() {
        val repository = MailRepository()
        repository.addMail(mail("a", 1_000L))
        repository.markAsRead("a")

        // The listener re-offers every active notification each time the launcher resumes,
        // always with isRead = false. That must not undo her having read it.
        repository.addMail(mail("a", 1_000L))

        assertTrue(repository.mails.value.single().isRead)
    }

    @Test
    fun `newest mail is first regardless of arrival order`() {
        val repository = MailRepository()
        repository.addMail(mail("older", 1_000L))
        repository.addMail(mail("newest", 3_000L))
        repository.addMail(mail("middle", 2_000L))

        assertEquals(
            listOf("newest", "middle", "older"),
            repository.mails.value.map { it.id }
        )
    }

    @Test
    fun `read state survives the round trip through the database entity`() {
        val read = mail("a", 1_000L, isRead = true)
        val unread = mail("b", 2_000L, isRead = false)

        assertTrue(read.toEntity().toMail().isRead)
        assertFalse(unread.toEntity().toMail().isRead)
        assertEquals(read, read.toEntity().toMail())
    }

    @Test
    fun `markAllAsRead marks every mail read in memory`() {
        val repository = MailRepository()
        repository.addMail(mail("a", 1_000L, isRead = false))
        repository.addMail(mail("b", 2_000L, isRead = false))

        repository.markAllAsRead()

        assertEquals(2, repository.mails.value.size)
        assertTrue(repository.mails.value.all { it.isRead })
    }
}
