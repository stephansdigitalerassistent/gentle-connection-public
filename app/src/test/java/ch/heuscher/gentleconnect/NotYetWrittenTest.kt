package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.NotYetWritten
import org.junit.Assert.assertEquals
import org.junit.Test

class NotYetWrittenTest {

    private fun person(id: String, number: String = "", threemaId: String? = null) =
        Contact(id = id, name = id, phoneNumber = number, threemaId = threemaId)

    private val keyOf: (Contact) -> String? = { c ->
        c.threemaId?.let { "threema:$it" } ?: c.phoneNumber.filter { it.isDigit() }.ifEmpty { null }
    }
    private val reachable: (Contact) -> Boolean = { it.phoneNumber.isNotBlank() || it.threemaId != null }

    @Test
    fun `somebody with a conversation is left out`() {
        val result = NotYetWritten.from(
            listOf(person("Anna", "079 111"), person("Beat", "079 222")),
            conversations = setOf("079111"),
            keyOf = keyOf,
            reachable = reachable
        )
        assertEquals(listOf("Beat"), result.map { it.name })
    }

    @Test
    fun `the phone book's order is kept`() {
        val order = listOf(person("Zora", "1"), person("Anna", "2"), person("Mia", "3"))
        val result = NotYetWritten.from(order, emptySet(), keyOf, reachable)
        assertEquals(listOf("Zora", "Anna", "Mia"), result.map { it.name })
    }

    @Test
    fun `nobody who cannot be reached is offered`() {
        val result = NotYetWritten.from(
            listOf(person("No number"), person("Threema only", threemaId = "ABCD1234")),
            emptySet(),
            keyOf,
            reachable
        )
        assertEquals(listOf("Threema only"), result.map { it.name })
    }

    @Test
    fun `one row per conversation, the first one wins`() {
        val result = NotYetWritten.from(
            listOf(person("Favourite", "079 111"), person("Same person again", "079111")),
            emptySet(),
            keyOf,
            reachable
        )
        assertEquals(listOf("Favourite"), result.map { it.name })
    }
}
