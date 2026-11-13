package ch.heuscher.gentleconnect.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage

/**
 * Finding the pictures and the paper in a real letter.
 *
 * Written against a whole MIME message rather than against the rules alone, because the failure
 * this guards against is not in the rules: it is a letter whose photograph sits one level deeper
 * in the tree than the walk goes, or a logo that arrives with a file name and is drawn across her
 * screen as though her daughter had sent it.
 *
 * The message is built line by line rather than as an indented block, because a base64 part is
 * itself several lines long and interpolating one into an indented literal leaves a message whose
 * headers are indented and therefore not headers at all.
 */
class MimeAttachmentsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val session: Session = Session.getInstance(Properties())

    private fun parse(raw: String): MimeMessage =
        MimeMessage(session, ByteArrayInputStream(raw.toByteArray(StandardCharsets.UTF_8)))

    private fun encoded(size: Int): String =
        Base64.getMimeEncoder().encodeToString(ByteArray(size) { (it % 251).toByte() })

    private fun store() = FileMailAttachmentStore(temporaryFolder.newFolder("store-${counter++}"))

    private fun StringBuilder.filePart(
        boundary: String,
        contentType: String,
        disposition: String,
        fileName: String,
        size: Int
    ) {
        appendLine("--$boundary")
        appendLine("Content-Type: $contentType; name=\"$fileName\"")
        appendLine("Content-Disposition: $disposition; filename=\"$fileName\"")
        appendLine("Content-Transfer-Encoding: base64")
        appendLine()
        appendLine(encoded(size))
    }

    /** A letter with the letter itself in two renderings, two pictures, and two files. */
    private fun letter(): String = buildString {
        appendLine("From: Tochter <tochter@example.com>")
        appendLine("Subject: Bilder von den Enkeln")
        appendLine("MIME-Version: 1.0")
        appendLine("Content-Type: multipart/mixed; boundary=\"OUTER\"")
        appendLine()

        appendLine("--OUTER")
        appendLine("Content-Type: multipart/alternative; boundary=\"INNER\"")
        appendLine()
        appendLine("--INNER")
        appendLine("Content-Type: text/plain; charset=UTF-8")
        appendLine()
        appendLine("Liebe Mama, hier sind die Bilder.")
        appendLine("--INNER")
        appendLine("Content-Type: text/html; charset=UTF-8")
        appendLine()
        appendLine("<p>Liebe Mama, hier sind die Bilder.</p>")
        appendLine("--INNER--")

        // A company logo's size, and a photograph's. Nothing but the size separates them.
        filePart("OUTER", "image/png", "inline", "logo.png", 3_000)
        filePart("OUTER", "image/jpeg", "inline", "Enkel.jpg", 60_000)
        filePart("OUTER", "application/pdf", "attachment", "Termin.pdf", 2_000)
        filePart("OUTER", "application/vnd.ms-excel", "attachment", "Liste.xlsx", 4_000)

        appendLine("--OUTER--")
    }

    @Test
    fun `the photograph and the appointment are kept, the logo and the spreadsheet are not`() {
        val kept = MimeAttachments.collect(parse(letter()), "<a@b.example>", store())

        assertEquals(listOf("Enkel.jpg", "Termin.pdf"), kept.map { it.fileName })
        assertTrue(kept[0].isPicture)
        assertTrue(kept[1].isPaper)
    }

    @Test
    fun `the words of the letter are never saved as a file`() {
        val kept = MimeAttachments.collect(parse(letter()), "<a@b.example>", store())

        // The nested multipart/alternative is where the letter itself lives, in two renderings.
        // Either of them saved as a file would put an unreadable page under her letter.
        assertTrue(kept.none { it.mimeType.startsWith("text/") })
    }

    @Test
    fun `a mailing with more pictures than the screen can carry is cut off`() {
        val kept = MimeAttachments.collect(parse(letter()), "<a@b.example>", store(), limit = 1)

        assertEquals(listOf("Enkel.jpg"), kept.map { it.fileName })
    }

    @Test
    fun `a letter with nothing in it but words comes back empty`() {
        val words = buildString {
            appendLine("From: Tochter <tochter@example.com>")
            appendLine("Subject: Kurz")
            appendLine("MIME-Version: 1.0")
            appendLine("Content-Type: text/plain; charset=UTF-8")
            appendLine()
            appendLine("Bis morgen!")
        }

        assertEquals(emptyList<Any>(), MimeAttachments.collect(parse(words), "<c@d.example>", store()))
    }

    private companion object {
        var counter = 0
    }
}
