package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.PhotoDecoding.TARGET_PIXELS
import ch.heuscher.gentleconnect.model.PhotoDecoding.sampleSizeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How far a photograph is shrunk before it is decoded.
 *
 * The arithmetic is worth a test of its own because getting it wrong is invisible until the phone
 * is under memory pressure, and then it is fatal: a full-size decode of a modern camera image is
 * tens of megabytes, and the launcher being killed for a picture is a phone that goes blank in
 * her hands.
 */
class PhotoDecodingTest {

    @Test
    fun `a photograph from a telephone camera is decoded a fraction of its size`() {
        // 4032x3024 is what the phone this app runs on produces.
        val sample = sampleSizeFor(4032, 3024)

        assertEquals(4, sample)
        assertTrue("must not exceed the target", 4032 / sample <= TARGET_PIXELS)
    }

    @Test
    fun `something already small is not shrunk`() {
        assertEquals(1, sampleSizeFor(800, 600))
        assertEquals(1, sampleSizeFor(TARGET_PIXELS, TARGET_PIXELS))
    }

    @Test
    fun `the answer is always a power of two`() {
        // inSampleSize honours nothing else: asking for 3 quietly gets you 2, so a value that is
        // not a power of two is a decode at a size nobody chose.
        for (width in listOf(1201, 2000, 2401, 5000, 12000, 40000)) {
            val sample = sampleSizeFor(width, width)
            assertTrue("$width gave $sample", sample > 0 && (sample and (sample - 1)) == 0)
            assertTrue("$width stayed too big", width / sample <= TARGET_PIXELS)
        }
    }

    @Test
    fun `a panorama is shrunk by its long side`() {
        // Width and height are considered separately on purpose: a 12000x800 panorama is enormous
        // in one direction only, and judging it by area or by height alone would decode it whole.
        assertEquals(16, sampleSizeFor(12000, 800))
    }

    @Test
    fun `nonsense dimensions do not divide by zero`() {
        // BitmapFactory reports 0 or -1 for a file it could not read the header of, which for a
        // half-downloaded photograph is an ordinary thing to happen.
        assertEquals(1, sampleSizeFor(0, 0))
        assertEquals(1, sampleSizeFor(-1, 100))
    }
}
