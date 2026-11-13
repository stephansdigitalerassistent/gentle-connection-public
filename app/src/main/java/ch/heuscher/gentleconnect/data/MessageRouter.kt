package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.model.BridgeTransport

/** How a message she writes leaves the phone. Never shown to her. */
enum class SendChannel { THREEMA, WHATSAPP, SMS }

data class SendRoutingDecision(
    val channel: SendChannel,
    val target: String,      // Threema ID, WhatsApp address, or telephone number
    val reason: String       // for logging only, never shown to her
)

/**
 * Picks the way out for a message she is writing first.
 *
 * The companion to [CallRouter], and pure for the same reason: this is the decision that
 * determines whether her message arrives, and it has to be testable without a phone.
 *
 * **Answering is not routed here.** A reply goes back the way the message came — that is
 * already decided by the message itself, and answering an SMS over Threema would arrive from
 * an identity the sender does not recognise. This only applies when she starts.
 */
object MessageRouter {

    /**
     * Whether a text message is a way of reaching somebody. **On again since 2026-08-16.**
     *
     * It was switched off because SMS was the last resort and was reached every single time:
     * nothing on the phone had a Threema ID, no partner record existed, so the two branches above
     * SMS were unreachable and everything she wrote went out over the radio at roaming rates while
     * the app looked like a working messenger. Switching it off made the app smaller and honest.
     *
     * What has changed is that the branches above it now fire. WhatsApp arrives and answers through
     * the bridge, the embedded Threema client sends, and SMS is last in [decide] — so it is reached
     * when it is the only thing that reaches the person, which is what a last resort is for.
     *
     * And it gates more than writing first: [ch.heuscher.gentleconnect.model.canBeAnswered] reads
     * it, so with this false she could not answer a text message at all. Her SMS history is now
     * imported in full, both halves of it, which made that concrete — a year of her sister's texts
     * on her screen with no way to reply to any of them. Somebody texts her; she must be able to
     * text back.
     */
    const val SMS_ENABLED = true

    /**
     * Order: Threema, then WhatsApp, then SMS.
     *
     * Threema first because it is this app's own transport — it costs nothing, it works the
     * same abroad, and it is the one route the app can actually see succeed or fail. WhatsApp
     * second: it goes through the bridge too, but it is somebody else's service. SMS last
     * because it costs money per message and, for someone who cannot be asked to think about
     * that, it should be the fallback rather than the default.
     *
     * Returns null when there is no way to reach the person at all. The caller must not
     * present writing to them as possible.
     */
    fun decide(
        threemaId: String?,
        whatsappId: String?,
        phoneNumber: String?
    ): SendRoutingDecision? {
        val cleanThreemaId = threemaId?.trim()?.ifEmpty { null }
        val cleanWhatsappId = whatsappId?.trim()?.ifEmpty { null }
        val cleanPhoneNumber = phoneNumber?.trim()?.ifEmpty { null }

        return when {
            cleanThreemaId != null -> SendRoutingDecision(
                channel = SendChannel.THREEMA,
                target = cleanThreemaId,
                reason = "threema_id"
            )
            cleanWhatsappId != null -> SendRoutingDecision(
                channel = SendChannel.WHATSAPP,
                target = cleanWhatsappId,
                reason = "whatsapp_address"
            )
            cleanPhoneNumber != null && SMS_ENABLED -> SendRoutingDecision(
                channel = SendChannel.SMS,
                target = cleanPhoneNumber,
                reason = "phone_number"
            )
            else -> null
        }
    }

    /** Whether anything at all can carry a message to this person right now. */
    fun canReach(threemaId: String?, whatsappId: String?, phoneNumber: String?): Boolean =
        decide(threemaId, whatsappId, phoneNumber) != null

    /**
     * The bridge transport for a channel, or null for [SendChannel.SMS] — which does not go
     * through the bridge at all, but leaves the phone directly through the radio.
     */
    fun transportFor(channel: SendChannel): BridgeTransport? = when (channel) {
        SendChannel.THREEMA -> BridgeTransport.THREEMA
        SendChannel.WHATSAPP -> BridgeTransport.WHATSAPP
        SendChannel.SMS -> null
    }
}
