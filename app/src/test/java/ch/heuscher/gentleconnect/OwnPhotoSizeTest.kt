package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.OwnPhotoSize
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnPhotoSizeTest {

    @Test
    fun `landscape image scales down to long edge 1600 preserving aspect`() {
        val (w, h) = OwnPhotoSize.target(4000, 3000)
        assertEquals(1600, w)
        assertEquals(1200, h)
    }

    @Test
    fun `portrait image scales down to long edge 1600 preserving aspect`() {
        val (w, h) = OwnPhotoSize.target(3000, 4000)
        assertEquals(1200, w)
        assertEquals(1600, h)
    }

    @Test
    fun `image already smaller than long edge is not upscaled`() {
        val (w, h) = OwnPhotoSize.target(800, 600)
        assertEquals(800, w)
        assertEquals(600, h)
    }

    @Test
    fun `square image scales down to 1600 by 1600`() {
        val (w, h) = OwnPhotoSize.target(2400, 2400)
        assertEquals(1600, w)
        assertEquals(1600, h)
    }

    @Test
    fun `extreme aspect ratio never returns zero`() {
        val (w, h) = OwnPhotoSize.target(16000, 1)
        assertEquals(1600, w)
        assertEquals(1, h)

        val (w2, h2) = OwnPhotoSize.target(1, 16000)
        assertEquals(1, w2)
        assertEquals(1600, h2)
    }

    @Test
    fun `custom long edge parameter works as expected`() {
        val (w, h) = OwnPhotoSize.target(2000, 1000, longEdge = 1000)
        assertEquals(1000, w)
        assertEquals(500, h)
    }
}
