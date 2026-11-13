package ch.heuscher.gentleconnect.model

/**
 * What is remembered between sensor readings.
 *
 * `day` is the start-of-day millis the counts belong to.
 */
data class StepState(
    val day: Long,
    val today: Long,
    val yesterday: Long?,
    val lastRaw: Long
)

/**
 * What the home screen shows.
 *
 * `today` null = still collecting (< threshold).
 */
data class StepsShown(
    val today: Long?,
    val yesterday: Long?
)

/**
 * Pure counting rules and presentation logic for her daily steps.
 *
 * Keeps track of steps across days and reboots without requiring any Android framework
 * dependencies. The hardware step counter returns a cumulative count since the phone booted,
 * so every reading is folded against the last known baseline to produce a delta.
 */
object StepDays {
    const val TODAY_THRESHOLD = 3000L

    /**
     * Folds one TYPE_STEP_COUNTER reading (`raw`, cumulative since boot) into [state].
     * `today` = start of the day `now` is in; `previousDay` = start of the day before (both
     * computed by the caller with Calendar so DST days are right).
     */
    fun advance(state: StepState?, raw: Long, today: Long, previousDay: Long): StepState {
        if (state == null) {
            return StepState(
                day = today,
                today = 0L,
                yesterday = null,
                lastRaw = raw
            )
        }

        // A smaller reading means the phone rebooted and the counter restarted at 0,
        // so everything since boot is new.
        val delta = if (raw >= state.lastRaw) raw - state.lastRaw else raw

        return when (state.day) {
            today -> StepState(
                day = today,
                today = state.today + delta,
                yesterday = state.yesterday,
                lastRaw = raw
            )
            previousDay -> StepState(
                day = today,
                today = delta,
                yesterday = state.today,
                lastRaw = raw
            )
            else -> StepState(
                // Older than previousDay (gap of more than a day) or in the future (clock moved back).
                // Delta spans unknown days; undercount rather than overcount.
                day = today,
                today = 0L,
                yesterday = null,
                lastRaw = raw
            )
        }
    }

    /**
     * Rolls a stored state forward to the clock without a new reading (midnight passed, no steps since).
     */
    fun shown(state: StepState?, today: Long, previousDay: Long): StepsShown {
        if (state == null) return StepsShown(today = null, yesterday = null)

        return when (state.day) {
            today -> StepsShown(
                today = state.today.takeIf { it >= TODAY_THRESHOLD },
                yesterday = state.yesterday
            )
            previousDay -> StepsShown(
                today = null,
                yesterday = state.today
            )
            else -> StepsShown(today = null, yesterday = null)
        }
    }

    /**
     * 4512 -> "4'512", 812 -> "812", 1234567 -> "1'234'567", 0 -> "0". Pure string code, no locale.
     */
    fun format(steps: Long): String {
        if (steps < 0L) return "-" + format(-steps)
        val s = steps.toString()
        if (s.length <= 3) return s
        val sb = StringBuilder()
        val remainder = s.length % 3
        if (remainder > 0) {
            sb.append(s.substring(0, remainder))
        }
        for (i in remainder until s.length step 3) {
            if (sb.isNotEmpty()) {
                sb.append('\'')
            }
            sb.append(s.substring(i, i + 3))
        }
        return sb.toString()
    }
}
