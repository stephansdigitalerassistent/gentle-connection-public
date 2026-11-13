package ch.heuscher.gentleconnect.model

/**
 * What shape a photograph is drawn in.
 *
 * Every picture used to be drawn in the same box — the width of the bubble by a fixed 220dp — and
 * cropped to fit it. That is right for none of them. An upright photograph of a person, which is
 * most of what her family sends, arrived as a horizontal band across the middle of them: the
 * faces at the top and whatever they were standing on at the bottom were simply not there, and
 * nothing on the screen said a picture had been cut. She has no way to see the rest, because
 * pictures here are deliberately not tappable.
 *
 * So a photograph now keeps its own shape. The one thing that shape is not allowed to do is make
 * the picture unusable, which happens at both extremes:
 *
 *  - **Too tall**, and one photograph is the whole screen. What was written under it is pushed
 *    off the bottom, and the next message with it, so a conversation stops looking like a
 *    conversation.
 *  - **Too wide**, and there is nothing to see. A panorama at 15:1 in a bubble three hundred
 *    points wide is a twenty-point stripe.
 *
 * Between [TALLEST] and [WIDEST] the picture is shown exactly as it was taken. Outside them it is
 * drawn at the nearest limit and cropped from the middle, because the middle of a photograph is
 * where the people are.
 */
object PhotoShape {

    /**
     * The most upright shape drawn whole: half again as tall as it is wide.
     *
     * Chosen so that the ordinary 3:4 portrait — what a telephone camera held upright produces,
     * and most of what she is sent — is never touched at all. The taller 9:16 ones, which are
     * usually a screen recording or a picture taken of another screen, lose a little from the top
     * and bottom rather than filling her display with one message.
     */
    const val TALLEST = 2f / 3f

    /**
     * The widest shape drawn whole: twice as wide as it is tall.
     *
     * A 16:9 photograph, which is the widest anybody's camera produces, sits inside this and is
     * left alone. Beyond it is panorama territory, where showing the whole thing means showing it
     * too small to make anything out.
     */
    const val WIDEST = 2f

    /**
     * A shape to fall back on when the picture's own is not known.
     *
     * Landscape, mildly, because a photograph whose dimensions could not be read is one that
     * failed to decode, and this only decides how much space an absence occupies.
     */
    const val UNKNOWN = 4f / 3f

    /**
     * The width-over-height ratio to draw a picture of this size at.
     *
     * Nonsense dimensions — BitmapFactory reports 0 for a header it could not read, which for a
     * photograph still downloading is an ordinary thing to happen — give [UNKNOWN] rather than a
     * division by zero.
     */
    fun ratioFor(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return UNKNOWN
        return (width.toFloat() / height.toFloat()).coerceIn(TALLEST, WIDEST)
    }

    /**
     * Whether a picture of this size loses anything to being drawn at [ratioFor].
     *
     * Nothing on her screen is told this — a caption saying a photograph was cropped is a caption
     * she can do nothing about — but it is the difference the tests are about, and it says plainly
     * which pictures the limits are actually reaching.
     */
    fun isCropped(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val ratio = width.toFloat() / height.toFloat()
        return ratio < TALLEST || ratio > WIDEST
    }
}
