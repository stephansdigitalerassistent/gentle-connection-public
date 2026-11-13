package ch.heuscher.gentleconnect.reply

import ch.heuscher.gentleconnect.model.Message

/**
 * Interface defining the strategy for sending a reply (emoji or short text) to an incoming message.
 */
interface MessageReplier {
    /**
     * Primary reply mechanism: send a quick emoji reaction.
     * @return true once the network has confirmed it went, not merely that it was accepted.
     */
    suspend fun replyWithEmoji(message: Message, emoji: String): Boolean

    /**
     * Secondary reply mechanism: send a short text string.
     * @return true once the network has confirmed it went.
     */
    suspend fun replyWithText(message: Message, text: String): Boolean

    /**
     * Send to an address with no incoming message behind it — this is her writing first.
     * @return true once the network has confirmed it went.
     */
    suspend fun sendDirectText(address: String, text: String): Boolean
}
