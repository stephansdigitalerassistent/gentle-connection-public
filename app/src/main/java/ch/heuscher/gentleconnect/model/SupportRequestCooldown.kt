package ch.heuscher.gentleconnect.model

/**
 * Pure decision logic for re-alerting ("Noch einmal Bescheid geben") cooldown.
 *
 * Spec section 3.2 & 8:
 * - Re-alerts everyone at most once per 5 minutes.
 * - Within the cooldown window, the button is simply not shown.
 */
object SupportRequestCooldown {

    const val COOLDOWN_MS = 5L * 60 * 1000 // 5 minutes

    /**
     * Checks whether re-alerting is allowed given the timestamp of the last alert.
     *
     * @param lastAlertAt timestamp of the initial request or the most recent re-alert
     * @param now current timestamp in milliseconds
     */
    fun canReAlert(lastAlertAt: Long, now: Long): Boolean {
        return now - lastAlertAt >= COOLDOWN_MS
    }

    /**
     * Returns the remaining cooldown duration in milliseconds, or 0 if cooldown has elapsed.
     */
    fun remainingCooldownMs(lastAlertAt: Long, now: Long): Long {
        val remaining = COOLDOWN_MS - (now - lastAlertAt)
        return if (remaining > 0) remaining else 0L
    }
}
