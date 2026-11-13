package ch.heuscher.gentleconnect.data.threema

import ch.heuscher.gentleconnect.data.db.ImportedMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule these tests defend: importing a backup must never put old messages in front of
 * her as things needing attention.
 *
 * Two mechanisms enforce it, and only one is unit-testable.
 *
 *  1. The parser forces `isRead = true` regardless of what the backup says. Tested here.
 *  2. Imported messages are structurally absent from the home screen: they live in Room and
 *     never enter `MessageCatalog.messages`, which combines only the notification store and
 *     the bridge stream. That is a property of how the flows are wired, not of a function,
 *     so it cannot be asserted from a JVM test without a fake ViewModel that would prove
 *     nothing about the real one. It is guarded by review, not by this file.
 */
class ThreemaImportRulesTest {

    @Test
    fun `parser forces isRead even when the backup marks a message unread`() {
        val csv = """
            uid,isoutbox,body,type,posted_at,isread
            msg99,0,"Ancient message from 2018",1,1525000000000,0
        """.trimIndent()

        val parsed = ThreemaBackupParser.parseMessagesCsv(csv, "ECHO1001")

        assertEquals(1, parsed.size)
        assertTrue("The backup said unread; the import must still mark it read", parsed[0].isRead)
    }

    @Test
    fun `parser forces isRead when the backup has no isread column at all`() {
        val csv = """
            uid,isoutbox,body,type,posted_at
            msg100,0,"No isread column here",1,1525000000000
        """.trimIndent()

        val parsed = ThreemaBackupParser.parseMessagesCsv(csv, "ECHO1001")

        assertEquals(1, parsed.size)
        assertTrue(parsed[0].isRead)
    }

    @Test
    fun `every message in a multi-row conversation is imported as read`() {
        val csv = """
            uid,isoutbox,body,type,posted_at,isread
            m1,0,"Erste",1,1525000000000,0
            m2,1,"Zweite",1,1525000060000,0
            m3,0,"Dritte",1,1525000120000,1
        """.trimIndent()

        val parsed = ThreemaBackupParser.parseMessagesCsv(csv, "ECHO1001")

        assertEquals(3, parsed.size)
        assertTrue(parsed.all { it.isRead })
    }

    @Test
    fun `the storage entity defaults to read so a caller cannot forget`() {
        // No isRead argument: the default must be true, not false.
        val imported = ImportedMessageEntity(
            id = "imp_123",
            partnerIdentity = "ECHO1001",
            text = "Old historical message",
            timestamp = 1625000000000L,
            isOutgoing = false
        )

        assertTrue(imported.isRead)
    }

    @Test
    fun `an undatable message sorts as old rather than as brand new`() {
        val csv = """
            uid,isoutbox,body,type,posted_at,isread
            m1,0,"Kein Datum",1,,0
            m2,0,"Kaputtes Datum",1,not-a-date,0
        """.trimIndent()

        val parsed = ThreemaBackupParser.parseMessagesCsv(csv, "ECHO1001")

        assertEquals(2, parsed.size)
        // Dating these to now would float a decade-old message to the top of any list.
        assertTrue(parsed.all { it.timestamp == 0L })
    }
}
