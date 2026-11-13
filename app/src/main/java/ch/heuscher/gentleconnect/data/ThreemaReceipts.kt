package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.data.threema.ThreemaClient
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.ThreemaReceipt

/**
 * Telling whoever wrote that their message arrived, and later that it was read.
 *
 * Before this existed they saw one grey tick for ever: the message had reached Threema's server
 * and, as far as their telephone could tell, gone nowhere. For somebody who cannot reliably reply,
 * the ticks are what her family reads instead — and they were saying that nothing had got through.
 *
 * One place, because the two receipts are sent from two: delivery from the arrival pipeline the
 * moment a message lands, reading from the ViewModel when she opens it. The rule about which
 * messages may be acknowledged at all is the same for both, and a copy of it would be a rule that
 * only half the app follows.
 *
 * One-to-one Threema only. Groups have their own receipt type that Threema's own clients do not
 * send for delivery, and a message of hers has nobody to acknowledge it to. Everything that is not
 * one of those is dropped silently — a receipt is a courtesy, and a failed one must never become
 * something on her screen.
 */
object ThreemaReceipts {

    fun send(client: ThreemaClient?, message: Message, receipt: Int) {
        if (message.transport != BridgeTransport.THREEMA) return
        if (message.isOutgoing || message.isGroupMessage) return
        val sender = message.senderId?.takeIf { it.isNotBlank() } ?: return
        val body = ThreemaReceipt.bodyForStoredId(
            receipt = receipt,
            storedId = message.id,
            prefix = OnDeviceMessageMapper.ID_PREFIX
        ) ?: return

        client?.sendRawMessage(sender, ThreemaReceipt.MESSAGE_TYPE, body)
    }
}
