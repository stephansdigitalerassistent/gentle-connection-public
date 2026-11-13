package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A message that is not words.
 *
 * Found on the phone: the home screen listed two messages whose entire text was `[Foto]`, in
 * square brackets, in German, on a screen that was otherwise in English.
 */
class AttachmentTest {

    @Test
    fun `the tokens both message paths write are recognised`() {
        // ThreemaBackupParser writes these; the WhatsApp bridge sends the same shapes.
        assertEquals(Attachment.PHOTO, Attachment.of("[Foto]"))
        assertEquals(Attachment.VOICE, Attachment.of("[Sprachnachricht]"))
        assertEquals(Attachment.VIDEO, Attachment.of("[Video]"))
        assertEquals(Attachment.LOCATION, Attachment.of("[Standort]"))
        assertEquals(Attachment.FILE, Attachment.of("[Datei]"))
        assertEquals(Attachment.POLL, Attachment.of("[Umfrage]"))
        assertEquals(Attachment.MESSAGE, Attachment.of("[Nachricht]"))
        assertEquals(Attachment.OTHER, Attachment.of("[Anhang]"))
    }

    @Test
    fun `surrounding space and case do not matter`() {
        assertEquals(Attachment.PHOTO, Attachment.of("  [Foto]  "))
        assertEquals(Attachment.PHOTO, Attachment.of("[foto]"))
    }

    @Test
    fun `words are words`() {
        assertNull(Attachment.of("Das nächste Turnier ist in 3 Wochen!"))
        assertNull(Attachment.of(null))
        assertNull(Attachment.of(""))
    }

    @Test
    fun `a token inside a sentence is somebody having typed brackets`() {
        // Her own words are never rewritten. A token that does not begin the message is a pair of
        // square brackets somebody typed.
        assertNull(Attachment.of("Schau dir das [Foto] an"))
        assertNull(Attachment.of("Und hier [Video] war es schöner"))
    }

    @Test
    fun `a token can carry the caption that came with it`() {
        // This test used to assert the opposite — that "[Foto] von gestern" was somebody typing
        // brackets — and the phone disagreed. The WhatsApp bridge's withCaption sends
        // `placeholder + " " + caption` as one string, and 60 messages on her phone were arriving
        // that way, 29 of them statuses. Matching only a whole-string token meant every one of
        // them was drawn exactly as it came, brackets included.
        assertEquals(Attachment.PHOTO, Attachment.of("[Foto] Grüsse aus dem Garten"))
        assertEquals(Attachment.VIDEO, Attachment.of("[Video] Schee war's!"))
        assertEquals(Attachment.FILE, Attachment.of("[Dokument] Fahrplan"))
    }

    @Test
    fun `the caption is what she is shown, and the token is not`() {
        assertEquals("Grüsse aus dem Garten", Attachment.captionIn("[Foto] Grüsse aus dem Garten"))
        assertEquals("Schee war's!", Attachment.captionIn("  [Video]   Schee war's!  "))
        // Nothing but a token: no caption, which is what makes messageInWords fall back to a word.
        assertEquals("", Attachment.captionIn("[Foto]"))
        // Not a token at all: her words, untouched, brackets and all.
        assertEquals("Schau dir das [Foto] an", Attachment.captionIn("Schau dir das [Foto] an"))
        assertEquals("[Fotos] von gestern", Attachment.captionIn("[Fotos] von gestern"))
    }

    @Test
    fun `the whole vocabulary both senders write is known`() {
        // Taken from the bridge's mediaPlaceholders table and the Threema parser's mapMessageText.
        // "[Dokument]" and "[Kontakt]" were missing here and reached her screen as themselves.
        assertEquals(Attachment.FILE, Attachment.of("[Dokument]"))
        assertEquals(Attachment.CONTACT, Attachment.of("[Kontakt]"))
        assertEquals(Attachment.STICKER, Attachment.of("[Sticker]"))
    }

    @Test
    fun `a picture announced as a file is announced as a picture`() {
        // Threema files photographs, films and voice messages under one type, so every one of the
        // 793 attachments in her backup arrived as "[Datei]" — and the word stayed on the screen
        // above the picture, because a token is only hidden when it names what is drawn below it.
        assertEquals("[Foto]", Attachment.retoken("[Datei]", Attachment.PHOTO))
        assertEquals("[Video]", Attachment.retoken("[Datei]", Attachment.VIDEO))
        assertEquals("[Sprachnachricht]", Attachment.retoken("[Datei]", Attachment.VOICE))
    }

    @Test
    fun `her sender's own words survive the correction`() {
        assertEquals(
            "[Foto] Grüsse aus dem Garten",
            Attachment.retoken("[Datei] Grüsse aus dem Garten", Attachment.PHOTO)
        )
        // A message that is words and not an attachment is not touched, whatever came with it.
        assertEquals(
            "Schick mir die Datei",
            Attachment.retoken("Schick mir die Datei", Attachment.PHOTO)
        )
        assertEquals("", Attachment.retoken(null, Attachment.PHOTO))
    }

    @Test
    fun `a bracketed word this does not know is left alone`() {
        // The safe direction to be wrong in: an unknown token is shown as it was written, where a
        // sentence mistaken for one would have its words deleted.
        assertNull(Attachment.of("[Termin] am Freitag"))
        assertNull(Attachment.of("[Foto"))
    }
}
