package ch.heuscher.gentleconnect.model

/**
 * What counts as having been seen.
 *
 * The app has exactly one gesture for it: she opens a conversation and reads it. There is no
 * "mark as read" anywhere, and there must not be — housekeeping is the thing this launcher exists
 * to take off her phone. So opening has to clear everything, because nothing else ever will.
 *
 * It did not. Opening marked the single message the list row stood for, which is the newest one,
 * and anything older that had never been opened stayed unread forever. Both signals on her screen
 * — the card at the top of home and the mark on the Message button — ask whether anything from a
 * person is unread, so both stayed lit after she had read every word. The card went on announcing
 * a neighbour who had written the day before. A sign that cannot be cleared teaches her to stop
 * reading it, which costs more than never having shown it.
 */
object Seen {

    /**
     * The messages that opening a conversation has to mark as read.
     *
     * [opened] is passed separately and put first because it is the one message known to be in
     * this conversation at the moment of the tap: on the very first open, the list it is drawn
     * from may not have caught up yet. Duplicates between the two are collapsed by id, so a
     * message is never marked — or acknowledged to its sender — twice.
     *
     * Already-read messages are dropped rather than passed on, because marking is not free: for
     * Threema it sends a read receipt to the person who wrote, and re-acknowledging a message from
     * last week every time she reopens a conversation would be a stream of small lies about when
     * she looked.
     */
    fun whatOpeningClears(opened: Message, inConversation: List<Message>): List<Message> =
        (listOf(opened) + inConversation)
            .distinctBy { it.id }
            .filter { it.isUnread }
}
