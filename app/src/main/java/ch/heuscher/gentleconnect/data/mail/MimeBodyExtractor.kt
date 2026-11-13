package ch.heuscher.gentleconnect.data.mail

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.ContentType

/**
 * Extracts readable plain text from a MIME [Part] (e.g. MimeMessage or BodyPart).
 * Handles charsets, transfer encodings, multipart/alternative, HTML stripping, and malformed MIME trees.
 */
object MimeBodyExtractor {

    /** Roughly two lines on her screen at the default text size. */
    private const val DEFAULT_SNIPPET_LENGTH = 160

    fun extractBody(part: Part?): String {
        if (part == null) return ""
        return try {
            val text = readableLetter(processPart(part))
            text.trim()
        } catch (e: Throwable) {
            ""
        }
    }

    /**
     * A letter with the machine's leavings taken out of it: what is left is what somebody wrote.
     *
     * Three things, all of them written for a reader that this one is not:
     *
     *  - **The words left where a picture was.** A letter from Google begins `[image: Google]` —
     *    the alt text of a logo, for a reader that cannot show pictures. This one cannot either,
     *    so the marker is all that ever arrives: a bracket, a colon and a word, at the top of
     *    every letter from every company with a logo. `[cid:image001.png@01D9…]` is Outlook's
     *    form of the same thing, mid-sentence.
     *  - **The address a link pointed at.** Plain-text mail writes `Check activity <https://…>`.
     *    There is nothing to press in a letter here, so the address is never read by anybody —
     *    except aloud, character by character, which is how she takes a letter in. What is left
     *    is "Check activity", which is what the link said.
     *  - **Holes.** Companies pad their mail with blank lines, and taking a logo out leaves
     *    another. Three line breaks in a row is not a paragraph, it is a gap she scrolls past
     *    looking for the rest of the letter.
     *
     * The same rule as the quote marker in a Threema message, and for the same reason: what a
     * machine left behind to stand in for something it could not send is not something she should
     * have to read past.
     *
     * If all that would leave nothing at all, the text is handed back untouched. A letter that
     * was only ever a picture still has to say *something*, and its alt text is then the only
     * description of it anybody wrote.
     */
    fun readableLetter(text: String): String {
        if (text.isEmpty()) return text

        val stripped = LINKED_URL.replace(IMAGE_PLACEHOLDER.replace(text, " "), "")
            .lines()
            .joinToString("\n") { line -> line.replace(Regex("[ \t]{2,}"), " ").trim() }
            .replace(BLANK_RUN, "\n\n")
            .trim()
        return stripped.ifBlank { text }
    }

    /**
     * `[image: Google]`, `[img: banner]`, `[Bild: Logo]`, `[cid:image001.png@01D9…]`.
     *
     * Bounded and stopped at a line break on purpose: an unclosed bracket in a real sentence must
     * not be allowed to swallow the rest of the letter.
     */
    private val IMAGE_PLACEHOLDER = Regex("(?i)\\[(?:image|img|bild|cid)\\s*:[^\\]\\n]{0,200}]")

    /**
     * The address a link was pointing at, written out after the words it was on.
     *
     * Plain-text mail spells a link `Check activity <https://accounts.google.com/…>`: the words
     * somebody wrote, then the address in angle brackets for a reader that cannot follow one.
     * Hers cannot — there is nothing to press in a letter here — so the address is never anything
     * but a line of machine text, and read-aloud reads it out character by character. Taking it
     * out leaves "Check activity", which is exactly what the link said.
     *
     * Only ever the bracketed form, so an address somebody typed into a sentence for her to read
     * stays where they put it.
     */
    private val LINKED_URL = Regex("\\s*<https?://[^>\\s]{0,2000}>")

    /**
     * Three or more line breaks, which is a hole in the middle of a letter rather than a
     * paragraph. Companies pad their mail with them, and taking a logo out leaves another.
     */
    private val BLANK_RUN = Regex("\\n{3,}")

    private fun processPart(part: Part): String {
        return try {
            if (isAttachment(part)) {
                return ""
            }

            if (part.isMimeType("multipart/alternative")) {
                val mp = getMultipartContent(part) ?: return ""
                return processMultipartAlternative(mp)
            }

            if (part.isMimeType("multipart/*")) {
                val mp = getMultipartContent(part) ?: return ""
                return processMultipartGeneric(mp)
            }

            if (part.isMimeType("text/plain")) {
                return extractTextFromPart(part)
            }

            if (part.isMimeType("text/html")) {
                val rawHtml = extractTextFromPart(part)
                return htmlToText(rawHtml)
            }

            ""
        } catch (e: Throwable) {
            ""
        }
    }

    private fun isAttachment(part: Part): Boolean {
        return try {
            val disposition = part.disposition
            if (disposition != null && disposition.equals(Part.ATTACHMENT, ignoreCase = true)) {
                return true
            }
            val fileName = part.fileName
            if (!fileName.isNullOrBlank() && !part.isMimeType("text/plain") && !part.isMimeType("text/html") && !part.isMimeType("multipart/*")) {
                return true
            }
            false
        } catch (e: Throwable) {
            false
        }
    }

    private fun getMultipartContent(part: Part): Multipart? {
        return try {
            val content = part.content
            if (content is Multipart) {
                content
            } else {
                null
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun processMultipartAlternative(mp: Multipart): String {
        var plainText: String? = null
        var htmlText: String? = null

        try {
            val count = mp.count
            for (i in 0 until count) {
                val bodyPart = mp.getBodyPart(i) ?: continue
                if (bodyPart.isMimeType("text/plain")) {
                    val txt = extractTextFromPart(bodyPart)
                    if (txt.isNotBlank()) {
                        plainText = txt
                        break
                    }
                } else if (bodyPart.isMimeType("text/html") && htmlText == null) {
                    val rawHtml = extractTextFromPart(bodyPart)
                    if (rawHtml.isNotBlank()) {
                        htmlText = htmlToText(rawHtml)
                    }
                } else if (bodyPart.isMimeType("multipart/*")) {
                    val subText = processPart(bodyPart)
                    if (subText.isNotBlank() && plainText == null) {
                        plainText = subText
                    }
                }
            }
        } catch (e: Throwable) {
            // ignore and return whatever collected
        }

        return plainText ?: htmlText ?: ""
    }

    private fun processMultipartGeneric(mp: Multipart): String {
        val sb = StringBuilder()
        try {
            val count = mp.count
            for (i in 0 until count) {
                val bodyPart = mp.getBodyPart(i) ?: continue
                val text = processPart(bodyPart)
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) {
                        sb.append("\n\n")
                    }
                    sb.append(text)
                }
            }
        } catch (e: Throwable) {
            // ignore
        }
        return sb.toString()
    }

    private fun extractTextFromPart(part: Part): String {
        try {
            val content = part.content
            if (content is String) {
                return content
            }
            if (content is InputStream) {
                val bytes = readBytes(content)
                val charset = parseCharset(part.contentType)
                return String(bytes, charset)
            }
        } catch (e: Throwable) {
            // Fallback: read raw input stream
        }

        return try {
            val inputStream = part.inputStream
            val bytes = readBytes(inputStream)
            val charset = parseCharset(part.contentType)
            String(bytes, charset)
        } catch (e: Throwable) {
            ""
        }
    }

    private fun parseCharset(contentTypeStr: String?): Charset {
        if (contentTypeStr == null) return StandardCharsets.UTF_8
        return try {
            val ct = ContentType(contentTypeStr)
            val charsetName = ct.getParameter("charset")
            if (!charsetName.isNullOrBlank()) {
                Charset.forName(charsetName.trim())
            } else {
                StandardCharsets.UTF_8
            }
        } catch (e: Throwable) {
            val match = Regex("(?i)charset=[\"']?([^\"';\\s]+)").find(contentTypeStr)
            if (match != null) {
                val name = match.groupValues[1].trim()
                try {
                    Charset.forName(name)
                } catch (ex: Throwable) {
                    StandardCharsets.UTF_8
                }
            } else {
                StandardCharsets.UTF_8
            }
        }
    }

    private fun readBytes(inputStream: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(4096)
        var nRead: Int
        while (inputStream.read(data, 0, data.size).also { nRead = it } != -1) {
            buffer.write(data, 0, nRead)
        }
        return buffer.toByteArray()
    }

    /**
     * A one-line preview of [body] for the mail list row.
     *
     * Her list rows are two lines at a text size the caregiver can enlarge, so a snippet that
     * keeps the message's paragraph breaks would either be clipped mid-layout or push the rows
     * apart. Collapses to a single line and cuts on a word boundary rather than mid-word.
     */
    fun extractSnippet(body: String, maxLength: Int = DEFAULT_SNIPPET_LENGTH): String {
        val singleLine = body.replace(Regex("\\s+"), " ").trim()
        if (singleLine.length <= maxLength) return singleLine

        val cut = singleLine.take(maxLength)
        val lastSpace = cut.lastIndexOf(' ')
        // Only honour the word boundary if it is not so early that it throws most of the
        // preview away — a single very long token would otherwise leave her with "…".
        val trimmed = if (lastSpace > maxLength / 2) cut.take(lastSpace) else cut
        return trimmed.trimEnd() + "…"
    }

    fun htmlToText(html: String): String {
        if (html.isBlank()) return ""

        var cleaned = html.replace(Regex("(?i)<script[^>]*>[\\s\\S]*?</script>"), "")
        cleaned = cleaned.replace(Regex("(?i)<style[^>]*>[\\s\\S]*?</style>"), "")
        cleaned = cleaned.replace(Regex("(?i)<!--[\\s\\S]*?-->"), "")

        cleaned = cleaned.replace(Regex("(?i)<br\\s*/?>"), "\n")
        cleaned = cleaned.replace(Regex("(?i)</?(p|div|tr|h[1-6]|li|blockquote|table|header|footer|section|article)[^>]*>"), "\n")
        cleaned = cleaned.replace(Regex("<[^>]+>"), "")

        cleaned = decodeHtmlEntities(cleaned)

        val lines = cleaned.split("\n")
            .map { line -> line.replace(Regex("[ \\t]+"), " ").trim() }

        val result = StringBuilder()
        var consecutiveEmpty = 0
        for (line in lines) {
            if (line.isEmpty()) {
                consecutiveEmpty++
                if (consecutiveEmpty <= 1 && result.isNotEmpty()) {
                    result.append("\n")
                }
            } else {
                consecutiveEmpty = 0
                if (result.isNotEmpty() && !result.endsWith("\n")) {
                    result.append("\n")
                }
                result.append(line)
            }
        }
        return result.toString().trim()
    }

    fun decodeHtmlEntities(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        s = s.replace("&nbsp;", " ")
        s = s.replace("&amp;", "&")
        s = s.replace("&lt;", "<")
        s = s.replace("&gt;", ">")
        s = s.replace("&quot;", "\"")
        s = s.replace("&apos;", "'")
        s = s.replace("&auml;", "ä")
        s = s.replace("&Auml;", "Ä")
        s = s.replace("&ouml;", "ö")
        s = s.replace("&Ouml;", "Ö")
        s = s.replace("&uuml;", "ü")
        s = s.replace("&Uuml;", "Ü")
        s = s.replace("&szlig;", "ß")
        s = s.replace("&euro;", "€")
        s = s.replace("&copy;", "©")
        s = s.replace("&reg;", "®")
        s = s.replace("&trade;", "™")
        s = s.replace("&laquo;", "«")
        s = s.replace("&raquo;", "»")
        s = s.replace("&ndash;", "–")
        s = s.replace("&mdash;", "—")

        s = Regex("&#[xX]([0-9a-fA-F]+);").replace(s) { matchResult ->
            val hex = matchResult.groupValues[1]
            try {
                val codePoint = hex.toInt(16)
                String(Character.toChars(codePoint))
            } catch (e: Throwable) {
                matchResult.value
            }
        }

        s = Regex("&#([0-9]+);").replace(s) { matchResult ->
            val dec = matchResult.groupValues[1]
            try {
                val codePoint = dec.toInt(10)
                String(Character.toChars(codePoint))
            } catch (e: Throwable) {
                matchResult.value
            }
        }

        return s
    }
}
