package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.ReplyRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sizes the reply block comes out at, on every window this app is drawn in.
 *
 * Mostly about the windows that must **not** change. The reaction faces grew a floor and a way to
 * wrap when the conversation screen gained a second column on wide windows, and the risk in that
 * is not the new narrow column — it is the phone screen she uses every day silently rearranging
 * itself. So the phone cases are pinned first and the new one last.
 */
class ReplyRowTest {

    // What ReplyControls is built with: the gap between circles, the ordinary ceiling, the
    // smaller one while she is typing, the floor, and the width below which the floor applies.
    private val gap = 8f
    private val ceiling = 76f
    private val whileTyping = 48f
    private val floor = 48f
    private val floorBelow = 280f

    // Two representative screen densities: a compact phone reporting 450 dots to the inch,
    // and both screens of the folding phone report 464.
    private val hers = 450f / 160f
    private val cover = 464f / 160f

    /**
     * Whether the row still fits once the points have become pixels.
     *
     * This is the thing dp arithmetic alone cannot see and the thing that actually wrapped the
     * fifth face on her screen: every circle is rounded up to a whole pixel, every gap with it,
     * and a row that fits to the point comes out a pixel or two too wide.
     */
    private fun fitsInPixels(available: Float, diameter: Float, circles: Int, density: Float): Boolean {
        fun px(points: Float) = kotlin.math.ceil(points * density)
        return circles * px(diameter) + (circles - 1) * px(gap) <= kotlin.math.floor(available * density)
    }

    private fun diameter(available: Float, circles: Int = 5, ceiling: Float = this.ceiling) =
        ReplyRow.circleDiameter(
            available = available,
            circles = circles,
            gap = gap,
            ceiling = ceiling,
            floor = floor,
            floorBelow = floorBelow
        )

    @Test
    fun `five faces on the folding phone's outer screen are on one line`() {
        // 294 points of block: (294 - 4 gaps) / 5 is 52.4, taken down to a whole point and one
        // more given back so that the pixels cannot spill. See [ReplyRow.SLACK].
        assertEquals(51f, diameter(294f), 0.05f)
        assertEquals(5, ReplyRow.circlesPerLine(294f, diameter(294f), gap))
        assertTrue("one line once it is drawn", fitsInPixels(294f, diameter(294f), 5, cover))
    }

    @Test
    fun `five faces on an ordinary phone are on one line`() {
        // Compact phone (384 dp window) leaves this block about 323 once the screen is padded.
        assertEquals(61f, diameter(344f), 0.05f)
        assertEquals(5, ReplyRow.circlesPerLine(344f, diameter(344f), gap))
        assertTrue("one line once it is drawn", fitsInPixels(323f, diameter(323f), 5, hers))
    }

    @Test
    fun `the sixth circle a caregiver can switch on still fits on one line`() {
        // Six at about forty-one points, which is under the floor and must stay that way: the
        // floor is held back above 280, so this phone screen does not start wrapping.
        val six = diameter(294f, circles = 6)
        assertEquals(41f, six, 0.05f)
        assertEquals(6, ReplyRow.circlesPerLine(294f, six, gap))
        assertTrue("one line once it is drawn", fitsInPixels(294f, six, 6, cover))
    }

    @Test
    fun `while she types they shrink to the smaller ceiling and stay on one line`() {
        val typing = diameter(294f, ceiling = whileTyping)
        assertEquals(whileTyping, typing, 0.001f)
        assertEquals(5, ReplyRow.circlesPerLine(294f, typing, gap))
    }

    @Test
    fun `on a wide window they stop growing at the ceiling`() {
        assertEquals(ceiling, diameter(600f), 0.001f)
    }

    @Test
    fun `in the narrow column they stop shrinking and wrap instead`() {
        // The right-hand column of a wide window: about 170 points. Sharing alone would give
        // twenty-eight, which is not a thing her hand can hit.
        val narrow = diameter(170f)
        assertEquals(floor, narrow, 0.001f)
        assertEquals(3, ReplyRow.circlesPerLine(170f, narrow, gap))
    }

    @Test
    fun `a row with nothing in it asks for nothing unreasonable`() {
        assertEquals(ceiling, diameter(300f, circles = 0), 0.001f)
        assertEquals(0, ReplyRow.circlesPerLine(300f, 0f, gap))
    }

    @Test
    fun `one circle always fits, however little room there is`() {
        assertEquals(1, ReplyRow.circlesPerLine(20f, 48f, gap))
    }

    @Test
    fun `her phone puts the answering button under the faces`() {
        // Five circles at their smallest and a button wide enough to read want five hundred
        // points; a 450 dpi phone gives this block about three hundred and forty-four.
        assertFalse(
            ReplyRow.facesBesideButton(344f, circles = 5, diameter = 48f, gap = gap, button = 220f)
        )
        assertFalse(
            ReplyRow.facesBesideButton(294f, circles = 5, diameter = 48f, gap = gap, button = 220f)
        )
    }

    @Test
    fun `the open Fold puts them side by side`() {
        assertTrue(
            ReplyRow.facesBesideButton(667f, circles = 5, diameter = 48f, gap = gap, button = 220f)
        )
        // And the circles then have room to be drawn at their full size beside it.
        assertEquals(ceiling, diameter(667f - 220f - gap, circles = 5), 0.001f)
    }

    @Test
    fun `the sixth circle can take the line away again`() {
        // A caregiver switching on the way to all the other faces adds one. Where that no longer
        // fits beside the button, the faces take their own line rather than shrinking.
        assertTrue(
            ReplyRow.facesBesideButton(560f, circles = 5, diameter = 48f, gap = gap, button = 220f)
        )
        assertFalse(
            ReplyRow.facesBesideButton(560f, circles = 6, diameter = 48f, gap = gap, button = 320f)
        )
    }

    @Test
    fun `no faces at all leaves the button the line`() {
        assertFalse(
            ReplyRow.facesBesideButton(667f, circles = 0, diameter = 48f, gap = gap, button = 220f)
        )
    }

    @Test
    fun `the camera key beside the button requires extra room`() {
        val cameraKey = 64f
        // Fits without camera at 667f
        assertTrue(
            ReplyRow.facesBesideButton(667f, circles = 5, diameter = 48f, gap = gap, button = 220f)
        )
        // With camera key (64 + 8 = 72 more), still fits at 667f (272 + 8 + 220 + 8 + 64 = 572 <= 667)
        assertTrue(
            ReplyRow.facesBesideButton(667f, circles = 5, diameter = 48f, gap = gap, button = 220f, extraButton = cameraKey)
        )
        // At 560f, fits without camera (272 + 8 + 220 = 500 <= 560):
        assertTrue(
            ReplyRow.facesBesideButton(560f, circles = 5, diameter = 48f, gap = gap, button = 220f)
        )
        // But with camera key (572 > 560), it wraps to stacked layout:
        assertFalse(
            ReplyRow.facesBesideButton(560f, circles = 5, diameter = 48f, gap = gap, button = 220f, extraButton = cameraKey)
        )
    }
}
