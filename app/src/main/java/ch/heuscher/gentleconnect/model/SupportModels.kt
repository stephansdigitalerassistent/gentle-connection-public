package ch.heuscher.gentleconnect.model

/**
 * A member of the support group configured on the caregiver portal.
 *
 * Stored in [RemoteSettings.supportGroup]. Display order matches list order;
 * the first member's photo is shown on the support button in idle state.
 *
 * @param id unique identifier
 * @param name "Anna" — used on her screens and in the alert messages
 * @param phoneNumber E.164 phone number; required as SMS backup
 * @param threemaId optional Threema ID
 * @param whatsAppNumber optional WhatsApp number (E.164)
 * @param buttonPhotoUri optional close-up face photo URI; only the first member's is shown on the button
 * @param contactId the address-book contact this member was picked from on the portal. When set,
 *   everything but the id is filled in on the phone — see [SupportGroupResolver].
 */
data class SupportMember(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val threemaId: String? = null,
    val whatsAppNumber: String? = null,
    val buttonPhotoUri: String? = null,
    val contactId: String? = null,
    /**
     * Their remaining numbers, so that a call back is recognised as theirs.
     *
     * [phoneNumber] is the one number to write to and dial — see
     * [ch.heuscher.gentleconnect.model.ContactPhone] — but somebody who rings her back after a
     * help request rings from whichever telephone is nearest, and answering "I don't know who
     * that is" to the son who is calling because he was alerted is the whole feature failing at
     * the last step.
     */
    val otherNumbers: List<String> = emptyList()
)

/** Lifecycle status of a support request. */
enum class SupportStatus {
    OPEN,
    CLAIMED,
    CLOSED
}

/** Transport / source that claimed an open support request. */
enum class SupportClaimVia {
    REPLY,
    CALL,
    PORTAL
}

/** Delivery attempt / status for one support group member. */
data class SupportDelivery(
    val memberId: String,
    val channel: String, // "threema" | "whatsapp" | "sms"
    val state: String,   // "sent" | "failed" | "read"
    val at: Long
)

/** Location attached to a support request (only if location sharing is enabled). */
data class SupportLocation(
    val lat: Double,
    val lon: Double,
    val accuracyMetres: Int?,
    val at: Long
)

/** Pure snapshot of phone status at the moment support was pressed. */
data class SupportPhoneStatus(
    val battery: Int,
    val charging: Boolean,
    val network: String,
    val roaming: Boolean,
    val voiceService: Boolean = true,
    val threema: String,
    val whatsapp: String,
    val faults: List<String> = emptyList(),
    val appVersion: Long = 0L
)

/**
 * A support request created when she confirms the support button question.
 * Mirrors Firestore path `devices/{deviceId}/supportRequests/{requestId}`.
 */
data class SupportRequest(
    val id: String,
    val createdAt: Long,
    val screen: String,
    val status: SupportStatus = SupportStatus.OPEN,
    /** The member id of whoever took it; null when nobody has, or when a portal user did. */
    val claimedBy: String? = null,
    /** Their name as shown to her and on the portal — also set for a portal user who is not a member. */
    val claimedByName: String? = null,
    val claimedVia: SupportClaimVia? = null,
    val claimedAt: Long? = null,
    val claimerCallEndedAt: Long? = null,
    val location: SupportLocation? = null,
    val phoneStatus: SupportPhoneStatus? = null,
    val deliveries: List<SupportDelivery> = emptyList(),
    val repeats: List<Long> = emptyList(),
    val missedCallMemberName: String? = null
)

/** Visual state of the support button in headers and on Home. */
sealed class SupportButtonState {
    /** No support group configured: button is not shown, 56 dp spacer kept. */
    object None : SupportButtonState()

    /** Idle: first member's face, blue ring. */
    data class Idle(val firstMember: SupportMember) : SupportButtonState()

    /** Alert sent, not yet claimed: first member's face, blue ring, green tick badge. */
    data class Sent(val firstMember: SupportMember, val requestId: String) : SupportButtonState()

    /** Claimed: claimer's face, blue ring, green tick badge. */
    data class Claimed(val claimer: SupportMember, val requestId: String) : SupportButtonState()
}
