package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.PersonName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a person is called on her screen.
 *
 * Both of these were found by looking at the phone. The favourites read `Eliane "Eli"…` and the
 * write picker read `Johann "Papa Opa…` — sixteen characters spent, and the family name never
 * reached the screen. And a group message from an identity nobody has a contact for was signed
 * `TSTMBR01`.
 */
class PersonNameTest {

    @Test
    fun `a nickname in quotes is not part of the name`() {
        assertEquals("Johann Hartmann", PersonName.forHer("Johann \"Papa Opa\" Hartmann"))
        assertEquals("Eliane Müller", PersonName.forHer("Eliane \"Eli\" Müller"))
        assertEquals("Laura Hartmann", PersonName.forHer("Laura \"Lala Lala\" Hartmann"))
    }

    @Test
    fun `typographic and german quotes count too`() {
        // Contacts typed on a phone keyboard get curly quotes, and a Swiss address book gets
        // German ones. All three shapes are the same intent.
        assertEquals("Johann Hartmann", PersonName.forHer("Johann “Opa” Hartmann"))
        assertEquals("Johann Hartmann", PersonName.forHer("Johann „Opa“ Hartmann"))
    }

    @Test
    fun `an ordinary name is left exactly as it is`() {
        assertEquals("Sabine Hartmann-Berger", PersonName.forHer("Sabine Hartmann-Berger"))
        assertEquals("Claire-Lise", PersonName.forHer("  Claire-Lise  "))
        assertEquals("Familie Hartmann", PersonName.forHer("Familie Hartmann"))
    }

    @Test
    fun `an apostrophe is part of the name, not a nickname`() {
        // The reason the rule is double quotes only: treating ' as a delimiter eats half of these.
        assertEquals("Sean O'Brien", PersonName.forHer("Sean O'Brien"))
        assertEquals("Maria D'Angelo", PersonName.forHer("Maria D'Angelo"))
    }

    @Test
    fun `a name that is nothing but a nickname keeps the nickname`() {
        // Somebody wrote this down deliberately, and it is a great deal better than "Someone".
        assertEquals("Grosi", PersonName.forHer("\"Grosi\""))
    }

    @Test
    fun `an unfinished nickname takes the rest with it, and leaves no quote mark`() {
        assertEquals("Johann", PersonName.forHer("Johann \"Papa Opa"))
    }

    @Test
    fun `a threema identity is not a name`() {
        assertNull(PersonName.forHer("TSTMBR01"))
        assertNull(PersonName.forHer("TSTMBR03"))
        assertNull(PersonName.forHer("TSTMBR02"))
    }

    @Test
    fun `eight uppercase letters are a name, not an identity`() {
        // The deliberate hole in the rule. Getting this wrong the other way turns her family into
        // "Someone" on every screen that names them.
        assertEquals("KAROLINA", PersonName.forHer("KAROLINA"))
        assertEquals("MATTHIAS", PersonName.forHer("MATTHIAS"))
        assertFalse(PersonName.looksLikeIdentity("KAROLINA"))
        assertTrue(PersonName.looksLikeIdentity("TSTMBR01"))
    }

    @Test
    fun `a whatsapp address is not a name`() {
        // Found on the phone: an unnamed WhatsApp group signed every one of its messages, and
        // headed the conversation, with its own address.
        assertNull(PersonName.forHer("120363000099999999@g.us"))
        assertNull(PersonName.forHer("41791234567@s.whatsapp.net"))
        assertNull(PersonName.forHer("annette@example.ch"))
        assertTrue(PersonName.looksLikeAddress("120363000099999999@g.us"))
    }

    @Test
    fun `a bare telephone number is still allowed to be a name`() {
        // The deliberate limit of the address rule. WhatsAppMessageMapper falls back to the
        // number on purpose: it is something she may recognise, and it is how her phone has
        // always shown a stranger.
        assertEquals("41791234567", PersonName.forHer("41791234567"))
        assertEquals("+41 79 123 45 67", PersonName.forHer("+41 79 123 45 67"))
        assertFalse(PersonName.looksLikeAddress("41791234567"))
    }

    @Test
    fun `nothing at all is nothing at all`() {
        assertNull(PersonName.forHer(null))
        assertNull(PersonName.forHer(""))
        assertNull(PersonName.forHer("   "))
        assertNull(PersonName.forHer("\"\""))
    }

    @Test
    fun `the gap left by a removed nickname does not become a double space`() {
        assertEquals("Johann Hartmann", PersonName.withoutNickname("Johann  \"Opa\"  Hartmann"))
    }

    @Test
    fun `initials from ordinary names`() {
        assertEquals("AM", PersonName.initials("Anna Meier"))
        assertEquals("SH", PersonName.initials("Simon Hartmann"))
        assertEquals("SB", PersonName.initials("Sabine Hartmann-Berger"))
        assertEquals("M", PersonName.initials("Mama"))
        assertEquals("G", PersonName.initials("\"Grosi\""))
        assertEquals("JH", PersonName.initials("Johann \"Opa\" Hartmann"))
    }

    @Test
    fun `initials from email addresses`() {
        assertEquals("S", PersonName.initials("steven@example.com"))
        assertEquals("AM", PersonName.initials("anna.meier@example.ch"))
        assertEquals("JD", PersonName.initials("john_doe@example.com"))
    }

    @Test
    fun `initials from blank or empty names`() {
        assertEquals("?", PersonName.initials(null))
        assertEquals("?", PersonName.initials(""))
        assertEquals("?", PersonName.initials("   "))
    }
}
