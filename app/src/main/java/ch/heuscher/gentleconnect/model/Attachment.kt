package ch.heuscher.gentleconnect.model

/**
 * A message that is not words: a photograph, a voice, a film, a place.
 *
 * Both ways a message reaches this phone put a bracketed German token in the message *text* rather
 * than in a field of its own — [ch.heuscher.gentleconnect.data.threema.ThreemaBackupParser] writes
 * one, and the WhatsApp bridge sends one (see WhatsAppBridgeProtocol). So `[Foto]` was standing in
 * the middle of an English screen, in square brackets, which is a developer's punctuation and not
 * something she has ever been asked to read.
 *
 * The token stays the wire format — it is what both senders already write, and rewriting the store
 * would mean rewriting every message already on the phone. It is turned into words at the moment
 * it is drawn, by [ch.heuscher.gentleconnect.ui.messageInWords].
 *
 * **The token can carry a caption behind it, and this was missed for a long time.** The bridge's
 * `withCaption` sends `placeholder + " " + caption`, so what arrives is `[Foto] Grüsse aus dem
 * Garten` — one string, token first. The matcher here accepted a token only when it was the entire
 * message, so every captioned photograph fell through it and was drawn exactly as it arrived,
 * brackets and all. On her phone that was 60 messages, 29 of them WhatsApp statuses: the one shape
 * this file exists to keep off her screen, on her screen, under her family's names.
 *
 * So a token is read when it *begins* the message, and what follows is her sender's words. A token
 * anywhere else is still somebody having typed square brackets and is never touched — see [of].
 *
 * Pure, and an enum rather than a string, for the reason [PersonName] is: this object has no
 * business knowing which language the phone is in, and CI here runs JVM unit tests only.
 */
enum class Attachment(val token: String) {
    PHOTO("[Foto]"),
    VOICE("[Sprachnachricht]"),
    VIDEO("[Video]"),
    LOCATION("[Standort]"),
    FILE("[Datei]"),
    POLL("[Umfrage]"),
    CONTACT("[Kontakt]"),
    STICKER("[Sticker]"),
    MESSAGE("[Nachricht]"),
    OTHER("[Anhang]");

    companion object {

        /**
         * The attachment this message announces, or null when it is words.
         *
         * The token has to begin the message and has to end where a word ends: `[Foto]` and
         * `[Foto] am See` are a photograph, `Schau dir das [Foto] an` is a sentence somebody
         * wrote, and `[Fotos]` — brackets around a word this does not know — is left alone too.
         * Being wrong in that direction is the safe one: an unrecognised token is shown to her as
         * it was written, while a sentence mistaken for a token would have its words deleted.
         */
        fun of(text: String?): Attachment? = when (leadingToken(text)) {
            "[foto]" -> PHOTO
            "[sprachnachricht]" -> VOICE
            "[video]" -> VIDEO
            "[standort]" -> LOCATION
            // Both words for the same thing, from the two senders: the Threema parser writes
            // "[Datei]" and the WhatsApp bridge writes "[Dokument]". Neither was ever going to be
            // changed to match the other, because each is what that messenger's own users read.
            "[datei]", "[dokument]" -> FILE
            "[umfrage]" -> POLL
            "[kontakt]" -> CONTACT
            "[sticker]" -> STICKER
            "[nachricht]" -> MESSAGE
            "[anhang]" -> OTHER
            else -> null
        }

        /**
         * What her sender actually wrote, with the token taken off the front.
         *
         * The empty string when the message is nothing but a token, which is the ordinary case and
         * is what makes [ch.heuscher.gentleconnect.ui.messageInWords] fall back to a word. The
         * whole text, untouched, when there is no token to take off — including the case of a
         * bracketed word this does not recognise.
         */
        fun captionIn(text: String?): String {
            val trimmed = text?.trim().orEmpty()
            if (of(trimmed) == null) return trimmed
            return trimmed.substringAfter(']').trim()
        }

        /**
         * The same message, announced as what the attachment turned out to be.
         *
         * Threema files a photograph, a film and a voice message all under the same type, so the
         * archive says `[Datei]` for every one of them and the importer only learns which it is by
         * looking at the first bytes of the file. Left as it arrived, the word "Datei" was drawn
         * over 793 of her pictures: [Message.showsWords] can only take a token off the screen when
         * the token names the thing that is drawn under it, and `[Datei]` names nothing.
         *
         * Her sender's own words, where there are any, are kept exactly as they were — only the
         * token in front of them is corrected. A message that is words and not an attachment is
         * never touched: somebody writing "Schick mir die Datei" is not announcing a photograph.
         */
        fun retoken(text: String?, attachment: Attachment): String {
            val trimmed = text?.trim().orEmpty()
            if (of(trimmed) == null) return trimmed

            val caption = captionIn(trimmed)
            return if (caption.isEmpty()) attachment.token else "${attachment.token} $caption"
        }

        /**
         * The bracketed word this message begins with, lower-cased, or null.
         *
         * Whitespace after the bracket is required so that `[Foto]s` is not a photograph. There is
         * no such message today; there is also no reason for this to decide that there could not
         * be one.
         */
        private fun leadingToken(text: String?): String? {
            val trimmed = text?.trimStart().orEmpty()
            if (!trimmed.startsWith("[")) return null

            val close = trimmed.indexOf(']')
            if (close < 0) return null

            val rest = trimmed.substring(close + 1)
            if (rest.isNotEmpty() && !rest.first().isWhitespace()) return null

            return trimmed.substring(0, close + 1).lowercase()
        }
    }
}
