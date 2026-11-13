package ch.heuscher.gentleconnect.model

/**
 * Reconciling messages that arrive from a backup with the ones already on the phone.
 *
 * Room's insert for messages replaces on a clashing id, which makes importing twice harmless as
 * far as the database is concerned — no duplicate rows. That is not the same as harmless as far
 * as she is concerned, and the difference is this file.
 *
 * A message she has already read is stored here as read. The copy inside a backup taken last
 * month says unread, because it was unread when the backup was made. Handing those rows straight
 * to the DAO would mark a month of conversations unread again: her home screen would fill with
 * things demanding attention that she has already dealt with. Restoring a backup must never do
 * that, and it is exactly what "idempotent" hides if you only count rows.
 *
 * So the rules are:
 *
 *  - a message only in the backup is restored;
 *  - a message only on the phone is left alone — a backup is not a statement about what should
 *    no longer exist, and importing an old one must not delete this morning's messages;
 *  - a message in both keeps what the phone knows, except that reading is monotonic: read on
 *    either side means read. Nothing she has attended to becomes unattended.
 */
object MessageArchive {

    /**
     * [onThePhone] merged with [fromTheBackup], newest first.
     *
     * Merging the same backup again returns the same list — that is the property this exists for,
     * and [MessageArchiveTest] pins it.
     */
    fun merge(onThePhone: List<Message>, fromTheBackup: List<Message>): List<Message> {
        val byId = LinkedHashMap<String, Message>(onThePhone.size + fromTheBackup.size)
        onThePhone.forEach { byId[it.id] = it }

        fromTheBackup.forEach { restored ->
            val existing = byId[restored.id]
            byId[restored.id] = if (existing == null) {
                restored
            } else {
                // The phone's row wins, because it is the newer statement about this message —
                // it may carry a reaction, or a read mark, that the backup predates.
                existing.copy(isRead = existing.isRead || restored.isRead)
            }
        }

        return byId.values.sortedByDescending { it.timestamp }
    }

    /**
     * The messages in [onThePhone] that [fromTheBackup] would actually add.
     *
     * For telling a caregiver "this will restore 214 messages" before doing it, rather than after.
     */
    fun wouldRestore(onThePhone: List<Message>, fromTheBackup: List<Message>): List<Message> {
        val known = onThePhone.mapTo(HashSet(onThePhone.size)) { it.id }
        return fromTheBackup.filterNot { it.id in known }
    }
}
