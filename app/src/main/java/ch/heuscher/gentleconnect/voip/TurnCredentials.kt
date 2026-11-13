package ch.heuscher.gentleconnect.voip

import java.util.Date

/**
 * Holds TURN server URLs and authentication credentials obtained from the Threema directory server.
 *
 * TURN credentials expire; respect expirationDate and refetch before it lapses. Cache in memory only.
 */
data class TurnCredentials(
    val turnUrls: List<String>,
    val turnUrlsDualStack: List<String>,
    val turnUsername: String,
    val turnPassword: String,
    val expirationDate: Date
) {
    /**
     * Checks if credentials are valid (not expired, considering a safety margin in seconds).
     */
    fun isValid(now: Date = Date(), marginSeconds: Long = 60): Boolean {
        if (turnUrls.isEmpty() && turnUrlsDualStack.isEmpty()) return false
        if (turnUsername.isBlank() || turnPassword.isBlank()) return false
        val threshold = expirationDate.time - (marginSeconds * 1000L)
        return now.time < threshold
    }

    /**
     * Combines all available TURN URLs (standard and dual stack).
     */
    fun allTurnUrls(): List<String> {
        val combined = mutableListOf<String>()
        combined.addAll(turnUrls)
        combined.addAll(turnUrlsDualStack)
        return combined.distinct()
    }
}
