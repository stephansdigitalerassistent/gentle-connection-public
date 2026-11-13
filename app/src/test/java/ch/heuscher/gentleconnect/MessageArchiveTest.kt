package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageArchiveTest {

    private fun message(id: String, at: Long, read: Boolean = false, text: String = "hello") =
        Message(
            id = id,
            senderName = "Someone",
            text = text,
            timestamp = at,
            sourcePackage = "test",
            isRead = read
        )

    @Test
    fun `restores what the phone does not have`() {
        val merged = MessageArchive.merge(
            onThePhone = listOf(message("a", 2)),
            fromTheBackup = listOf(message("b", 1))
        )
        assertEquals(listOf("a", "b"), merged.map { it.id })
    }

    @Test
    fun `importing the same backup twice changes nothing the second time`() {
        val phone = listOf(message("a", 2))
        val backup = listOf(message("b", 1), message("c", 3))

        val once = MessageArchive.merge(phone, backup)
        val twice = MessageArchive.merge(once, backup)
        assertEquals(once, twice)
    }

    /** The failure this file exists to prevent: a month of read messages demanding attention again. */
    @Test
    fun `a message she has read stays read when an older backup says otherwise`() {
        val merged = MessageArchive.merge(
            onThePhone = listOf(message("a", 1, read = true)),
            fromTheBackup = listOf(message("a", 1, read = false))
        )
        assertTrue(merged.single().isRead)
    }

    /** Reading is monotonic in both directions: read in the backup is read now. */
    @Test
    fun `a message read before the backup was restored is not unread now`() {
        val merged = MessageArchive.merge(
            onThePhone = listOf(message("a", 1, read = false)),
            fromTheBackup = listOf(message("a", 1, read = true))
        )
        assertTrue(merged.single().isRead)
    }

    /** A backup is not a statement about what should no longer exist. */
    @Test
    fun `importing an old backup does not delete newer messages`() {
        val merged = MessageArchive.merge(
            onThePhone = listOf(message("this-morning", 100)),
            fromTheBackup = listOf(message("last-month", 1))
        )
        assertEquals(setOf("this-morning", "last-month"), merged.map { it.id }.toSet())
    }

    /** The phone's row is the newer statement, so its text and reaction survive the import. */
    @Test
    fun `what the phone knows about a message wins`() {
        val merged = MessageArchive.merge(
            onThePhone = listOf(message("a", 1, text = "edited on the phone")),
            fromTheBackup = listOf(message("a", 1, text = "as it was backed up"))
        )
        assertEquals("edited on the phone", merged.single().text)
    }

    @Test
    fun `counts only what would actually be added`() {
        val phone = listOf(message("a", 1), message("b", 2))
        val backup = listOf(message("b", 2), message("c", 3), message("d", 4))
        assertEquals(listOf("c", "d"), MessageArchive.wouldRestore(phone, backup).map { it.id })
    }

    @Test
    fun `comes back newest first`() {
        val merged = MessageArchive.merge(
            onThePhone = listOf(message("old", 1)),
            fromTheBackup = listOf(message("new", 9), message("middle", 5))
        )
        assertEquals(listOf("new", "middle", "old"), merged.map { it.id })
    }
}
