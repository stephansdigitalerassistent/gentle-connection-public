package ch.heuscher.gentleconnect.data.mail

import ch.heuscher.gentleconnect.model.MailAttachment
import ch.heuscher.gentleconnect.model.MailAttachments
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.ContentType

/**
 * Walks a letter looking for the pictures and the paper in it.
 *
 * The mirror of [MimeBodyExtractor], which walks the same tree throwing exactly this away — a
 * letter is a tree of parts, some of them words for the screen and some of them files, and until
 * now only the words were ever taken out of it. Kept apart rather than folded into one pass,
 * because the two have different failure modes and only one of them may cost her the letter:
 * words that will not extract leave her an empty letter, whereas a photograph that will not save
 * must leave the letter exactly as it was.
 *
 * Nothing here decides what is worth keeping. That is [MailAttachments] and the store, so the
 * rule sits in one place and can be proved without a mailbox.
 */
object MimeAttachments {

    /**
     * Everything worth showing under [mailId]'s words, in the order the sender put it in.
     *
     * Bounded twice: at most [MailAttachments.MAX_PER_LETTER] files, and at most [MAX_PARTS] parts
     * looked at on the way to them. A mail that is a thousand nested parts is either a mailing
     * nobody reads or something built to be walked forever, and her post must not stall on either.
     */
    fun collect(
        part: Part?,
        mailId: String,
        store: MailAttachmentStore,
        limit: Int = MailAttachments.MAX_PER_LETTER
    ): List<MailAttachment> {
        if (part == null || limit <= 0) return emptyList()
        val kept = mutableListOf<MailAttachment>()
        walk(part, mailId, store, limit, kept, Budget())
        return kept
    }

    private class Budget(var partsLeft: Int = MAX_PARTS)

    private fun walk(
        part: Part,
        mailId: String,
        store: MailAttachmentStore,
        limit: Int,
        kept: MutableList<MailAttachment>,
        budget: Budget
    ) {
        if (kept.size >= limit || budget.partsLeft <= 0) return
        budget.partsLeft--

        try {
            if (part.isMimeType("multipart/*")) {
                val multipart = part.content as? Multipart ?: return
                for (i in 0 until multipart.count) {
                    if (kept.size >= limit || budget.partsLeft <= 0) return
                    val child = multipart.getBodyPart(i) ?: continue
                    walk(child, mailId, store, limit, kept, budget)
                }
                return
            }

            // The letter itself, and every alternative rendering of it. A part with a file name is
            // not that even when it is text — a `.txt` attachment is a file — but this app cannot
            // draw one either, so it falls out at [MailAttachments.kindOf] rather than here.
            if (part.isMimeType("text/plain") || part.isMimeType("text/html")) {
                if (fileNameOf(part).isNullOrBlank()) return
            }

            val saved = part.inputStream.use { stream ->
                store.save(
                    mailId = mailId,
                    fileName = fileNameOf(part),
                    mimeType = part.contentType,
                    isInline = isInline(part),
                    content = stream
                )
            }
            if (saved != null) kept.add(saved)
        } catch (e: Throwable) {
            // One unreadable part is one missing picture, never a lost letter. Deliberately caught
            // per part rather than around the walk, so the rest of the tree is still looked at.
        }
    }

    /**
     * What the sender called this part.
     *
     * `Part.getFileName` reads the disposition's `filename` and falls back to the content type's
     * `name`, but it throws on a malformed header rather than returning null, and malformed
     * headers are ordinary in mail. The name is only ever used to work out what a part is and what
     * to call the file, so failing to read one is not a reason to skip it.
     */
    private fun fileNameOf(part: Part): String? = try {
        part.fileName ?: nameParameter(part.contentType)
    } catch (e: Throwable) {
        null
    }

    private fun nameParameter(contentType: String?): String? = try {
        contentType?.let { ContentType(it).getParameter("name") }
    } catch (e: Throwable) {
        null
    }

    /**
     * Whether the sender says this belongs *inside* the letter rather than beside it.
     *
     * Anything that does not plainly say "attachment" counts as inline, which is the cautious way
     * round: a logo carries `inline`, and a logo with no disposition at all is still a logo. The
     * cost of being wrong this way is a photograph somebody sent with no disposition and under
     * 24 KB going unshown; the cost of the other way is every company logo drawn across her screen
     * as though her daughter had sent it.
     */
    private fun isInline(part: Part): Boolean = try {
        !Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)
    } catch (e: Throwable) {
        true
    }

    /** How many parts of one letter are ever looked at. Far above any real mail. */
    private const val MAX_PARTS = 60
}
