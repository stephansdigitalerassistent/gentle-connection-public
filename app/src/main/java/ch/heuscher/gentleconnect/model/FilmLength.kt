package ch.heuscher.gentleconnect.model

import kotlin.math.roundToInt

/**
 * How long a film is, and how much of it has been watched.
 *
 * The button that plays a film used to say only "Ansehen", and pressing it committed her to a
 * whole screen with a single way out. Whether that was eight seconds of her great-grandson or
 * four minutes of a wedding was not on the screen anywhere, and the difference matters to
 * somebody deciding whether to sit down first.
 *
 * Note that [MessageVoice][ch.heuscher.gentleconnect.ui.components.MessageVoice] deliberately
 * refuses a duration, and that is not a contradiction. A voice message plays inside the
 * conversation and stops the moment she presses the same button again; nothing is entered and
 * nothing has to be left. A film takes the screen. The number answers a question she actually has
 * *before* she presses, which is the test the voice control's duration failed.
 *
 * ## Why words rather than a clock
 *
 * "1:47" is a colon between two numbers that only means what it means if you already know the
 * convention, and this app has spent a lot of effort not showing her developer punctuation — see
 * [Attachment]. "2 Minuten" is the same fact with nothing to decode.
 *
 * The cost is precision, and it is paid on purpose: seconds are rounded up to the next whole
 * second and anything past a minute is rounded to the nearest minute. She is deciding whether to
 * sit down, not catching a train.
 *
 * Pure, because CI here runs JVM unit tests only.
 */
object FilmLength {

    /** How long a film lasts, in the coarsest unit that still answers "have I time for this?". */
    sealed interface Length {
        data class Seconds(val count: Int) : Length
        data class Minutes(val count: Int) : Length
    }

    /**
     * The length of a film, or null when the phone could not say.
     *
     * Null rather than a zero, because nothing is the right thing to draw for a film whose file
     * has no readable duration: a label saying "0 Sekunden" under a button that plays a perfectly
     * good film is worse than no label at all.
     */
    fun of(millis: Long): Length? {
        if (millis <= 0) return null

        // Up, never down. A film of 900ms is "1 Sekunde"; rounding it to nothing would put a
        // label on the screen that says the film is not there.
        val seconds = ((millis + 999) / 1000).toInt()
        if (seconds < 60) return Length.Seconds(seconds)

        // At the boundary this is what turns 60 seconds into one minute rather than leaving
        // "60 Sekunden" on the screen, which is the same number said the long way round.
        return Length.Minutes(maxOf(1, (seconds / 60f).roundToInt()))
    }

    /**
     * How much of the film has been watched, 0..1, for the bar on the film screen.
     *
     * Clamped at both ends because neither number is trustworthy: a player that has not finished
     * preparing reports a duration of -1, and one that has just finished can report a position
     * past the end. Both would otherwise draw a bar of impossible length, and a bar that is
     * sometimes wrong teaches her not to look at it.
     */
    fun watched(positionMillis: Long, durationMillis: Long): Float {
        if (durationMillis <= 0L || positionMillis <= 0L) return 0f
        return (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
    }
}
