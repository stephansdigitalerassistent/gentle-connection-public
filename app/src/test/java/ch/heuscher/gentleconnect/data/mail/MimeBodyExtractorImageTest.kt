package ch.heuscher.gentleconnect.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Taking out the words a letter leaves where a picture was.
 *
 * Every letter from a company with a logo begins with the alt text of that logo. This app shows
 * no pictures in a letter, so the marker is the only part of it that ever arrives — and read-aloud
 * says it out, bracket and all.
 */
class MimeBodyExtractorImageTest {

    @Test
    fun `the letter from Google loses its logo and keeps its words`() {
        // Copied off her phone on 2026-08-23, where it was the first thing in the reader.
        val body = "[image: Google] App password created to sign in to your account " +
            "alex.muster@example.com"

        assertEquals(
            "App password created to sign in to your account alex.muster@example.com",
            MimeBodyExtractor.readableLetter(body)
        )
    }

    @Test
    fun `Outlook's content id is taken out mid-sentence`() {
        assertEquals(
            "Hallo Mama bis bald",
            MimeBodyExtractor.readableLetter("Hallo Mama [cid:image001.png@01D9AB12.4F5C] bis bald")
        )
    }

    @Test
    fun `a German marker goes the same way`() {
        assertEquals(
            "Liebe Grüsse",
            MimeBodyExtractor.readableLetter("[Bild: Logo]\nLiebe Grüsse")
        )
    }

    @Test
    fun `a letter that is nothing but a picture keeps its only description`() {
        // Taking the marker out would leave her an empty letter, which says less than the alt
        // text does. The same fallback a Threema message gets when stripping empties it.
        assertEquals(
            "[image: Poster]",
            MimeBodyExtractor.readableLetter("[image: Poster]")
        )
    }

    @Test
    fun `brackets somebody actually wrote are left alone`() {
        val written = "Wir treffen uns [wie besprochen] um 3"
        assertEquals(written, MimeBodyExtractor.readableLetter(written))
    }

    @Test
    fun `an unclosed marker does not swallow the rest of the letter`() {
        // The reason the pattern stops at a line break. Without that, one malformed bracket costs
        // her everything her daughter wrote underneath it.
        val body = "[image: broken\nLiebe Mama, wie geht es dir?"
        assertEquals(body, MimeBodyExtractor.readableLetter(body))
    }

    @Test
    fun `an ordinary letter is returned exactly as it came`() {
        val body = "Liebe Mama,\n\nwie geht es dir?"
        assertEquals(body, MimeBodyExtractor.readableLetter(body))
    }

    @Test
    fun `the hole a logo leaves is closed`() {
        // Off her phone on 2026-08-23, once the marker was gone: the letter opened with a
        // sentence, then a gap the height of the reader, then the rest of it. She would scroll
        // past that looking for the words.
        val body = "[image: Google] App password created to sign in to your account" +
            "\n\n\n\n\nalex.muster@example.com If you didn't"

        assertEquals(
            "App password created to sign in to your account\n\nalex.muster@example.com If you didn't",
            MimeBodyExtractor.readableLetter(body)
        )
    }

    @Test
    fun `the address a link pointed at goes, the words it was on stay`() {
        // There is nothing to press in a letter here, so the address is only ever read out loud,
        // character by character. "Check activity" is what the link said.
        assertEquals(
            "Check activity",
            MimeBodyExtractor.readableLetter("Check activity <https://accounts.google.com/AccountChooser?Email=x>")
        )
    }

    @Test
    fun `an address somebody typed for her to read is left where they put it`() {
        val written = "Die Seite ist https://srf.ch und sehr gut"
        assertEquals(written, MimeBodyExtractor.readableLetter(written))
    }

    @Test
    fun `a paragraph break is not a hole and survives`() {
        val body = "Liebe Mama,\n\nwie geht es dir?"
        assertEquals(body, MimeBodyExtractor.readableLetter(body))
    }
}
