package ch.heuscher.gentleconnect.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what she is shown under a letter.
 *
 * The one worth writing down twice: a company logo must never be drawn as though it were a
 * photograph her daughter sent. That is the rule that would fail silently and look, on any
 * screenshot, exactly like the feature working.
 */
class MailAttachmentsTest {

    @Test
    fun `a photograph is a picture and an appointment is paper`() {
        assertEquals(
            MailAttachmentKind.PICTURE,
            MailAttachments.kindOf("image/jpeg", "IMG_2043.jpg")
        )
        assertEquals(
            MailAttachmentKind.PAPER,
            MailAttachments.kindOf("application/pdf", "Termin.pdf")
        )
    }

    @Test
    fun `the content type may carry its charset and still be understood`() {
        assertEquals(
            MailAttachmentKind.PICTURE,
            MailAttachments.kindOf("image/png; name=\"scan.png\"", "scan.png")
        )
    }

    @Test
    fun `a mail server that will not name the type leaves the file name to say it`() {
        // Outlook sends anything it did not recognise as octet-stream. A photograph labelled that
        // way is still a photograph.
        assertEquals(
            MailAttachmentKind.PICTURE,
            MailAttachments.kindOf("application/octet-stream", "Enkel.JPEG")
        )
        assertEquals(
            MailAttachmentKind.PAPER,
            MailAttachments.kindOf("application/octet-stream", "Rechnung.PDF")
        )
    }

    @Test
    fun `what this phone cannot draw is not kept at all`() {
        assertNull(MailAttachments.kindOf("application/vnd.ms-excel", "Liste.xlsx"))
        assertNull(MailAttachments.kindOf("text/calendar", "termin.ics"))
        // Android's decoder cannot open one, so keeping it would promise a picture and show a hole.
        assertNull(MailAttachments.kindOf("image/svg+xml", "logo.svg"))
        assertNull(MailAttachments.kindOf(null, null))
    }

    @Test
    fun `the logo at the top of every company letter is not a photograph`() {
        val logo = 3L * 1024
        assertFalse(
            MailAttachments.isWorthKeeping(MailAttachmentKind.PICTURE, isInline = true, sizeBytes = logo)
        )
    }

    @Test
    fun `a picture somebody actually took is kept`() {
        val photograph = 1_800_000L
        assertTrue(
            MailAttachments.isWorthKeeping(MailAttachmentKind.PICTURE, isInline = true, sizeBytes = photograph)
        )
    }

    @Test
    fun `something small that was attached on purpose is still kept`() {
        // Attaching is a deliberate act. Only the inline rule guesses, and only about decoration.
        assertTrue(
            MailAttachments.isWorthKeeping(MailAttachmentKind.PAPER, isInline = false, sizeBytes = 900L)
        )
    }

    @Test
    fun `nothing enormous and nothing empty`() {
        assertFalse(
            MailAttachments.isWorthKeeping(
                MailAttachmentKind.PICTURE,
                isInline = false,
                sizeBytes = MailAttachments.MAX_BYTES + 1
            )
        )
        assertFalse(
            MailAttachments.isWorthKeeping(MailAttachmentKind.PICTURE, isInline = false, sizeBytes = 0L)
        )
    }

    @Test
    fun `a file name from a stranger cannot climb out of its folder`() {
        assertEquals("gentle_connection.db", MailAttachments.safeFileName("../../databases/gentle_connection.db"))
        assertEquals("evil.png", MailAttachments.safeFileName("..\\..\\evil.png"))
        assertFalse(MailAttachments.safeFileName("../x.png").contains(".."))
    }

    @Test
    fun `a name that is all punctuation still ends up a name`() {
        assertTrue(MailAttachments.safeFileName("").isNotBlank())
        assertTrue(MailAttachments.safeFileName("...").isNotBlank())
        assertTrue(MailAttachments.safeFileName(null).isNotBlank())
    }

    @Test
    fun `an absurdly long name is cut and keeps what it is`() {
        val name = "a".repeat(400) + ".pdf"
        val safe = MailAttachments.safeFileName(name)
        assertTrue(safe.length <= 80)
        assertTrue(safe.endsWith(".pdf"))
        assertEquals(MailAttachmentKind.PAPER, MailAttachments.kindOf(null, safe))
    }

    @Test
    fun `an everyday name is left as it was written`() {
        assertEquals("Grüsse aus dem Garten.jpg", MailAttachments.safeFileName("Grüsse aus dem Garten.jpg"))
    }

    @Test
    fun `two letters whose ids differ only in punctuation do not share a folder`() {
        val one = MailAttachments.folderNameFor("<abc.123@mail.example.com>")
        val two = MailAttachments.folderNameFor("<abc-123@mail.example.com>")
        assertTrue(one != two)
    }

    @Test
    fun `the same letter always lands in the same folder`() {
        // What stops a re-fetch after a UID reset growing a second copy of every photograph.
        assertEquals(
            MailAttachments.folderNameFor("imap_12345_67"),
            MailAttachments.folderNameFor("imap_12345_67")
        )
    }

    @Test
    fun `a folder name has nothing in it a file system could object to`() {
        val folder = MailAttachments.folderNameFor("../../etc/passwd@host")
        assertFalse(folder.contains("/"))
        assertFalse(folder.contains("."))
    }

    @Test
    fun `the list survives being written down and read back`() {
        val attachments = listOf(
            MailAttachment("Enkel.jpg", "image/jpeg", "/data/mail/a/Enkel.jpg"),
            MailAttachment("Termin.pdf", "application/pdf", "/data/mail/a/Termin.pdf")
        )
        assertEquals(attachments, MailAttachments.decode(MailAttachments.encode(attachments)))
    }

    @Test
    fun `nothing is nothing, both ways round`() {
        assertEquals("", MailAttachments.encode(emptyList()))
        assertEquals(emptyList<MailAttachment>(), MailAttachments.decode(""))
        assertEquals(emptyList<MailAttachment>(), MailAttachments.decode(null))
    }

    @Test
    fun `a row that does not decode cleanly costs one attachment and not the letter`() {
        val good = MailAttachment("Enkel.jpg", "image/jpeg", "/data/mail/a/Enkel.jpg")
        val stored = MailAttachments.encode(listOf(good)) + 30.toChar() + "rubbish"
        assertEquals(listOf(good), MailAttachments.decode(stored))
    }
}
