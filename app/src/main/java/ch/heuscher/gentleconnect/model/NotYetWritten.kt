package ch.heuscher.gentleconnect.model

/**
 * The people she could write to and never has — drawn at the end of the message list.
 *
 * The message list only ever showed people who had written, so somebody she has in her address
 * book and has only ever rung was nowhere on it. Looking for them there, she found nothing, and
 * nothing said why. They now follow the messages, in the order the telephone lists them (her
 * favourites first — see [PhoneBook]), under a line saying there is nothing from them yet.
 *
 * Kept pure: the caller supplies how a person and a conversation are keyed, which needs Android's
 * number formatting, so the rule itself can be tested without a phone.
 */
object NotYetWritten {

    /**
     * [inOrder] without anybody who already has a conversation, or who cannot be written to.
     *
     * @param conversations the keys of every conversation she has, from either side.
     * @param keyOf the conversation a person's messages would be filed under, or null when there
     *   is nothing to file them under — in which case there is nowhere for her message to appear
     *   either, and they are left out.
     * @param reachable whether any route reaches them. A name with no number and no identity is not
     *   offered: writing to it would fail after she had written the whole message.
     */
    fun from(
        inOrder: List<Contact>,
        conversations: Set<String>,
        keyOf: (Contact) -> String?,
        reachable: (Contact) -> Boolean
    ): List<Contact> {
        // One row per conversation. The same person filed twice in the address book — a mobile and
        // a landline under one name, a favourite who is also in the list below — would otherwise
        // be offered twice, and the first is the one in her favourites.
        val seen = HashSet<String>()
        return inOrder.filter { contact ->
            if (!reachable(contact)) return@filter false
            val key = keyOf(contact) ?: return@filter false
            key !in conversations && seen.add(key)
        }
    }
}
