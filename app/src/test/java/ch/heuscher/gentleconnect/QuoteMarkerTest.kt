package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.QuoteMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stripping quote markers out of Threema messages.
 *
 * Threema puts quotes in the message text itself. Left alone, the sixteen hex characters of a v2
 * quote or the eight characters of an identity in a legacy v1 quote land on her home screen and are
 * read aloud to her by the voice.
 */
class QuoteMarkerTest {

    @Test
    fun `a modern v2 quote extracts the wire id and body`() {
        val parsed = QuoteMarker.parse("> quote #a3f19c04b7e25d81\n\nJa, gerne!")

        assertEquals("a3f19c04b7e25d81", parsed.wireId)
        assertEquals("Ja, gerne!", parsed.body)
    }

    @Test
    fun `a v2 quote handles multiline body text`() {
        val parsed = QuoteMarker.parse("> quote #a3f19c04b7e25d81\n\nFirst line\nSecond line")

        assertEquals("a3f19c04b7e25d81", parsed.wireId)
        assertEquals("First line\nSecond line", parsed.body)
    }

    @Test
    fun `a legacy v1 quote strips the marker without an id`() {
        val parsed = QuoteMarker.parse("> ECHO1001: Kommst du am Sonntag zum Essen?\nJa, gerne!")

        assertNull(parsed.wireId)
        assertEquals("Ja, gerne!", parsed.body)
    }

    @Test
    fun `a legacy v1 quote with multiline quoted text strips properly`() {
        val text = "> ECHO1001: First line of quote\n> Second line of quote\n\nJa, gerne!"
        val parsed = QuoteMarker.parse(text)

        assertNull(parsed.wireId)
        assertEquals("Ja, gerne!", parsed.body)
    }

    @Test
    fun `nested v2 quotes strip all markers and keep the outermost wire id`() {
        val text = "> quote #a3f19c04b7e25d81\n\n> quote #a3f19c04b7e25d81\n\nJa, gerne!"
        val parsed = QuoteMarker.parse(text)

        assertEquals("a3f19c04b7e25d81", parsed.wireId)
        assertEquals("Ja, gerne!", parsed.body)
    }

    @Test
    fun `a v1 quote inside a v2 quote strips both and keeps the v2 wire id`() {
        val text = "> quote #a3f19c04b7e25d81\n\n> ECHO1001: Kommst du?\nJa, gerne!"
        val parsed = QuoteMarker.parse(text)

        assertEquals("a3f19c04b7e25d81", parsed.wireId)
        assertEquals("Ja, gerne!", parsed.body)
    }

    @Test
    fun `a v2 marker inside a v1 quote is stripped and its id deliberately not used`() {
        // A v1 quote carries the quoted message's own text, prefixed. When that message was
        // itself a reply, its `> quote #…` line comes along with it — and the id on that line
        // names what the *quoted* message was answering, one hop further back. Citing it would
        // put a third message on her screen as the thing being answered, which is worse than
        // citing nothing: she would be shown the wrong context and have no way to tell.
        //
        // So the marker goes, the words stay, and no id is recovered. A v1 quote never yields
        // one; that is the whole difference between the two forms.
        val text = "> ECHO1001: Hallo\n> quote #a3f19c04b7e25d81\n\nJa, gerne!"
        val parsed = QuoteMarker.parse(text)

        assertNull(parsed.wireId)
        assertEquals("Ja, gerne!", parsed.body)
    }

    @Test
    fun `deeply nested quote markers are bounded and do not loop forever`() {
        var text = "Final answer"
        repeat(20) {
            text = "> quote #a3f19c04b7e25d81\n\n$text"
        }
        val parsed = QuoteMarker.parse(text)

        assertEquals("a3f19c04b7e25d81", parsed.wireId)
    }

    @Test
    fun `a plain message with no quote is returned unchanged`() {
        val parsed = QuoteMarker.parse("Hallo Mama, wie geht es dir?")

        assertNull(parsed.wireId)
        assertEquals("Hallo Mama, wie geht es dir?", parsed.body)
    }

    @Test
    fun `an empty message is returned unchanged`() {
        val parsed = QuoteMarker.parse("")

        assertNull(parsed.wireId)
        assertEquals("", parsed.body)
    }

    @Test
    fun `a malformed v2 id is refused and left intact`() {
        // Uppercase hex is not what Threema produces and matches nothing
        val upper = QuoteMarker.parse("> quote #A3F19C04B7E25D81\n\nJa, gerne!")
        assertNull(upper.wireId)
        assertEquals("> quote #A3F19C04B7E25D81\n\nJa, gerne!", upper.body)

        // Wrong length
        val short = QuoteMarker.parse("> quote #a3f19c04\n\nJa, gerne!")
        assertNull(short.wireId)
        assertEquals("> quote #a3f19c04\n\nJa, gerne!", short.body)

        // Non-hex chars
        val nonHex = QuoteMarker.parse("> quote #zzzzzzzzzzzzzzzz\n\nJa, gerne!")
        assertNull(nonHex.wireId)
        assertEquals("> quote #zzzzzzzzzzzzzzzz\n\nJa, gerne!", nonHex.body)
    }
}
