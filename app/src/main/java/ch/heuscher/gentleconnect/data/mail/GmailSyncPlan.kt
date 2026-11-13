package ch.heuscher.gentleconnect.data.mail

/**
 * Gmail message ids stored in the `uid` column.
 *
 * A Gmail id is a 64-bit number written in hex, so it fits a Long exactly — negative for the top
 * half, which does not matter for an identifier. Everything read through the Gmail API is filed
 * under [UID_VALIDITY], so it can never be mistaken for a UID from the IMAP sync this replaced.
 */
object GmailIds {
    const val UID_VALIDITY = 0L

    fun toUid(id: String): Long? =
        runCatching { java.lang.Long.parseUnsignedLong(id, 16) }.getOrNull()

    fun fromUid(uid: Long): String = java.lang.Long.toUnsignedString(uid, 16)
}

data class GmailSyncPlan(
    /** Gmail ids to download, newest first. */
    val toFetch: List<String>,
    /** Stored letters that Gmail no longer marks unread. */
    val seenUids: Set<Long>
)

object GmailSyncPlanner {

    /**
     * @param inboxIds the newest inbox ids, newest first, as Gmail lists them.
     * @param unreadIds the inbox ids Gmail marks unread.
     * @param storedUids what is already in the database under [GmailIds.UID_VALIDITY].
     */
    fun plan(
        inboxIds: List<String>,
        unreadIds: Set<String>,
        storedUids: Set<Long>,
        fetchCap: Int
    ): GmailSyncPlan {
        val toFetch = inboxIds
            .filter { id -> GmailIds.toUid(id).let { it != null && it !in storedUids } }
            .distinct()
            .take(fetchCap.coerceAtLeast(0))

        // Only letters still in the listed window: one that has left the inbox, or scrolled past
        // the window, says nothing either way about whether she has read it.
        val seenUids = inboxIds
            .filter { it !in unreadIds }
            .mapNotNull { GmailIds.toUid(it) }
            .filter { it in storedUids }
            .toSet()

        return GmailSyncPlan(toFetch = toFetch, seenUids = seenUids)
    }
}
