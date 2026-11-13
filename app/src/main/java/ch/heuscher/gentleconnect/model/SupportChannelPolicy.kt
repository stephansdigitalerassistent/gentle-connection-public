package ch.heuscher.gentleconnect.model

/** Channel used for sending support alerts. */
enum class SupportChannel {
    THREEMA,
    WHATSAPP,
    SMS
}

/**
 * Pure channel decision policy for support alerts.
 *
 * Spec section 4.2 & 8:
 * - Messenger (Threema if threemaId, else WhatsApp if whatsAppNumber) first when internet is available.
 * - SMS backup immediately if:
 *     1. The phone has no internet (CallRouter.hasInternet is false).
 *     2. The member has no messenger configured.
 * - SMS fallback if messenger send was not confirmed within 60 seconds.
 * - Roaming does NOT suppress SMS! While `RemoteSettings.threemaOnlyWhenRoaming` restricts voice
 *   calls abroad to protect against high per-minute costs, a help request is worth an SMS abroad.
 *   This is an explicit exception and must remain unsuppressed.
 * - Retry interval when nothing sent at all: every 2 min for 30 min, then every 10 min up to 2 h.
 */
object SupportChannelPolicy {

    const val MESSENGER_CONFIRMATION_TIMEOUT_MS = 60_000L
    const val RETRY_PHASE_ONE_MAX_MS = 30L * 60 * 1000 // 30 minutes
    const val RETRY_PHASE_TWO_MAX_MS = 120L * 60 * 1000 // 2 hours
    const val RETRY_PHASE_ONE_INTERVAL_MS = 2L * 60 * 1000 // 2 minutes
    const val RETRY_PHASE_TWO_INTERVAL_MS = 10L * 60 * 1000 // 10 minutes

    /**
     * Determines the initial channel to contact a group member on.
     *
     * @param member the support group member
     * @param hasInternet whether the device currently has active, validated internet
     * @param isRoaming whether the phone is currently roaming (never suppresses SMS)
     * @param threemaOnlyWhenRoaming the remote setting for calls (never suppresses SMS)
     */
    fun selectInitialChannel(
        member: SupportMember,
        hasInternet: Boolean,
        isRoaming: Boolean = false,
        threemaOnlyWhenRoaming: Boolean = false
    ): SupportChannel {
        // Roaming does NOT suppress SMS for support alerts. threemaOnlyWhenRoaming
        // is about voice call cost; a help request is worth an SMS abroad.
        if (!hasInternet) return SupportChannel.SMS
        return when {
            !member.threemaId.isNullOrBlank() -> SupportChannel.THREEMA
            !member.whatsAppNumber.isNullOrBlank() -> SupportChannel.WHATSAPP
            else -> SupportChannel.SMS
        }
    }

    /**
     * Decides whether an unconfirmed messenger transmission should fall back to SMS.
     *
     * @param channel the channel that was used
     * @param confirmedSent whether the messenger confirmed transmission (server ack / outbox success)
     * @param elapsedSinceSendMs time in milliseconds since the send was initiated
     */
    fun shouldFallbackToSms(
        channel: SupportChannel,
        confirmedSent: Boolean,
        elapsedSinceSendMs: Long
    ): Boolean {
        if (channel == SupportChannel.SMS) return false
        if (confirmedSent) return false
        return elapsedSinceSendMs >= MESSENGER_CONFIRMATION_TIMEOUT_MS
    }

    /**
     * Calculates the retry interval in milliseconds when nothing could be sent at all,
     * or returns null if the 2-hour window has expired.
     *
     * @param elapsedSinceRequestMs time in milliseconds since the request was initiated
     */
    fun nextRetryIntervalMs(elapsedSinceRequestMs: Long): Long? {
        return when {
            elapsedSinceRequestMs < RETRY_PHASE_ONE_MAX_MS -> RETRY_PHASE_ONE_INTERVAL_MS
            elapsedSinceRequestMs < RETRY_PHASE_TWO_MAX_MS -> RETRY_PHASE_TWO_INTERVAL_MS
            else -> null
        }
    }
}
