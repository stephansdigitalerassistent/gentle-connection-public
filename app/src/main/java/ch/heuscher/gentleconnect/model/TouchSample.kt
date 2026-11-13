package ch.heuscher.gentleconnect.model

/**
 * Whether a touch on the screen did something, did nothing, or was not a completed gesture at all
 * (such as a cancelled touch or a drag in a trace where scrolling cannot be told from a dead drag).
 */
enum class TouchOutcome { ANSWERED, UNANSWERED, NEITHER }

/**
 * One completed touch gesture, from the moment the finger landed ([downAt]) to the moment it
 * lifted ([upAt]), with where it landed in density-independent pixels and which screen was showing.
 *
 * Carrying the coordinates, timings, screen name and [outcome] together lets [AgitationTracker]
 * tell the difference between four frantic dead taps on the same spot, three dead taps in the
 * top corner where the back arrow normally lives, and a chain of taps that simply carried through
 * across screen changes as each new screen's button appeared under her finger.
 */
data class TouchSample(
    val downAt: Long,        // wall clock ms when the finger landed
    val upAt: Long,          // wall clock ms when it lifted
    val xDp: Float,
    val yDp: Float,
    val screenWidthDp: Float, // 0 = unknown (disables the right-hand corner)
    val screen: String,
    val isTap: Boolean,      // TapFilter said tap AND not on keypad or reaction zones
    val outcome: TouchOutcome
)
