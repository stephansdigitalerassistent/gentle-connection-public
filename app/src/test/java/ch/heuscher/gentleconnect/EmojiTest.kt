package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Emoji
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [Emoji.spell].
 *
 * A voice that goes quiet at an emoji sounds broken, and a thumbs-up from her daughter is a
 * whole message.
 */
class EmojiTest {

    private val names: Map<String, String> = mapOf(
        "\uD83D\uDC4D" to "Daumen hoch",        // 👍
        "\u2764" to "Herz",                      // ❤
        "\uD83D\uDE0A" to "lächelndes Gesicht", // 😊
        "\uD83D\uDE02" to "lacht Tränen",       // 😂
        "\uD83D\uDE4F" to "danke"               // 🙏
    )

    private fun spell(text: String): String = Emoji.spell(text) { names[it] }

    @Test
    fun `a bare emoji becomes its word`() {
        assertEquals("Daumen hoch", spell("👍"))
        assertEquals("Herz", spell("❤"))
    }

    @Test
    fun `an emoji with a variation selector finds the same word`() {
        assertEquals("Herz", spell("❤️"))
        assertEquals("Herz", spell("\u2764\uFE0F"))
    }

    @Test
    fun `an unknown emoji is left alone`() {
        assertEquals("🚀", spell("🚀"))
        assertEquals("Gute Reise 🚀", spell("Gute Reise 🚀"))
    }

    @Test
    fun `text either side survives`() {
        assertEquals("Hallo Daumen hoch danke Oma", spell("Hallo 👍 danke Oma"))
        assertEquals("Liebe Grüsse Herz von Anna", spell("Liebe Grüsse ❤️ von Anna"))
    }

    @Test
    fun `several emoji in one message`() {
        assertEquals("Daumen hoch Herz lächelndes Gesicht", spell("👍❤️😊"))
        assertEquals("Super lacht Tränen danke", spell("Super 😂 🙏"))
    }

    @Test
    fun `a message with no emoji comes back unchanged`() {
        assertEquals("Hallo Oma wie geht es dir?", spell("Hallo Oma wie geht es dir?"))
        assertEquals("Kommst du heute zum Zvieri?", spell("Kommst du heute zum Zvieri?"))
    }

    @Test
    fun `empty message returns empty string`() {
        assertEquals("", spell(""))
    }

    @Test
    fun `runs of whitespace collapse and result is trimmed`() {
        assertEquals("Hallo Herz", spell("   Hallo    ❤️   "))
    }
}
