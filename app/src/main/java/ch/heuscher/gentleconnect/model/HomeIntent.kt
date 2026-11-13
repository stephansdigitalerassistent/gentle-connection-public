package ch.heuscher.gentleconnect.model

import android.content.Intent

/**
 * Whether an incoming intent or key represents a request to go to the home screen.
 *
 * As the telephone's default launcher, Android delivers [Intent.ACTION_MAIN] with
 * [Intent.CATEGORY_HOME] to the running activity when the user presses the system Home
 * button (or performs the home gesture). Tapping the launcher icon from the app drawer or
 * recents also arrives as [Intent.ACTION_MAIN] with [Intent.CATEGORY_LAUNCHER].
 *
 * Kept pure so it can be tested without an Android device.
 */
object HomeIntent {

    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val CATEGORY_HOME = "android.intent.category.HOME"
    const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

    /**
     * Whether this intent asks to return to the launcher's home screen.
     *
     * Arrival and missed-call intents take precedence: an incoming message or missed call
     * lighting the screen or tapped from a notification lands on that message or the call log,
     * not on home.
     */
    fun isHome(
        action: String?,
        categories: Collection<String>?,
        hasAttention: Boolean = false,
        hasMissedCall: Boolean = false
    ): Boolean {
        if (hasAttention || hasMissedCall) return false
        if (categories?.contains(CATEGORY_HOME) == true) return true
        if (action == ACTION_MAIN &&
            (categories.isNullOrEmpty() || categories.contains(CATEGORY_LAUNCHER))
        ) {
            return true
        }
        return false
    }

    /**
     * Whether an [Intent] asks to return to the launcher's home screen.
     */
    fun isHome(intent: Intent?, attentionExtra: String, missedCallExtra: String): Boolean {
        if (intent == null) return false
        val hasAttention = intent.getBooleanExtra(attentionExtra, false)
        val hasMissedCall = intent.getBooleanExtra(missedCallExtra, false)
        return isHome(
            action = intent.action,
            categories = intent.categories,
            hasAttention = hasAttention,
            hasMissedCall = hasMissedCall
        )
    }
}
