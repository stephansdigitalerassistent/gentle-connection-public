package ch.heuscher.gentleconnect.model

import java.util.regex.Pattern

/**
 * What a quoted message in the incoming text stream names: the wire id of the message being
 * answered (when known), and the remainder of the text with quote headers stripped.
 */
data class QuotedRef(
    val wireId: String?,
    val body: String
)

/**
 * Strips messenger quote markers out of message text before any screen or speech engine sees them.
 *
 * Threema embeds replies directly in the message text — modern versions use `> quote #<16 hex>\n\n<body>`,
 * while an older `> <identity>: <quoted text>\n<body>` format remains in circulation. Left in place,
 * these markers put hex strings in front of her daughter's words and cause read-aloud to recite
 * machine metadata.
 *
 * Pure on purpose: unit tests verify every permutation without Android dependencies.
 */
object QuoteMarker {

    private const val MAX_DEPTH = 5

    private val QUOTE_V2 = Pattern.compile("(?sm)\\A> quote #([0-9a-f]{16})\\r?\\n\\r?\\n(.*)")
    private val QUOTE_V1 = Pattern.compile("(?sm)\\A> ([A-Z0-9*]{8}): (.*?)^(?!\r?> ).+")

    fun parse(text: String): QuotedRef {
        if (text.isEmpty()) return QuotedRef(wireId = null, body = text)

        var currentText = text
        var resolvedWireId: String? = null
        var depth = 0

        while (depth < MAX_DEPTH) {
            val v2 = QUOTE_V2.matcher(currentText)
            if (v2.find()) {
                if (resolvedWireId == null) {
                    resolvedWireId = v2.group(1)
                }
                currentText = v2.group(2) ?: ""
                depth++
                continue
            }

            val v1 = QUOTE_V1.matcher(currentText)
            if (v1.find()) {
                currentText = currentText.substring(v1.end(2)).trim()
                depth++
                continue
            }

            break
        }

        return if (depth > 0) {
            QuotedRef(wireId = resolvedWireId, body = currentText)
        } else {
            QuotedRef(wireId = null, body = text)
        }
    }
}
