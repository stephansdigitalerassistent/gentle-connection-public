package ch.heuscher.gentleconnect.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeBodyExtractorSnippetTest {

    @Test
    fun `a short body is its own snippet`() {
        assertEquals("Passt dir Freitag?", MimeBodyExtractor.extractSnippet("Passt dir Freitag?"))
    }

    @Test
    fun `paragraph breaks collapse to one line`() {
        val body = "Liebe Mama,\n\n  hier sind die Fotos.\n\nGruss\tAnna"
        assertEquals("Liebe Mama, hier sind die Fotos. Gruss Anna", MimeBodyExtractor.extractSnippet(body))
    }

    @Test
    fun `a long body is cut on a word boundary`() {
        val body = "wort ".repeat(100)
        val snippet = MimeBodyExtractor.extractSnippet(body, maxLength = 20)

        assertTrue("should be shortened", snippet.length <= 21)
        assertTrue("should end with an ellipsis", snippet.endsWith("…"))
        assertTrue("should not cut mid-word", snippet.removeSuffix("…").endsWith("wort"))
    }

    @Test
    fun `a single unbroken token still yields a preview`() {
        // No space to cut on. Falling back to the word boundary would leave her with nothing.
        val snippet = MimeBodyExtractor.extractSnippet("a".repeat(200), maxLength = 20)

        assertEquals(21, snippet.length)
        assertTrue(snippet.startsWith("aaaa"))
    }

    @Test
    fun `an empty body yields an empty snippet`() {
        assertEquals("", MimeBodyExtractor.extractSnippet(""))
        assertEquals("", MimeBodyExtractor.extractSnippet("   \n\t  "))
    }
}
