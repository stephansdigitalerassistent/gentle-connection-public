package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.ThreemaWireId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The name the app gives its own messages.
 *
 * It did not used to give them one. The bridge invented an id and never reported it, so every
 * message she sent was filed under something the app made up — and a reaction names the wire id,
 * which meant every heart her family put on something she wrote was dropped without trace.
 */
class ThreemaWireIdTest {

    @Test
    fun `an id is eight bytes of lower-case hexadecimal`() {
        // The exact shape the bridge prints and parses. Anything else round-trips to a different
        // message, or to none.
        repeat(200) {
            val id = ThreemaWireId.random()
            assertEquals(16, id.length)
            assertTrue(id, ThreemaWireId.isValid(id))
        }
    }

    @Test
    fun `ids do not repeat`() {
        // Two messages sharing an id would make a reaction to one appear on both.
        val ids = (1..500).map { ThreemaWireId.random() }.toSet()

        assertEquals(500, ids.size)
    }

    @Test
    fun `the whole range of each digit is reachable`() {
        // A generator that never produced 'f' would still pass every other test here while
        // quietly halving the space.
        val seen = (1..400).flatMap { ThreemaWireId.random(Random(it)).toList() }.toSet()

        assertEquals(16, seen.size)
    }

    @Test
    fun `anything that is not that shape is refused`() {
        assertFalse(ThreemaWireId.isValid(null))
        assertFalse(ThreemaWireId.isValid(""))
        assertFalse(ThreemaWireId.isValid("0102030405060708ff"))
        assertFalse(ThreemaWireId.isValid("0102030405060")) 
        // Upper case is a different string to the bridge, which prints lower case — and an id
        // that differs only in case names no message at all.
        assertFalse(ThreemaWireId.isValid("ABCDEF0102030405"))
        assertTrue(ThreemaWireId.isValid("abcdef0102030405"))
        assertFalse(ThreemaWireId.isValid("zzzzzzzzzzzzzzzz"))
    }
}
