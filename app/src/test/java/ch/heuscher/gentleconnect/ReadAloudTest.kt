package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.ReadAloud
import ch.heuscher.gentleconnect.model.ReadAloud.Tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a press on "read aloud" does while the voice is or is not talking, and how quiet the
 * phone may be when it starts.
 */
class ReadAloudTest {

    @Test
    fun `a press in silence starts the reading`() {
        assertEquals(Tap.START, ReadAloud.onTap(reading = null, asked = "Hallo", sinceStartMs = 0))
        assertEquals(Tap.START, ReadAloud.onTap(null, "Hallo", 60_000))
    }

    @Test
    fun `a second press straight after the first is the same press`() {
        assertEquals(Tap.IGNORE, ReadAloud.onTap("Hallo", "Hallo", 0))
        assertEquals(Tap.IGNORE, ReadAloud.onTap("Hallo", "Hallo", ReadAloud.SETTLE_MS - 1))
    }

    @Test
    fun `a press on what is being read stops it`() {
        assertEquals(Tap.STOP, ReadAloud.onTap("Hallo", "Hallo", ReadAloud.SETTLE_MS))
        assertEquals(Tap.STOP, ReadAloud.onTap("Hallo", "Hallo", 30_000))
    }

    @Test
    fun `a press on something else reads that instead, however soon`() {
        assertEquals(Tap.START, ReadAloud.onTap("Hallo", "Bis morgen", 10))
        assertEquals(Tap.START, ReadAloud.onTap("Hallo", "Bis morgen", 30_000))
    }

    @Test
    fun `a clock that ran backwards cannot leave a button that does nothing`() {
        assertEquals(Tap.STOP, ReadAloud.onTap("Hallo", "Hallo", -5))
    }

    @Test
    fun `a silent or quiet phone is turned up to the floor`() {
        assertEquals(9, ReadAloud.raisedVolume(current = 0, max = 15))
        assertEquals(9, ReadAloud.raisedVolume(current = 8, max = 15))
        assertEquals(15, ReadAloud.raisedVolume(current = 3, max = 25))
    }

    @Test
    fun `a phone that is loud enough is left alone`() {
        assertNull(ReadAloud.raisedVolume(current = 9, max = 15))
        assertNull(ReadAloud.raisedVolume(current = 15, max = 15))
    }

    @Test
    fun `a stream with no steps is left alone and a one-step stream is not left silent`() {
        assertNull(ReadAloud.raisedVolume(current = 0, max = 0))
        assertEquals(1, ReadAloud.raisedVolume(current = 0, max = 1))
    }
}
