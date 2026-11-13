package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Mail
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.Searching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding one message among thousands.
 *
 * She is typing what she remembers, not composing a query: a word or two, in whatever order they
 * come to mind, without the accents her keyboard makes difficult.
 */
class SearchingTest {

    private fun message(
        id: String,
        from: String,
        text: String,
        group: String? = null,
        address: String? = null
    ) = Message(
        id = id,
        senderName = from,
        senderAddress = address,
        text = text,
        timestamp = 0L,
        sourcePackage = "test",
        groupName = group
    )

    private fun mail(id: String, from: String, subject: String, body: String) = Mail(
        id = id,
        senderName = from,
        senderAddress = "$from@example.com",
        subject = subject,
        snippet = body.take(20),
        timestamp = 0L,
        body = body
    )

    @Test
    fun `a word from the message finds it`() {
        val all = listOf(
            message("1", "Anna", "Wir kommen am Sonntag"),
            message("2", "Peter", "Bis morgen")
        )
        assertEquals(listOf("1"), Searching.messages("Sonntag", all).map { it.id })
    }

    @Test
    fun `the sender's name finds it`() {
        val all = listOf(
            message("1", "Anna", "Wir kommen am Sonntag"),
            message("2", "Peter", "Bis morgen")
        )
        assertEquals(listOf("2"), Searching.messages("peter", all).map { it.id })
    }

    @Test
    fun `the words may come in any order and from different fields`() {
        // What she remembers is "something from Anna about Sunday", not a sentence.
        val all = listOf(
            message("1", "Anna", "Wir kommen am Sonntag"),
            message("2", "Anna", "Bis morgen"),
            message("3", "Peter", "Am Sonntag bin ich weg")
        )
        assertEquals(listOf("1"), Searching.messages("sonntag anna", all).map { it.id })
        assertEquals(listOf("1"), Searching.messages("anna sonntag", all).map { it.id })
    }

    @Test
    fun `accents and capitals do not have to be typed`() {
        val all = listOf(message("1", "Jürg", "Wir fahren nach Zürich"))
        assertEquals(1, Searching.messages("zurich", all).size)
        assertEquals(1, Searching.messages("ZÜRICH", all).size)
        assertEquals(1, Searching.messages("jurg", all).size)
    }

    @Test
    fun `an empty query hides nothing`() {
        val all = listOf(message("1", "Anna", "eins"), message("2", "Peter", "zwei"))
        assertEquals(2, Searching.messages("", all).size)
        assertEquals(2, Searching.messages("   ", all).size)
    }

    @Test
    fun `a word that is in nothing finds nothing`() {
        val all = listOf(message("1", "Anna", "Wir kommen am Sonntag"))
        assertTrue(Searching.messages("Dienstag", all).isEmpty())
    }

    @Test
    fun `a letter is searched to the bottom and not only to the fold`() {
        // The sentence she remembers is almost always below the part on screen. A search that
        // only read the snippet would say the letter does not exist.
        val letters = listOf(
            mail("1", "praxis", "Termin", "Sehr geehrte Frau Hartmann, ".padEnd(400) + "Bestätigung")
        )
        assertEquals(1, Searching.mails("bestatigung", letters).size)
        assertEquals(1, Searching.mails("termin", letters).size)
        assertEquals(1, Searching.mails("praxis", letters).size)
    }

    @Test
    fun `the name on the row finds it, and the row of a group is the group`() {
        // The bug, exactly as it stood: "Familie Hartmann" was the title on the screen and
        // searching for it found nothing, because the only name being searched was whoever
        // happened to have written last.
        val all = listOf(
            message("1", "Laura", "Wir kommen am Sonntag", group = "Familie Hartmann"),
            message("2", "Peter", "Bis morgen")
        )
        assertEquals(listOf("1"), Searching.messages("familie", all).map { it.id })
    }

    @Test
    fun `a person is still found inside the group they wrote to`() {
        // The group's name did not replace the writer's; the row answers to both.
        val all = listOf(
            message("1", "Laura", "Wir kommen am Sonntag", group = "Familie Hartmann"),
            message("2", "Peter", "Bis morgen")
        )
        assertEquals(listOf("1"), Searching.messages("laura", all).map { it.id })
        assertEquals(listOf("1"), Searching.messages("familie sonntag", all).map { it.id })
    }

    @Test
    fun `a number finds whoever is not in the address book`() {
        // The pharmacy has no name to type. Her letters have always been searchable by address;
        // her messages are now too.
        val all = listOf(
            message("1", "079 555 01 02", "Ihr Rezept ist bereit", address = "+41795550102"),
            message("2", "Peter", "Bis morgen")
        )
        assertEquals(listOf("1"), Searching.messages("+41795550102", all).map { it.id })
    }

    @Test
    fun `matches is true for an empty query and false for a word that is absent`() {
        assertTrue(Searching.matches("", "anything"))
        assertTrue(Searching.matches("anna", "Anna", null))
        assertFalse(Searching.matches("anna", null, "Peter"))
    }
}
