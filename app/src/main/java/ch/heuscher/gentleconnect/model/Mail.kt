package ch.heuscher.gentleconnect.model

/**
 * Read-only mail item model.
 */
data class Mail(
    val id: String,
    val senderName: String,
    val senderAddress: String,
    val subject: String,
    val snippet: String,
    val timestamp: Long,
    val isRead: Boolean = false,
    val body: String = "",
    val messageId: String? = null,
    val uid: Long? = null,
    val uidValidity: Long? = null,
    /**
     * Whether the sender is somebody she knows — in her address book, or on the caregiver
     * allowlist.
     *
     * This used to be called `isVisible` and it used to decide whether the letter reached her
     * Post screen at all: mail from anybody else was fetched, stored, and never shown. It no
     * longer hides anything. Every letter is shown; this decides how loudly.
     *
     * The reason for the change is that hiding is the one failure she cannot detect. A letter
     * that never appears leaves nothing to ask about, so a doctor's surgery writing from an
     * address nobody had stored simply did not exist. Showing everything makes the failure the
     * other way round — noise, which she can see and complain about.
     */
    val isFromKnownSender: Boolean = true,
    /**
     * The pictures and the paper that came with it — see [MailAttachment].
     *
     * Empty for nearly every letter, and empty is not a failure: most mail carries nothing but
     * words, most of what it does carry is a logo, and every letter stored before this existed has
     * none. The reader draws these under the words and is happy without them.
     */
    val attachments: List<MailAttachment> = emptyList()
) {
    val isUnread: Boolean
        get() = !isRead
}
