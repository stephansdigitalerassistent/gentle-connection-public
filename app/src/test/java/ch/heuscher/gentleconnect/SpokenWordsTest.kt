package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.SpokenWords
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenWordsTest {

    @Test
    fun `words are worth saying`() {
        assertTrue(SpokenWords.worthSaying("Grüsse aus dem Garten"))
        assertTrue(SpokenWords.worthSaying("M31"))
        assertTrue(SpokenWords.worthSaying("Mmmm 😊"))
    }

    @Test
    fun `a caption that is only a face is not`() {
        assertFalse(SpokenWords.worthSaying("😊"))
        assertFalse(SpokenWords.worthSaying("🎉🎉🎉"))
        assertFalse(SpokenWords.worthSaying("🛥️"))
    }

    @Test
    fun `nothing, and punctuation alone, are not`() {
        assertFalse(SpokenWords.worthSaying(""))
        assertFalse(SpokenWords.worthSaying("   "))
        assertFalse(SpokenWords.worthSaying("..."))
        assertFalse(SpokenWords.worthSaying("!!!"))
    }
}
