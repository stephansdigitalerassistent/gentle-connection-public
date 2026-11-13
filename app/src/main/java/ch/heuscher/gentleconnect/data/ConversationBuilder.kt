package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.Message
import kotlin.math.abs

/** One row of an open conversation: either something somebody wrote, or the day it was written. */
sealed interface ConversationItem {

    /** A message, hers or theirs. */
    data class Entry(val message: Message) : ConversationItem

    /**
     * The day the messages below it belong to.
     *
     * The only time marker in a conversation. A timestamp on every message turns a conversation
     * into a log, and the exact minute her daughter wrote is not something she is ever going to
     * need — whereas "was this today or last week" is the thing she asks.
     */
    data class Day(val timestamp: Long) : ConversationItem
}

/**
 * Turns the messages of one conversation into the rows the screen draws.
 *
 * Oldest at the top, newest at the bottom, which is the direction a conversation is read in and
 * the opposite of the flat message list. Pure, because this is where a message either appears in
 * the right place or quietly appears in the wrong one.
 */
object ConversationBuilder {

    /** Same window [MessageMerger] uses; two sources describing one message agree to about here. */
    private const val DEDUP_WINDOW_MS = 30_000L

    /**
     * Whether both came the same way: same channel, same messenger, and ids from the same scheme
     * (`threema_…`, `whatsapp_…`, `sms_…`). A backup import shares the live bridge's channel and
     * transport but numbers its ids differently, so it still counts as another route.
     */
    private fun sameRoute(a: Message, b: Message): Boolean =
        a.channel == b.channel &&
            a.transport == b.transport &&
            a.id.substringBefore('_', "") == b.id.substringBefore('_', "") &&
            a.id.contains('_')

    /**
     * @param dayOf maps a timestamp to something identifying its calendar day. Passed in because a
     *   calendar day needs a time zone and a time zone needs Android, and this must stay testable.
     */
    fun build(messages: List<Message>, dayOf: (Long) -> Long): List<ConversationItem> {
        if (messages.isEmpty()) return emptyList()

        val resolved = resolveQuotes(messages)

        val kept = ArrayList<Message>(resolved.size)
        resolved.sortedBy { it.timestamp }.distinctBy { it.id }.forEach { message ->
            // The same message can reach us twice by different routes — the server-side bridge and
            // her SMS inbox both carry it, under ids that are numbered independently. Two identical
            // bubbles a second apart read as the sender having written twice.
            //
            // But only across *different* routes. One route numbers its own messages, so two ids
            // from it are two messages, whatever they say. Folding by text alone ate photo bursts:
            // nine captionless photos from one sender in three seconds all read `[Foto]`, and she
            // was shown the first and none of the other eight (Hartmanns group, 30 Sep 2026).
            val alreadyThere = kept.any { existing ->
                !sameRoute(existing, message) &&
                    existing.direction == message.direction &&
                    existing.text == message.text &&
                    existing.senderName.equals(message.senderName, ignoreCase = true) &&
                    abs(existing.timestamp - message.timestamp) <= DEDUP_WINDOW_MS
            }
            if (!alreadyThere) kept.add(message)
        }

        val items = ArrayList<ConversationItem>(kept.size + 4)
        var currentDay: Long? = null
        kept.forEach { message ->
            val day = dayOf(message.timestamp)
            if (day != currentDay) {
                currentDay = day
                items.add(ConversationItem.Day(message.timestamp))
            }
            items.add(ConversationItem.Entry(message))
        }
        return items
    }

    /**
     * Resolves who a quote was answering and what they said, from the messages in this conversation.
     *
     * Derived at read time for the same reason group names are: if a contact is renamed in her
     * address book, quotes of their messages should show the new name everywhere at once. When
     * the quoted message is not in the list and nothing was carried on the wire, the fields stay
     * null and no card is drawn.
     */
    private fun resolveQuotes(messages: List<Message>): List<Message> {
        if (messages.none { it.quotedId != null }) return messages

        val byId = HashMap<String, Message>(messages.size)
        val byWireId = HashMap<String, Message>(messages.size * 2)
        for (m in messages) {
            byId[m.id] = m
            m.wireMessageId?.let { wire ->
                byWireId[wire] = m
                reverseHexBytes(wire)?.let { reversed -> byWireId[reversed] = m }
            }
        }

        return messages.map { message ->
            val targetId = message.quotedId ?: return@map message
            val candidate = byId[targetId]
                ?: byWireId[targetId]
                ?: targetId.removePrefix("threema_").let { prefixRemoved ->
                    byWireId[prefixRemoved] ?: reverseHexBytes(prefixRemoved)?.let { byWireId[it] }
                }
                ?: targetId.removePrefix("whatsapp_").let { byWireId[it] }

            // Skip a message that quotes itself — Threema's developer menu can send a self-quote,
            // which would otherwise draw its own words above itself.
            val quoted = candidate?.takeIf { it.id != message.id }
            if (quoted != null) {
                message.copy(
                    quotedSenderName = if (quoted.isOutgoing) null else quoted.senderName,
                    quotedText = quoted.text,
                    quotedIsHers = quoted.isOutgoing
                )
            } else {
                message
            }
        }
    }

    private fun reverseHexBytes(hex: String): String? {
        if (hex.length != 16) return null
        val sb = StringBuilder(16)
        for (i in 14 downTo 0 step 2) {
            sb.append(hex[i]).append(hex[i + 1])
        }
        return sb.toString()
    }
}
