package ch.heuscher.gentleconnect.model

/**
 * How big the reaction faces are, and whether the send button fits beside the box she writes in.
 *
 * Both answers used to be one expression each, inside the controls that draw them. They are here
 * because the answer changed when the conversation screen grew a second column on wide windows —
 * see [ch.heuscher.gentleconnect.ui.ConversationScreen] — and the thing that mattered was not the
 * new narrow case but the old one: five faces on an ordinary phone must come out exactly as they
 * did before, and a Compose layout cannot be asked that question without a device.
 *
 * All widths are in points, as floats, because that is what the drawing side can hand over
 * without dragging Android into a test.
 */
object ReplyRow {

    /**
     * How wide one reaction circle is drawn, given the room the row has.
     *
     * Three rules, in order. They **share** the width, so five fit on a narrow phone rather than
     * the fifth being pushed off the edge. They stop growing at [ceiling], so on a wide screen the
     * row does not become five dinner plates with a thumbnail adrift in each. And they stop
     * *shrinking* at [floor], below which the row wraps onto another line instead — a heart the
     * size of a fingernail is not a thing her hand can hit, and height is the one thing the narrow
     * column has to spare.
     *
     * The floor is held back until the width itself is narrower than any phone ([floorBelow]),
     * and that qualification is the whole care taken here. A caregiver who has switched on the
     * sixth circle gets six of about forty-two points on the folding phone's outer screen, and
     * they have always been on one line; a floor applied everywhere would wrap them, which is a
     * screen she knows changing shape for a window she is not looking at. So the floor is for
     * windows narrower than a phone, which until the conversation screen grew a second column
     * meant "there are none".
     *
     * Nonsense — no circles at all — gives the ceiling, which is what an empty row would have
     * taken anyway.
     */
    fun circleDiameter(
        available: Float,
        circles: Int,
        gap: Float,
        ceiling: Float,
        floor: Float,
        floorBelow: Float
    ): Float {
        if (circles <= 0) return ceiling
        val share = kotlin.math.floor((available - gap * (circles - 1)) / circles) - SLACK
        val capped = minOf(share, ceiling)
        val held = if (available < floorBelow) maxOf(capped, floor) else capped
        return maxOf(held, 0f)
    }

    /**
     * The point each circle gives back so that the row cannot spill.
     *
     * Dividing the width exactly and handing the answer to a wrapping row does not work, and the
     * reason is that points are not what anything is drawn in. A 360–384 dp phone has about three hundred and
     * twenty-three points for this row: five circles of 62.4 and four gaps of eight come to 323.0,
     * which fits — and then each circle is turned into pixels at 2.8 to the point and rounded up,
     * the row comes out two pixels wider than the screen, and the fifth face drops onto a line of
     * its own. An exact fit is not a fit. The folding phone's outer screen is the same sum at a
     * different density and the same accident waiting to happen.
     *
     * So the share is taken down to a whole point and then one more is given back. It costs about
     * a third of a millimetre of circle, and it buys roughly three pixels per circle, which is
     * more than the rounding can ever take. The one rule this row has — **five faces on one
     * line, on every screen she holds** — is then true by arithmetic rather than by luck.
     */
    private const val SLACK = 1f

    /**
     * How many of them fit on one line at that size, which is what says whether the row wraps.
     *
     * Nothing draws from this — [circleDiameter] and a wrapping row between them do the drawing.
     * It is here so that "on her phone they are still all on one line" is a sentence a test can
     * check, which is the whole reason any of this moved out of the layout.
     */
    fun circlesPerLine(available: Float, diameter: Float, gap: Float): Int {
        if (diameter <= 0f) return 0
        val fits = ((available + gap) / (diameter + gap)).toInt()
        return fits.coerceAtLeast(1)
    }

    /**
     * Whether the faces she can send stand beside the answering button rather than above it.
     *
     * The reply is one line across the bottom of the conversation again — the faces on the left,
     * the button that opens the keyboard on the right. That is the arrangement wherever the whole
     * of it fits on one line: the circles at their smallest ([diameter]), a gap, and a button
     * wide enough to hold the word "Antworten" without breaking it.
     *
     * On her phone it does not fit — five circles of forty-eight and a two-hundred-point button
     * want five hundred points of a three-hundred-and-forty-four-point window — so there the
     * faces keep the line they have always had and the button goes underneath them. The open Fold
     * has room for both, which is the whole reason for asking.
     *
     * No circles means nothing to stand beside: the button takes the line by itself.
     */
    fun facesBesideButton(
        available: Float,
        circles: Int,
        diameter: Float,
        gap: Float,
        button: Float,
        extraButton: Float = 0f
    ): Boolean {
        if (circles <= 0) return false
        val faces = circles * diameter + gap * (circles - 1)
        val extra = if (extraButton > 0f) extraButton + gap else 0f
        return available >= faces + gap + button + extra
    }
}
