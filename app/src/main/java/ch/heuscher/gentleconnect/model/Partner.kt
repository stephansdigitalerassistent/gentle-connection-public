package ch.heuscher.gentleconnect.model

/**
 * Someone she has already communicated with, and may therefore contact first.
 * Stored under `devices/{deviceId}/partners/{partnerId}` in Firestore, where the document
 * id is `"<transport>:<id>"` — a Threema ID and a WhatsApp number could otherwise collide.
 */
data class Partner(
    /** Address on [transport]: a Threema ID, a WhatsApp number, etc. */
    val id: String,
    val transport: BridgeTransport = BridgeTransport.UNKNOWN,
    val displayName: String,
    val phoneNumber: String? = null,
    val photoUrl: String? = null,
    val lastContactAt: Long = 0L,
    val canInitiate: Boolean = true
)

/**
 * Builds a [Recipient] from this partner for messaging and photo sending.
 */
fun Partner.toRecipient(): Recipient = Recipient(
    name = displayName,
    photoUri = photoUrl,
    threemaId = id.takeIf { transport == BridgeTransport.THREEMA },
    whatsappId = id.takeIf { transport == BridgeTransport.WHATSAPP },
    phoneNumber = phoneNumber
)
