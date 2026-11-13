package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.Message
import kotlin.math.abs

object MessageMerger {
    private const val DEDUP_WINDOW_MS = 30_000L

    /**
     * Combines notification messages and bridge messages.
     * Deduplicates across sources: if a bridge message and notification message have the same sender
     * and text within 30 seconds, the bridge message is kept (authoritative).
     * Output list is sorted by timestamp descending.
     */
    fun mergeMessages(notificationMsgs: List<Message>, bridgeMsgs: List<Message>): List<Message> {
        val filteredNotifications = notificationMsgs.filterNot { notif ->
            bridgeMsgs.any { bridge ->
                notif.senderName.equals(bridge.senderName, ignoreCase = true) &&
                        notif.text == bridge.text &&
                        abs(notif.timestamp - bridge.timestamp) <= DEDUP_WINDOW_MS
            }
        }
        return (bridgeMsgs + filteredNotifications).sortedByDescending { it.timestamp }
    }
}
