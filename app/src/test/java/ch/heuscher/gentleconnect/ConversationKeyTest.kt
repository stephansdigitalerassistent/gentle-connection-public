package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.ConversationKey
import ch.heuscher.gentleconnect.data.ConversationLinks
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.GroupIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationKeyTest {

    private fun contact(id: String, name: String, number: String) =
        Contact(id = id, name = name, phoneNumber = number)

    @Test
    fun `a person she knows is one conversation whichever way they reach her`() {
        // The whole point. Her daughter texting from a train and writing on Threema from home is
        // one person, and splitting her across two threads by transport would make the app's
        // plumbing her problem.
        val links = ConversationLinks.from(
            contacts = listOf(contact("7", "Sabine", "079 123 45 67")),
            directoryMatches = mapOf("41791234567" to "ECHO1001"),
            normalizedNumberOf = { "41791234567" }
        )

        val overThreema = ConversationKey.of(
            confirmedContactId = links.contactIdFor("ECHO1001", null),
            threemaId = "ECHO1001"
        )
        val overSms = ConversationKey.of(
            confirmedContactId = links.contactIdFor(null, "41791234567"),
            normalizedPhoneNumber = "41791234567"
        )

        assertEquals("contact:7", overThreema)
        assertEquals(overThreema, overSms)
    }

    @Test
    fun `a second number of the same person is still that person`() {
        // Found on the phone. David has a mobile and two office lines; Contact holds one number,
        // so on the other two he was nobody — while the SMS import, which asks the system about
        // the number itself, knew him on all three. One message she had sent was therefore filed
        // twice, under contact: by the import and under phone: by this app's own record, and the
        // message list showed her the same thread with the same person twice over.
        val links = ConversationLinks.from(
            contacts = listOf(contact("5346", "David", "+41 79 555 01 16")),
            directoryMatches = emptyMap(),
            normalizedNumberOf = { "41795550116" },
            everyNumber = listOf(
                "41795550116" to "5346",
                "41315550117" to "5346",
                "41315550118" to "5346"
            )
        )

        val onTheMobile = ConversationKey.of(
            confirmedContactId = links.contactIdFor(null, "41795550116"),
            normalizedPhoneNumber = "41795550116"
        )
        val onTheOfficeLine = ConversationKey.of(
            confirmedContactId = links.contactIdFor(null, "41315550117"),
            normalizedPhoneNumber = "41315550117"
        )

        assertEquals("contact:5346", onTheMobile)
        assertEquals(onTheMobile, onTheOfficeLine)
    }

    @Test
    fun `the number somebody has as their own beats the same number as somebody else's second`() {
        // A household line listed on both of them. Whoever holds it as their Contact number keeps
        // it, rather than the two taking turns as the address book is re-read — the invariant the
        // primary pass already had, now that a second pass can also claim a number.
        val links = ConversationLinks.from(
            contacts = listOf(contact("7", "Sabine", "031 555 01 10")),
            directoryMatches = emptyMap(),
            normalizedNumberOf = { "41315550110" },
            everyNumber = listOf("41315550110" to "8")
        )

        assertEquals("7", links.contactIdFor(null, "41315550110"))
    }

    @Test
    fun `a name match is not enough to merge two conversations`() {
        // ThreemaContactMatcher fills in Threema IDs by matching names, which is guesswork. It may
        // route a reply, where being wrong means a message bounces and somebody notices. It must
        // never merge two threads, where being wrong puts her doctor's messages under her sister's
        // name and nothing on the screen looks unusual.
        val links = ConversationLinks.from(
            contacts = listOf(contact("7", "Sabine", "079 123 45 67")),
            // The directory confirmed nothing.
            directoryMatches = emptyMap(),
            normalizedNumberOf = { "41791234567" }
        )

        assertNull(links.contactIdFor("ECHO1001", null))
        assertEquals(
            "threema:ECHO1001",
            ConversationKey.of(
                confirmedContactId = links.contactIdFor("ECHO1001", null),
                threemaId = "ECHO1001"
            )
        )
    }

    @Test
    fun `somebody she has no contact for still gets a conversation`() {
        assertEquals("threema:ECHO1001", ConversationKey.of(threemaId = "echo1001"))
        assertEquals("phone:41791234567", ConversationKey.of(normalizedPhoneNumber = "41791234567"))
    }

    @Test
    fun `several people are never filed as a person`() {
        // Even when we know the one of them who happened to write perfectly well.
        val key = ConversationKey.of(
            groupIdentity = GroupIdentity("TSTMBR06", "0a1b2c3d4e5f6071"),
            confirmedContactId = "7",
            threemaId = "ECHO1001"
        )
        assertEquals("group:TSTMBR06:0a1b2c3d4e5f6071", key)
    }

    @Test
    fun `nothing to key on is null rather than a conversation of its own`() {
        // A message we cannot file must not invent a thread. An empty-string key would collect
        // every unfilable message from everyone into one conversation.
        assertNull(ConversationKey.of())
        assertNull(ConversationKey.of(threemaId = "   ", normalizedPhoneNumber = ""))
    }

    @Test
    fun `two address book entries sharing a number do not take turns owning the conversation`() {
        val links = ConversationLinks.from(
            contacts = listOf(
                contact("7", "Sabine", "079 123 45 67"),
                contact("9", "Sabine Mobile", "079 123 45 67")
            ),
            directoryMatches = mapOf("41791234567" to "ECHO1001"),
            normalizedNumberOf = { "41791234567" }
        )

        assertEquals("7", links.contactIdFor(null, "41791234567"))
        assertEquals("7", links.contactIdFor("ECHO1001", null))
    }

    @Test
    fun `a contact with no usable number contributes no link`() {
        val links = ConversationLinks.from(
            contacts = listOf(contact("7", "Sabine", "not a number")),
            directoryMatches = mapOf("41791234567" to "ECHO1001"),
            normalizedNumberOf = { null }
        )

        assertNull(links.contactIdFor("ECHO1001", "41791234567"))
    }

    @Test
    fun `a sender that is a name and not a number still has a conversation`() {
        // Zur Rose, RoamingInfo, SwissPass. These had no key at all, so tapping one opened a
        // screen with no name at the top and nothing under it, nothing in them could be read
        // anywhere, and none of them could ever be marked as seen — because being seen is
        // something that happens to a conversation.
        val key = ConversationKey.of(serviceAddress = "Zur Rose")

        assertEquals(ConversationKey.SERVICE_PREFIX + "zur rose", key)
    }

    @Test
    fun `the same shop shouting is the same shop`() {
        assertEquals(
            ConversationKey.of(serviceAddress = "Zur Rose"),
            ConversationKey.of(serviceAddress = "ZUR ROSE ")
        )
    }

    @Test
    fun `a name never takes a message away from the person it belongs to`() {
        // Everything above it wins, so a company that also has a telephone number is filed under
        // the number like anybody else, and a person is never filed under a service key.
        assertEquals(
            ConversationKey.PHONE_PREFIX + "41791234567",
            ConversationKey.of(normalizedPhoneNumber = "41791234567", serviceAddress = "Claire-Lise")
        )
        assertEquals(
            ConversationKey.CONTACT_PREFIX + "7",
            ConversationKey.of(confirmedContactId = "7", serviceAddress = "Claire-Lise")
        )
    }

    @Test
    fun `nothing to key on is still nothing`() {
        assertNull(ConversationKey.of())
        assertNull(ConversationKey.of(serviceAddress = "   "))
    }
}
