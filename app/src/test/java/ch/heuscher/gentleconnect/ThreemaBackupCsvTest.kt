package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.threema.ThreemaBackupContact
import ch.heuscher.gentleconnect.data.threema.ThreemaBackupMessage
import ch.heuscher.gentleconnect.data.threema.ThreemaBackupParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Kotlin round-trip unit tests for Threema CSV parsing and writing.
 *
 * Verifies that writeContactsCsv / parseContactsCsv and writeMessagesCsv / parseMessagesCsv
 * faithfully preserve all contacts and messages, including awkward edge cases:
 * embedded quotes, commas, newlines, semicolons, and timestamps.
 */
class ThreemaBackupCsvTest {

    @Test
    fun `contacts round trip with standard fields`() {
        val original = listOf(
            ThreemaBackupContact(
                identity = "ECHOECHO",
                publicKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                firstName = "Echo",
                lastName = "Bot",
                nickname = "EchoService",
                verification = "3"
            ),
            ThreemaBackupContact(
                identity = "TESTUSER",
                publicKey = null,
                firstName = "Anna",
                lastName = "Muster",
                nickname = null,
                verification = null
            )
        )

        val csv = ThreemaBackupParser.writeContactsCsv(original)
        val parsed = ThreemaBackupParser.parseContactsCsv(csv)

        assertEquals(original, parsed)
    }

    @Test
    fun `contacts round trip with empty list`() {
        val csv = ThreemaBackupParser.writeContactsCsv(emptyList())
        val parsed = ThreemaBackupParser.parseContactsCsv(csv)
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `contacts round trip with awkward characters`() {
        val original = listOf(
            ThreemaBackupContact(
                identity = "TRICKY01",
                firstName = "Hans, Jr.",
                lastName = "Müller \"Der Grosse\"",
                nickname = "Hans;Der;Grosse\nZweite Zeile",
                verification = "2"
            )
        )

        val csv = ThreemaBackupParser.writeContactsCsv(original)
        val parsed = ThreemaBackupParser.parseContactsCsv(csv)

        assertEquals(original, parsed)
    }

    @Test
    fun `messages round trip with standard incoming and outgoing messages`() {
        val conversationIdentity = "ECHOECHO"
        val original = listOf(
            ThreemaBackupMessage(
                id = "msg_001",
                partnerIdentity = "ECHOECHO",
                text = "Hello world",
                timestamp = 1725000000000L,
                isOutgoing = false,
                isRead = true,
                type = "text"
            ),
            ThreemaBackupMessage(
                id = "msg_002",
                partnerIdentity = "ECHOECHO",
                text = "My reply to you",
                timestamp = 1725000060000L,
                isOutgoing = true,
                isRead = true,
                type = "text"
            ),
            ThreemaBackupMessage(
                id = "msg_003",
                partnerIdentity = "ECHOECHO",
                text = "Unread message",
                timestamp = 1725000120000L,
                isOutgoing = false,
                isRead = true,
                type = "text"
            )
        )

        val csv = ThreemaBackupParser.writeMessagesCsv(original)
        val parsed = ThreemaBackupParser.parseMessagesCsv(csv, conversationIdentity)

        assertEquals(original, parsed)
    }

    @Test
    fun `messages round trip with empty list`() {
        val csv = ThreemaBackupParser.writeMessagesCsv(emptyList())
        val parsed = ThreemaBackupParser.parseMessagesCsv(csv, "ECHOECHO")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `messages round trip with embedded quotes commas newlines and semicolons`() {
        val conversationIdentity = "COMPLEX1"
        val original = listOf(
            ThreemaBackupMessage(
                id = "msg_complex_1",
                partnerIdentity = "COMPLEX1",
                text = "He said: \"Hello, world!\" and smiled.",
                timestamp = 1725010000000L,
                isOutgoing = false,
                isRead = true,
                type = "text"
            ),
            ThreemaBackupMessage(
                id = "msg_complex_2",
                partnerIdentity = "COMPLEX1",
                text = "First line of text\nSecond line with, commas\r\nThird line; semicolons \"and quotes\"",
                timestamp = 1725020000000L,
                isOutgoing = true,
                isRead = true,
                type = "text"
            ),
            ThreemaBackupMessage(
                id = "msg_complex_3",
                partnerIdentity = "COMPLEX1",
                text = "Grüezi mitenand! ☕👋 Schöne Grüsse aus Zürich, Schweiz.",
                timestamp = 1725030000000L,
                isOutgoing = false,
                isRead = true,
                type = "text"
            )
        )

        val csv = ThreemaBackupParser.writeMessagesCsv(original)
        val parsed = ThreemaBackupParser.parseMessagesCsv(csv, conversationIdentity)

        assertEquals(original, parsed)
    }

    @Test
    fun `messages round trip preserves timestamp exactly`() {
        val timestamp = 1693488000000L
        val original = listOf(
            ThreemaBackupMessage(
                id = "msg_ts",
                partnerIdentity = "TIMETEST",
                text = "Testing time",
                timestamp = timestamp,
                isOutgoing = false,
                isRead = true,
                type = "text"
            )
        )

        val csv = ThreemaBackupParser.writeMessagesCsv(original)
        val parsed = ThreemaBackupParser.parseMessagesCsv(csv, "TIMETEST")

        assertEquals(timestamp, parsed.single().timestamp)
    }

    /**
     * The one field that deliberately does not survive the trip.
     *
     * The exporter writes the true value, because the column belongs to Threema's format and its
     * own app reads it. The parser ignores it coming back, because a restored backup is her
     * history and history must not arrive demanding attention — see ThreemaImportRulesTest, which
     * has pinned that rule since backups could first be imported at all.
     *
     * Asserted here as well as there so that anyone making the round trip "properly faithful"
     * finds out from this file, which is the one they will be looking at.
     */
    @Test
    fun `reading state is deliberately not restored from a backup`() {
        val unreadWhenBackedUp = ThreemaBackupMessage(
            id = "m-unread",
            partnerIdentity = "ECHOECHO",
            text = "Unread when the backup was taken",
            timestamp = 1725000120000L,
            isOutgoing = false,
            isRead = false,
            type = "text"
        )

        val csv = ThreemaBackupParser.writeMessagesCsv(listOf(unreadWhenBackedUp))
        assertTrue("the export still records the true value", csv.trimEnd().endsWith("0"))

        val parsed = ThreemaBackupParser.parseMessagesCsv(csv, "ECHOECHO")
        assertTrue("but an imported message arrives read", parsed.single().isRead)
    }
}
