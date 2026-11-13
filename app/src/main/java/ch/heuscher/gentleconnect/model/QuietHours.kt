package ch.heuscher.gentleconnect.model

import java.util.TreeMap

/**
 * Learns her personal quiet hours over sliding history windows so restlessness detection
 * can be tuned to her daily circadian rhythm without manual configuration.
 *
 * An elderly person often develops distinct sleeping and waking patterns that differ from
 * standard definitions of "night". Rather than hardcoding fixed quiet windows everywhere,
 * [QuietHours] tracks her touch activity over a 14-day history. Any hour of the day (0..23)
 * that sees touch activity on at most [QUIET_MAX_ACTIVE_DAYS] day out of the last 14 is
 * deemed a quiet hour for her.
 *
 * Safety invariant: Deep night hours (01:00 to 05:00) defined by [AgitationPolicy.isNocturnalHour]
 * are *always* treated as quiet hours, even if she is frequently awake during them. Nocturnal
 * confusion or sundowning must never be learned away as "normal".
 *
 * Before sufficient baseline data is accumulated ([days.size] < [MIN_DAYS]), [isQuiet] falls back
 * strictly to [forcedQuietHours] and [AgitationPolicy.isNocturnalHour].
 */
class QuietHours(private val forcedQuietHours: Set<Int> = emptySet()) {

    companion object {
        const val HISTORY_DAYS = 14
        const val MIN_DAYS = 7
        const val QUIET_MAX_ACTIVE_DAYS = 1

        /**
         * Parses an encoded representation `"epochDay:mask,..."`.
         * Bad or malformed tokens are quietly ignored; returns an empty [QuietHours] on blank/garbage.
         * Only the newest [HISTORY_DAYS] entries are retained.
         */
        fun decode(s: String?): QuietHours {
            val qh = QuietHours()
            if (s.isNullOrBlank()) return qh
            val tokens = s.split(",")
            for (token in tokens) {
                val parts = token.trim().split(":")
                if (parts.size == 2) {
                    val epochDay = parts[0].trim().toLongOrNull()
                    val mask = parts[1].trim().toIntOrNull()
                    if (epochDay != null && mask != null) {
                        qh.days[epochDay] = mask and 0xFFFFFF
                    }
                }
            }
            while (qh.days.size > HISTORY_DAYS) {
                qh.days.pollFirstEntry()
            }
            return qh
        }

        /** Test helper to create an instance with fixed quiet hours. */
        fun alwaysQuiet(hours: Set<Int>): QuietHours = QuietHours(forcedQuietHours = hours)
    }

    private val days = TreeMap<Long, Int>()

    /**
     * Records a touch interaction during [hour] on [epochDay].
     *
     * @return `true` if the bit was newly set and retained in history, `false` otherwise.
     */
    @Synchronized
    fun record(epochDay: Long, hour: Int): Boolean {
        if (hour !in 0..23) return false
        val bit = 1 shl hour
        val prev = days[epochDay] ?: 0
        val next = prev or bit
        if (days.containsKey(epochDay) && next == prev) return false

        days[epochDay] = next
        while (days.size > HISTORY_DAYS) {
            days.pollFirstEntry()
        }
        return days[epochDay] == next
    }

    /**
     * Whether [hour] qualifies as a quiet hour for her.
     *
     * Returns `true` if:
     * - The hour is explicitly forced via [forcedQuietHours].
     * - The hour is within the nocturnal window (01:00-05:00) per [AgitationPolicy.isNocturnalHour].
     * - At least [MIN_DAYS] of history have been gathered and the hour was active on at most
     *   [QUIET_MAX_ACTIVE_DAYS] day.
     */
    @Synchronized
    fun isQuiet(hour: Int): Boolean {
        if (hour !in 0..23) return false
        if (hour in forcedQuietHours) return true
        if (AgitationPolicy.isNocturnalHour(hour)) return true
        if (days.size < MIN_DAYS) return false

        val bit = 1 shl hour
        val activeDays = days.values.count { (it and bit) != 0 }
        return activeDays <= QUIET_MAX_ACTIVE_DAYS
    }

    /**
     * Encodes the tracked days and hour masks as a compact CSV string (`epochDay:mask,...`),
     * sorted ascending by epochDay.
     */
    @Synchronized
    fun encode(): String =
        days.entries.joinToString(",") { "${it.key}:${it.value}" }
}
