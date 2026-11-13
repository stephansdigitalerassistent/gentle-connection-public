package ch.heuscher.gentleconnect.model

/**
 * How wide a photograph is allowed to be drawn, so that it fits on the glass.
 *
 * A picture in a message is drawn at the width of the bubble it came in and whatever height its
 * own shape then asks for — see [ch.heuscher.gentleconnect.ui.components.MessagePhoto]. Width is
 * the only thing anybody sets, and height is what comes out. So on a wide window an upright
 * photograph grows in the dimension nobody was watching: on the open Fold a perfectly ordinary
 * 3:4 picture from her daughter came out eight hundred points tall in five hundred points of
 * list, and she scrolled through one photograph instead of looking at it.
 *
 * The rule is therefore stated where it belongs: **a picture is never wider than the width at
 * which an upright photograph still fits the height there is.** Not the conversation — the
 * picture. Narrowing the whole column to fit the photographs was the first answer here, and it
 * cost the words, the reading-aloud buttons and the voice messages two hundred points of a
 * screen that had just been opened to gain them: a wide window with a band of empty cream down
 * the side of it. A tall picture drawn narrower than its bubble costs nothing but the cream
 * either side of that one picture.
 *
 * All points, as floats, so the arithmetic is proved in CI rather than on her screen.
 */
object PictureFit {

    /**
     * The upright photograph this is measured against: three across, four down.
     *
     * What a phone camera held upright produces, and most of what her family sends. Not
     * [PhotoShape.TALLEST], which is the taller 9:16 shape and is already cropped towards this
     * one: measuring against the extreme would hold every picture narrower on the strength of the
     * rarest one, and a screen recording being slightly tall is worth nobody's photographs
     * shrinking.
     */
    const val UPRIGHT = 3f / 4f

    /**
     * The widest a picture may be drawn, given the room the list actually has.
     *
     * A cap and never a width: this is handed to the drawing side as a maximum, so wherever the
     * bubble is already narrower than the answer — which is her phone, always, where the height is
     * generous and the width is not — nothing about her screen changes.
     *
     * [narrowest] is the floor. A window short enough to ask for less than this — a phone turned
     * on its side, a freeform window squashed against the bottom of the screen — would otherwise
     * pull a photograph in to a postage stamp, and a picture that has to be scrolled is a smaller
     * fault than one too small to make anybody out. There it is allowed to be tall again.
     */
    fun widestPicture(height: Float, narrowest: Float): Float {
        if (height <= 0f) return narrowest
        return maxOf(height * UPRIGHT, narrowest)
    }
}
