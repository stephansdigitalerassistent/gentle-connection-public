package ch.heuscher.gentleconnect.model

/**
 * What a press on "read aloud" does, and how quiet the phone is allowed to be while it reads.
 *
 * Both used to have no answer. A second press while the voice was already talking started the
 * sentence again from the top, so a finger that landed twice — which hers does — produced a
 * stutter, and pressing again to find out whether anything was happening made sure nothing got
 * finished. And the voice came out at whatever the media volume had been left at, which after
 * one brush of the side key is nothing: a button that sinks, a screen that does not change, and
 * silence.
 */
object ReadAloud {

    /**
     * For this long after the voice starts, another press on the same button is the same press.
     *
     * Long enough to cover a finger that bounces or a second tap made because the first seemed
     * to do nothing; short enough that somebody who really does want it to stop is not kept
     * waiting.
     */
    const val SETTLE_MS = 1_000L

    enum class Tap { START, STOP, IGNORE }

    /**
     * @param reading what is being read at this moment, or null in silence
     * @param asked what the button that was pressed would read
     * @param sinceStartMs how long ago the reading began
     */
    fun onTap(reading: String?, asked: String, sinceStartMs: Long): Tap = when {
        reading == null || reading != asked -> Tap.START
        // The same as the voice message key: the control of what is sounding stops it.
        sinceStartMs in 0 until SETTLE_MS -> Tap.IGNORE
        else -> Tap.STOP
    }

    /** The share of the loudest the phone can be, in percent, below which reading is not left. */
    const val QUIETEST_PERCENT = 60

    /**
     * The volume to turn the phone up to before reading, or null to leave it alone.
     *
     * A floor and nothing else: a phone that somebody has turned up further stays where it is,
     * and nothing here ever turns one down.
     */
    fun raisedVolume(current: Int, max: Int): Int? {
        if (max <= 0) return null
        // Rounded up, in whole numbers: a phone with few steps must not round its floor to zero.
        val floor = ((max * QUIETEST_PERCENT + 99) / 100).coerceIn(1, max)
        return if (current < floor) floor else null
    }
}
