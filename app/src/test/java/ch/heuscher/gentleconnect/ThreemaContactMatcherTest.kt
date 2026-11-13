package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.MessageRouter
import ch.heuscher.gentleconnect.data.ThreemaContactMatcher
import ch.heuscher.gentleconnect.data.db.ThreemaContactEntity
import ch.heuscher.gentleconnect.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreemaContactMatcherTest {

    private fun threema(identity: String, first: String?, last: String?, nick: String? = null) =
        ThreemaContactEntity(
            identity = identity,
            firstName = first,
            lastName = last,
            nickname = nick,
            photoPath = null
        )

    private fun contact(name: String, number: String = "+41791234567") =
        Contact(id = name, name = name, phoneNumber = number)

    @Test
    fun `an unambiguous name gets its threema id`() {
        val result = ThreemaContactMatcher.attachThreemaIds(
            contacts = listOf(contact("Anna Meier"), contact("Peter Bucher")),
            threemaContacts = listOf(
                threema("ECHO1001", "Anna", "Meier"),
                threema("TSTMBR06", "Peter", "Bucher")
            )
        )

        assertEquals("ECHO1001", result.first { it.name == "Anna Meier" }.threemaId)
        assertEquals("TSTMBR06", result.first { it.name == "Peter Bucher" }.threemaId)
    }

    @Test
    fun `spacing and capitals are not a different person`() {
        val result = ThreemaContactMatcher.attachThreemaIds(
            contacts = listOf(contact("  anna   MEIER ")),
            threemaContacts = listOf(threema("ECHO1001", "Anna", "Meier"))
        )
        assertEquals("ECHO1001", result.single().threemaId)
    }

    @Test
    fun `two people with the same name are left alone rather than guessed at`() {
        // The failure being avoided: her message to one Anna Meier arriving at the other, under
        // her name, with neither of them ever finding out why.
        val twoInTheBackup = ThreemaContactMatcher.attachThreemaIds(
            contacts = listOf(contact("Anna Meier")),
            threemaContacts = listOf(
                threema("ECHO1001", "Anna", "Meier"),
                threema("TSTMBR06", "Anna", "Meier")
            )
        )
        assertNull(twoInTheBackup.single().threemaId)

        val twoInHerPhone = ThreemaContactMatcher.attachThreemaIds(
            contacts = listOf(
                contact("Anna Meier", "+41791111111"),
                contact("Anna Meier", "+41792222222")
            ),
            threemaContacts = listOf(threema("ECHO1001", "Anna", "Meier"))
        )
        assertTrue(twoInHerPhone.all { it.threemaId == null })
    }

    @Test
    fun `a backup row with no name at all matches nobody`() {
        // displayName falls back to the raw identity; that must not become a name to match on.
        val result = ThreemaContactMatcher.attachThreemaIds(
            contacts = listOf(contact("ECHO1001")),
            threemaContacts = listOf(threema("ECHO1001", null, null))
        )
        assertNull(result.single().threemaId)
    }

    @Test
    fun `an id she already has is never overwritten`() {
        val result = ThreemaContactMatcher.attachThreemaIds(
            contacts = listOf(contact("Anna Meier").copy(threemaId = "ALREADY1")),
            threemaContacts = listOf(threema("ECHO1001", "Anna", "Meier"))
        )
        assertEquals("ALREADY1", result.single().threemaId)
    }

    @Test
    fun `nothing imported changes nothing`() {
        val contacts = listOf(contact("Anna Meier"), contact("Peter Bucher"))
        assertEquals(contacts, ThreemaContactMatcher.attachThreemaIds(contacts, emptyList()))
    }

    @Test
    fun `a matched contact becomes reachable, and an unmatched one does not`() {
        // This is the whole point of the step: before this, no contact anywhere in the app had a
        // Threema ID, so MessageRouter could never pick Threema for anyone and every route died.
        val result = ThreemaContactMatcher.attachThreemaIds(
            contacts = listOf(contact("Anna Meier"), contact("Fremde Person")),
            threemaContacts = listOf(threema("ECHO1001", "Anna", "Meier"))
        )

        val anna = result.first { it.name == "Anna Meier" }
        val stranger = result.first { it.name == "Fremde Person" }

        assertTrue(MessageRouter.canReach(anna.threemaId, null, anna.phoneNumber))
        assertEquals(
            MessageRouter.SMS_ENABLED,
            MessageRouter.canReach(stranger.threemaId, null, stranger.phoneNumber)
        )
    }
}
