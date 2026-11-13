package ch.heuscher.gentleconnect.model

/**
 * Something that came with a letter and can be put on the screen: a photograph, or a PDF.
 *
 * Her post has shown the words of a letter and nothing else since it shipped. What her family and
 * her doctor's surgery actually send is often not words — a photograph of the grandchildren, an
 * appointment as a PDF, a scan of a form — and all of that arrived as a letter that said nothing
 * or almost nothing, with no sign that anything had been left out. That is the one failure she
 * cannot detect: a letter she can read looks complete.
 *
 * Only the two kinds this app can actually draw are ever kept — see [MailAttachmentKind]. A file
 * she cannot open on this phone is not something to put in front of her: there is nowhere to send
 * it, no other app to hand it to, and a row reading "Rechnung.xlsx" would be a dead end with a
 * name on it.
 *
 * [path] is app-private storage, and like every other media path in this app it may point at
 * nothing: the file may have failed to save, or the letter may predate this existing. Everything
 * that draws one has to be happy with the letter's words alone.
 */
data class MailAttachment(
    /** What the sender called it. Sanitised — see [MailAttachments.safeFileName]. */
    val fileName: String,
    val mimeType: String,
    val path: String
) {
    val kind: MailAttachmentKind?
        get() = MailAttachments.kindOf(mimeType, fileName)

    val isPicture: Boolean get() = kind == MailAttachmentKind.PICTURE

    val isPaper: Boolean get() = kind == MailAttachmentKind.PAPER
}

/**
 * The two things that can be shown, named for what she sees rather than for the file format.
 *
 * [PAPER] is a PDF, which on this screen is drawn as its pages, one under the other — the same
 * thing the sender put in the envelope, and nothing she has to open.
 */
enum class MailAttachmentKind { PICTURE, PAPER }

/**
 * The rules about what comes with a letter: what is worth keeping, what to call it, and how it
 * survives a restart.
 *
 * Pure and in the model, so CI can prove all of it. The rule that decides whether a company logo
 * is drawn as though it were a photograph of her family is exactly the kind that quietly stops
 * holding — see [isWorthKeeping].
 */
object MailAttachments {

    /**
     * The picture or the paper this is, or null when it is neither.
     *
     * The declared type is asked first and the name second, because a mail server is free to send
     * anything as `application/octet-stream` — Outlook does it for whatever it did not recognise,
     * and a photograph labelled that way is still a photograph. The name is then the only thing
     * left to go on.
     *
     * SVG is deliberately not a picture here: Android's decoder cannot open one, so keeping it
     * would mean a blank space on her screen where something had been promised.
     */
    fun kindOf(mimeType: String?, fileName: String?): MailAttachmentKind? {
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val extension = fileName?.substringAfterLast('.', "")?.trim()?.lowercase().orEmpty()

        if (type == "application/pdf" || type == "application/x-pdf") return MailAttachmentKind.PAPER
        if (type == "image/svg+xml") return null
        if (type in PICTURE_TYPES) return MailAttachmentKind.PICTURE

        if (extension == "pdf") return MailAttachmentKind.PAPER
        if (extension in PICTURE_EXTENSIONS) return MailAttachmentKind.PICTURE
        return null
    }

    /**
     * Whether this is worth putting under her letter, decided once the size is actually known.
     *
     * Two limits, both about what a phone and a screen can carry:
     *
     *  - **Nothing enormous.** A holiday film sent as an attachment is tens of megabytes, pulled
     *    over her flat's connection by a launcher that also has to stay alive.
     *  - **Nothing tiny that came inline.** Every letter from every company carries its logo, its
     *    social-media icons and a one-pixel tracking dot as inline images, all with file names.
     *    Drawn under the letter at the width of her screen a 3 KB logo is a smear sitting exactly
     *    where a photograph somebody sent her would sit. Size is the only thing that separates
     *    the two without a rule that judges the sender — and a picture somebody actually took is
     *    never this small.
     *
     * A file somebody *attached* is kept whatever its size: attaching something is a deliberate
     * act, and a small picture attached on purpose is still what they meant to send.
     */
    fun isWorthKeeping(kind: MailAttachmentKind?, isInline: Boolean, sizeBytes: Long): Boolean {
        if (kind == null) return false
        if (sizeBytes <= 0L || sizeBytes > MAX_BYTES) return false
        if (isInline && sizeBytes < SMALLEST_INLINE_PICTURE) return false
        return true
    }

    /** Nothing larger than this is kept. Ten megabytes. */
    const val MAX_BYTES: Long = 10L * 1024 * 1024

    /**
     * Below this, an inline image is decoration rather than something somebody sent. Chosen off
     * the letters actually on her phone: logos and icons sit in the low single-digit kilobytes,
     * and the smallest photograph a telephone camera produces is an order of magnitude above it.
     */
    const val SMALLEST_INLINE_PICTURE: Long = 24L * 1024

    /** At most this many under one letter, so a mailing with forty pictures cannot own the screen. */
    const val MAX_PER_LETTER: Int = 6

    /**
     * A file name that is safe to put on disk and readable if it is ever shown.
     *
     * Mail file names arrive from strangers: `../../databases/gentle_connection.db` is a valid
     * MIME file name, and so is one four hundred characters long. Everything that is not plainly
     * part of a name becomes an underscore.
     */
    fun safeFileName(name: String?): String {
        val trimmed = name?.trim().orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val cleaned = trimmed.map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' || c == ' ') c else '_'
        }.joinToString("").trim().trim('.')
        if (cleaned.isBlank()) return FALLBACK_NAME
        if (cleaned.length <= MAX_NAME_LENGTH) return cleaned

        val extension = cleaned.substringAfterLast('.', "")
        val stem = cleaned.take(MAX_NAME_LENGTH - extension.length - 1).trim().trim('.')
        return if (extension.isBlank() || stem.isBlank()) {
            cleaned.take(MAX_NAME_LENGTH)
        } else {
            "$stem.$extension"
        }
    }

    /**
     * The folder one letter's files live in.
     *
     * The letter's id with everything unusual taken out of it, and its hash on the end. That id is
     * a Message-ID chosen by whoever sent the letter — it contains `@`, often `/`, and two
     * different ids can differ only in the characters that get taken out, so the hash is what
     * stops one letter's photographs turning up under another's.
     */
    fun folderNameFor(mailId: String): String {
        val cleaned = mailId.map { c -> if (c.isLetterOrDigit()) c else '_' }
            .joinToString("")
            .takeLast(40)
        return cleaned + "_" + Integer.toHexString(mailId.hashCode())
    }

    /**
     * The list as one database column, and back.
     *
     * A separate table would be the tidier answer and is not worth a migration that walks every
     * letter she has: this is a short list, written once when the letter is fetched and read only
     * when she opens it. The separators are the ASCII record and unit separators, which cannot
     * occur in a sanitised name or in a path this app chose.
     *
     * Anything that does not decode cleanly is dropped rather than guessed at. A malformed row
     * means one letter shows no attachment, which is what every letter did before this existed.
     */
    fun encode(attachments: List<MailAttachment>): String =
        attachments.joinToString(RECORD) { "${it.path}$UNIT${it.mimeType}$UNIT${it.fileName}" }

    fun decode(stored: String?): List<MailAttachment> {
        if (stored.isNullOrBlank()) return emptyList()
        return stored.split(RECORD).mapNotNull { record ->
            val fields = record.split(UNIT)
            if (fields.size != 3) return@mapNotNull null
            val path = fields[0]
            if (path.isBlank()) return@mapNotNull null
            MailAttachment(fileName = fields[2], mimeType = fields[1], path = path)
        }
    }

    /** ASCII record separator, between two attachments. */
    private val RECORD = 30.toChar().toString()

    /** ASCII unit separator, between the fields of one. */
    private val UNIT = 31.toChar().toString()

    private const val FALLBACK_NAME = "attachment"
    private const val MAX_NAME_LENGTH = 80

    private val PICTURE_TYPES = setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/gif",
        "image/webp",
        "image/bmp",
        "image/heic",
        "image/heif"
    )

    private val PICTURE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"
    )
}
