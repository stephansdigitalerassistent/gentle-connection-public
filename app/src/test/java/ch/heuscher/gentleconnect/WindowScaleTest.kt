package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.ui.theme.GentleWindowScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How faces and text grow on large foldable screens.
 *
 * Scaling is guarded carefully because the cost of getting it wrong is not a cosmetic error:
 * scaling text too much forces her to scroll to read a message, and scaling an ordinary phone
 * would change the layout she has learned by heart.
 */
class WindowScaleTest {

    @Test
    fun `a normal phone window gives exactly one for both scales`() {
        // Standard phone dimensions (portrait). Neither axis exceeds the threshold so nothing changes.
        val typicalPhones = listOf(
            411f to 891f, // Pixel 7 / Galaxy S21
            360f to 800f, // Compact phones
            393f to 851f, // Pixel 5
            400f to 800f  // Reference width
        )

        for ((widthDp, heightDp) in typicalPhones) {
            assertEquals(1.0f, GentleWindowScale.faceScale(widthDp, heightDp), 0.0001f)
            assertEquals(1.0f, GentleWindowScale.textScale(widthDp, heightDp), 0.0001f)
        }
    }

    @Test
    fun `a phone in landscape gives exactly one for both scales because of the height guard`() {
        // Even if width is wide (e.g. 891dp), height is under 600dp (411dp).
        // The height guard keeps landscape phones from incorrectly scaling.
        val landscapePhones = listOf(
            891f to 411f,
            800f to 360f,
            851f to 393f
        )

        for ((widthDp, heightDp) in landscapePhones) {
            assertEquals(1.0f, GentleWindowScale.faceScale(widthDp, heightDp), 0.0001f)
            assertEquals(1.0f, GentleWindowScale.textScale(widthDp, heightDp), 0.0001f)
        }
    }

    @Test
    fun `the foldable inner panel scales faces up but stays within the clamps`() {
        // A large foldable inner panel: ~674 x 841 dp in portrait, ~841 x 674 dp in landscape.
        // Both dimensions exceed 600dp.
        val portraitWidth = 674f
        val portraitHeight = 841f

        val portraitFaceScale = GentleWindowScale.faceScale(portraitWidth, portraitHeight)
        val portraitTextScale = GentleWindowScale.textScale(portraitWidth, portraitHeight)

        // raw = 674 / 400 = 1.685, clamped to 1.6
        assertEquals(1.6f, portraitFaceScale, 0.0001f)
        // textScale = 1.0 + (1.6 - 1.0) * 0.4 = 1.24, within [1.0, 1.25]
        assertEquals(1.24f, portraitTextScale, 0.0001f)
        assertTrue("face scale within bounds", portraitFaceScale in 1.0f..1.6f)
        assertTrue("text scale within bounds", portraitTextScale in 1.0f..1.25f)

        val landscapeWidth = 841f
        val landscapeHeight = 674f

        val landscapeFaceScale = GentleWindowScale.faceScale(landscapeWidth, landscapeHeight)
        val landscapeTextScale = GentleWindowScale.textScale(landscapeWidth, landscapeHeight)

        assertEquals(1.6f, landscapeFaceScale, 0.0001f)
        assertEquals(1.24f, landscapeTextScale, 0.0001f)
        assertTrue("landscape face scale within bounds", landscapeFaceScale in 1.0f..1.6f)
        assertTrue("landscape text scale within bounds", landscapeTextScale in 1.0f..1.25f)
    }

    @Test
    fun `an intermediate foldable window scales proportionally`() {
        // A smaller foldable or split window: 600 x 600 dp
        val width = 600f
        val height = 600f
        // raw = 600 / 400 = 1.5
        val face = GentleWindowScale.faceScale(width, height)
        val text = GentleWindowScale.textScale(width, height)

        assertEquals(1.5f, face, 0.0001f)
        assertEquals(1.20f, text, 0.0001f)
    }

    @Test
    fun `an absurdly wide window is clamped at the maximums`() {
        val absurdWindows = listOf(
            2000f to 2000f,
            1200f to 1600f,
            4000f to 3000f
        )

        for ((widthDp, heightDp) in absurdWindows) {
            val faceScale = GentleWindowScale.faceScale(widthDp, heightDp)
            val textScale = GentleWindowScale.textScale(widthDp, heightDp)

            assertEquals(1.6f, faceScale, 0.0001f)
            assertTrue("text scale must not exceed 1.25f", textScale <= 1.25f)
            assertEquals(1.24f, textScale, 0.0001f)
        }
    }

    @Test
    fun `text always scales less than faces whenever faces scale at all`() {
        // Whenever face scale > 1.0, text scale must be strictly less than face scale
        // and the text scale growth (textScale - 1.0) must be 40% of the face scale growth.
        val testWindows = listOf(
            600f to 600f,
            620f to 700f,
            650f to 800f,
            674f to 841f,
            841f to 674f,
            1000f to 1000f
        )

        for ((widthDp, heightDp) in testWindows) {
            val face = GentleWindowScale.faceScale(widthDp, heightDp)
            val text = GentleWindowScale.textScale(widthDp, heightDp)

            assertTrue("face must scale above 1.0 for large windows", face > 1.0f)
            assertTrue("text scale must be strictly less than face scale", text < face)
            val faceGrowth = face - 1.0f
            val textGrowth = text - 1.0f
            assertEquals(faceGrowth * 0.4f, textGrowth, 0.0001f)
        }
    }

    @Test
    fun `two columns on the fold's inner screen, one everywhere else`() {
        // Measured on a foldable display with large zoom (density override 464), which is
        // what makes the open screen 640dp across rather than the 674dp of a default density.
        assertTrue("open", GentleWindowScale.isWide(640f, 745f))
        assertFalse("folded, on the cover screen", GentleWindowScale.isWide(334f, 819f))
        assertFalse("an ordinary phone", GentleWindowScale.isWide(411f, 891f))
        assertFalse("a phone held sideways", GentleWindowScale.isWide(891f, 411f))
    }

    @Test
    fun `the second column appears exactly where the faces start growing`() {
        // One size of window to know about, not two: the same guard decides both.
        val windows = listOf(
            600f to 600f,
            640f to 745f,
            674f to 841f,
            599f to 900f,
            900f to 599f,
            411f to 891f
        )

        for ((widthDp, heightDp) in windows) {
            assertEquals(
                "at ${widthDp}x$heightDp",
                GentleWindowScale.faceScale(widthDp, heightDp) > 1.0f,
                GentleWindowScale.isWide(widthDp, heightDp)
            )
        }
    }
}
