package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.PhoneNumberNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberNormalizerTest {

    private fun normalize(raw: String, framework: String? = null) =
        PhoneNumberNormalizer.matchable(framework, raw, "41")

    @Test
    fun `the same number written five ways hashes as one person`() {
        // If these disagree, the lookup finds nobody and every contact looks unreachable.
        val expected = "41791234567"
        assertEquals(expected, normalize("079 123 45 67"))
        assertEquals(expected, normalize("+41 79 123 45 67"))
        assertEquals(expected, normalize("0041791234567"))
        assertEquals(expected, normalize("079-123-45-67"))
        assertEquals(expected, normalize("(079) 123 45 67"))
    }

    @Test
    fun `the platform formatter is preferred when it produced something`() {
        assertEquals("41791234567", normalize("whatever", framework = "+41791234567"))
    }

    @Test
    fun `a platform result that is nonsense falls through to the raw number`() {
        // formatNumberToE164 returns null for numbers it cannot place, but it has also been known
        // to hand back something too short to be real. Either way the raw number gets its turn.
        assertEquals("41791234567", normalize("079 123 45 67", framework = "+41"))
    }

    @Test
    fun `a foreign number keeps its own country code`() {
        assertEquals("4915112345678", normalize("+49 151 12345678"))
        assertEquals("4915112345678", normalize("004915112345678"))
    }

    @Test
    fun `a bare subscriber number is not guessed at`() {
        // The failure being avoided: prepending a country code to a number that never had one,
        // landing on a real stranger who happens to use Threema.
        assertNull(normalize("791234567"))
    }

    @Test
    fun `short codes and empty entries are never sent`() {
        assertNull(normalize("112"))
        assertNull(normalize("0800"))
        assertNull(normalize(""))
        assertNull(normalize("   "))
        assertNull(normalize("keine Nummer"))
    }

    @Test
    fun `an implausibly long number is rejected`() {
        assertNull(normalize("+4179123456789012345"))
    }

    @Test
    fun `a number already carrying her country code is left as it is`() {
        assertEquals("41791234567", normalize("41791234567"))
    }
}
