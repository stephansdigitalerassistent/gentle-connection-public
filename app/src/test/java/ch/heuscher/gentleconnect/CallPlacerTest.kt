package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.CallPlacer
import org.junit.Assert.assertEquals
import org.junit.Test

class CallPlacerTest {

    @Test
    fun `dialable number keeps a plain international number`() {
        assertEquals("+41775550124", CallPlacer.dialableNumber("+41775550124"))
    }

    @Test
    fun `dialable number strips the spacing people write numbers with`() {
        assertEquals("+41775550124", CallPlacer.dialableNumber("+41 77 555 01 24"))
    }

    @Test
    fun `dialable number strips brackets and dashes`() {
        assertEquals("+41775550124", CallPlacer.dialableNumber("(+41) 77-555-01-24"))
    }

    @Test
    fun `dialable number keeps a local number as dialled`() {
        assertEquals("0775550124", CallPlacer.dialableNumber("077 555 01 24"))
    }

    /**
     * All punctuation is handed on untouched rather than becoming an empty `tel:`, so it surfaces
     * in the dialler where somebody can see what is wrong with it.
     */
    @Test
    fun `dialable number hands on text that cleans away to nothing`() {
        assertEquals("nonsense", CallPlacer.dialableNumber("nonsense"))
    }
}
