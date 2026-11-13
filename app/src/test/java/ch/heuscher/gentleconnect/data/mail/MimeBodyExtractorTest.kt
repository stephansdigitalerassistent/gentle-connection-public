package ch.heuscher.gentleconnect.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage

class MimeBodyExtractorTest {

    private val session: Session = Session.getInstance(Properties())

    private fun parseMessage(raw: String): MimeMessage {
        return MimeMessage(session, ByteArrayInputStream(raw.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun parseMessageBytes(rawBytes: ByteArray): MimeMessage {
        return MimeMessage(session, ByteArrayInputStream(rawBytes))
    }

    @Test
    fun `extracts plain text body`() {
        val raw = """
            From: Alice <alice@example.com>
            To: Bob <bob@example.com>
            Subject: Test
            Content-Type: text/plain; charset=utf-8

            Hallo Bob, wie geht es dir?
        """.trimIndent().replace("\n", "\r\n")

        val message = parseMessage(raw)
        val body = MimeBodyExtractor.extractBody(message)

        assertEquals("Hallo Bob, wie geht es dir?", body)
    }

    @Test
    fun `multipart alternative prefers plain text`() {
        val raw = """
            From: Alice <alice@example.com>
            Subject: Alternative
            Content-Type: multipart/alternative; boundary="boundary1"

            --boundary1
            Content-Type: text/plain; charset=utf-8

            Das ist der reine Text.
            --boundary1
            Content-Type: text/html; charset=utf-8

            <html><body><p>Das ist <b>HTML</b></p></body></html>
            --boundary1--
        """.trimIndent().replace("\n", "\r\n")

        val message = parseMessage(raw)
        val body = MimeBodyExtractor.extractBody(message)

        assertEquals("Das ist der reine Text.", body)
    }

    @Test
    fun `multipart alternative falls back to html when no plain text`() {
        val raw = """
            From: Alice <alice@example.com>
            Subject: HTML Only
            Content-Type: multipart/alternative; boundary="boundary2"

            --boundary2
            Content-Type: text/html; charset=utf-8

            <html><body><h1>Willkommen</h1><p>Nur HTML Text vorhanden.</p></body></html>
            --boundary2--
        """.trimIndent().replace("\n", "\r\n")

        val message = parseMessage(raw)
        val body = MimeBodyExtractor.extractBody(message)

        assertTrue(body.contains("Willkommen"))
        assertTrue(body.contains("Nur HTML Text vorhanden."))
        assertFalse(body.contains("<h1>"))
        assertFalse(body.contains("<p>"))
    }

    @Test
    fun `decodes quoted-printable with German umlauts`() {
        val raw = """
            From: Anna <anna@example.com>
            Subject: Umlauts
            Content-Type: text/plain; charset=utf-8
            Content-Transfer-Encoding: quoted-printable

            Sch=C3=B6ne Gr=C3=BC=C3=9Fe aus M=C3=BCnchen!
        """.trimIndent().replace("\n", "\r\n")

        val message = parseMessage(raw)
        val body = MimeBodyExtractor.extractBody(message)

        assertEquals("Schöne Grüße aus München!", body)
    }

    @Test
    fun `decodes base64 encoded text`() {
        val raw = """
            From: Anna <anna@example.com>
            Subject: Base64
            Content-Type: text/plain; charset=utf-8
            Content-Transfer-Encoding: base64

            SGFsbG8gZGFzIGlzdCBCYXNlNjQ=
        """.trimIndent().replace("\n", "\r\n")

        val message = parseMessage(raw)
        val body = MimeBodyExtractor.extractBody(message)

        assertEquals("Hallo das ist Base64", body)
    }

    @Test
    fun `handles declared non-UTF-8 charset ISO-8859-1`() {
        val header = "From: Alice <alice@example.com>\r\n" +
                "Subject: ISO Test\r\n" +
                "Content-Type: text/plain; charset=iso-8859-1\r\n" +
                "\r\n"
        val bodyText = "Schöne Grüße"
        val bodyBytes = bodyText.toByteArray(StandardCharsets.ISO_8859_1)
        val rawBytes = header.toByteArray(StandardCharsets.US_ASCII) + bodyBytes

        val message = parseMessageBytes(rawBytes)
        val body = MimeBodyExtractor.extractBody(message)

        assertEquals("Schöne Grüße", body)
    }

    @Test
    fun `attachment-bearing mail yields just the text`() {
        val raw = """
            From: Alice <alice@example.com>
            Subject: With Attachment
            Content-Type: multipart/mixed; boundary="boundary3"

            --boundary3
            Content-Type: text/plain; charset=utf-8

            Hier ist der eigentliche Nachrichten-Text.
            --boundary3
            Content-Type: application/pdf; name="document.pdf"
            Content-Disposition: attachment; filename="document.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0xLjQKJ...
            --boundary3--
        """.trimIndent().replace("\n", "\r\n")

        val message = parseMessage(raw)
        val body = MimeBodyExtractor.extractBody(message)

        assertEquals("Hier ist der eigentliche Nachrichten-Text.", body)
        assertFalse(body.contains("document.pdf"))
        assertFalse(body.contains("JVBERi"))
    }

    @Test
    fun `drops style and script contents when converting html`() {
        val raw = """
            From: Promo <promo@example.com>
            Subject: Newsletter
            Content-Type: text/html; charset=utf-8

            <html>
            <head>
            <style>body { background-color: red; } .hidden { display: none; }</style>
            <script>var secret = "doNotShowMe";</script>
            </head>
            <body>
            <p>Hallo Oma!</p>
            </body>
            </html>
        """.trimIndent().replace("\n", "\r\n")

        val message = parseMessage(raw)
        val body = MimeBodyExtractor.extractBody(message)

        assertEquals("Hallo Oma!", body)
        assertFalse(body.contains("background-color"))
        assertFalse(body.contains("doNotShowMe"))
    }

    @Test
    fun `malformed MIME tree does not throw`() {
        val raw = """
            From: Broken <broken@example.com>
            Content-Type: multipart/mixed; boundary="missing_boundary"

            This is malformed body text without proper boundaries
        """.trimIndent()

        val message = parseMessage(raw)
        val body = MimeBodyExtractor.extractBody(message)

        // Should return extracted string or empty string without throwing exception
        assertTrue(body is String)
    }
}
