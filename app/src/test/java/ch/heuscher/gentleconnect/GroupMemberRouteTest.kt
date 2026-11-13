package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.GroupFace
import ch.heuscher.gentleconnect.model.GroupMemberRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether one person out of several can be rung and written to, and over what.
 *
 * The thing being defended is that the buttons are drawn from what is actually reachable. A name
 * she can press that then fails is worse than a name she cannot press, and the only way to keep
 * that true without a phone in hand is here.
 */
class GroupMemberRouteTest {

    private val monika = Contact(
        id = "1",
        name = "Monika Hartmann",
        phoneNumber = "079 123 45 67",
        photoUri = "content://contacts/1/photo",
        threemaId = "ECHO1001"
    )

    private val contacts = listOf(monika)

    private fun face(key: String, name: String = "Monika", photo: String? = null) =
        GroupFace(key = key, name = name, photoUri = photo)

    @Test
    fun `a threema member is reachable by identity alone`() {
        val route = GroupMemberRoute.of(
            face = face("TSTMBR01", name = "Claire-Lise"),
            transport = BridgeTransport.THREEMA,
            contacts = contacts
        )!!

        assertEquals("TSTMBR01", route.threemaId)
        assertNull(route.phoneNumber)
        assertTrue(route.canRing)
        assertTrue(route.canWrite)
    }

    @Test
    fun `a threema member she has a contact for can be rung on the telephone`() {
        val route = GroupMemberRoute.of(
            face = face("ECHO1001"),
            transport = BridgeTransport.THREEMA,
            contacts = contacts
        )!!

        // The number is what lets CallRouter ring her the ordinary way where there is service,
        // instead of placing an in-app call because it knew nothing else.
        assertEquals("079 123 45 67", route.phoneNumber)
        assertEquals("ECHO1001", route.threemaId)
    }

    @Test
    fun `an identity in lower case is still the same person`() {
        val route = GroupMemberRoute.of(
            face = face("echo1001"),
            transport = BridgeTransport.THREEMA,
            contacts = contacts
        )!!

        assertEquals("ECHO1001", route.threemaId)
        assertEquals("079 123 45 67", route.phoneNumber)
    }

    @Test
    fun `an identity of letters only is accepted`() {
        // PersonName.looksLikeIdentity insists on a digit, because there it is deciding whether a
        // *name* is really a code. Here the key is never a name, and an all-letter identity is a
        // real person who must not lose their buttons.
        val route = GroupMemberRoute.of(
            face = face("KAROLINA"),
            transport = BridgeTransport.THREEMA,
            contacts = contacts
        )

        assertEquals("KAROLINA", route!!.threemaId)
    }

    @Test
    fun `a whatsapp member is reachable by the digits of their number`() {
        val route = GroupMemberRoute.of(
            face = face("41791234567"),
            transport = BridgeTransport.WHATSAPP,
            contacts = contacts
        )!!

        assertEquals("41791234567", route.whatsappId)
        // Her own spelling of the number, because the contact matched.
        assertEquals("079 123 45 67", route.phoneNumber)
        // And the identity from that contact, so the message goes over this app's own transport.
        assertEquals("ECHO1001", route.threemaId)
        assertTrue(route.canRing)
        assertTrue(route.canWrite)
    }

    @Test
    fun `a whatsapp member she has no contact for is rung on the number itself`() {
        val route = GroupMemberRoute.of(
            face = face("4930123456789", name = "Katrin"),
            transport = BridgeTransport.WHATSAPP,
            contacts = contacts
        )!!

        assertEquals("+4930123456789", route.phoneNumber)
        assertNull(route.threemaId)
        assertEquals("Katrin", route.name)
        assertTrue(route.canRing)
    }

    @Test
    fun `somebody known only by name can be neither rung nor written to`() {
        assertNull(
            GroupMemberRoute.of(
                face = face("name:monika"),
                transport = BridgeTransport.WHATSAPP,
                contacts = contacts
            )
        )
    }

    @Test
    fun `a hidden number stays unreachable rather than becoming a wrong one`() {
        // A LID that nothing could resolve keeps the raw address as its key. Its digits are not a
        // telephone number, and dialling them would ring a stranger.
        assertNull(
            GroupMemberRoute.of(
                face = face("174283746152@LID"),
                transport = BridgeTransport.WHATSAPP,
                contacts = contacts
            )
        )
    }

    @Test
    fun `a transport this does not know offers nothing`() {
        assertNull(
            GroupMemberRoute.of(
                face = face("41791234567"),
                transport = BridgeTransport.UNKNOWN,
                contacts = contacts
            )
        )
    }

    @Test
    fun `a key too short to be a telephone number is refused`() {
        assertNull(
            GroupMemberRoute.of(
                face = face("1234"),
                transport = BridgeTransport.WHATSAPP,
                contacts = contacts
            )
        )
    }

    @Test
    fun `a key that is not an identity is refused on threema`() {
        assertNull(
            GroupMemberRoute.of(
                face = face("ECHO24"),
                transport = BridgeTransport.THREEMA,
                contacts = contacts
            )
        )
    }

    @Test
    fun `the name on the face wins over the one in her address book`() {
        // Her contact says "Monika Hartmann"; Monika writes under "Monika". Taking the contact
        // would be the same mistake as replacing a chosen name with a telephone number.
        val route = GroupMemberRoute.of(
            face = face("ECHO1001", name = "Monika"),
            transport = BridgeTransport.THREEMA,
            contacts = contacts
        )!!

        assertEquals("Monika", route.name)
    }

    @Test
    fun `an unreadable name falls back to her address book`() {
        val route = GroupMemberRoute.of(
            // What a message from somebody with no contact arrives under: the identity itself.
            face = face("ECHO1001", name = "ECHO1001"),
            transport = BridgeTransport.THREEMA,
            contacts = contacts
        )!!

        assertEquals("Monika Hartmann", route.name)
    }

    @Test
    fun `the face's photograph wins over her address book's`() {
        val route = GroupMemberRoute.of(
            face = face("ECHO1001", photo = "file:///data/faces/monika.jpg"),
            transport = BridgeTransport.THREEMA,
            contacts = contacts
        )!!

        assertEquals("file:///data/faces/monika.jpg", route.photoUri)
    }

    @Test
    fun `a member with no contact still borrows nothing from anybody else`() {
        val route = GroupMemberRoute.of(
            face = face("TSTMBR01", name = "Claire-Lise"),
            transport = BridgeTransport.THREEMA,
            contacts = contacts
        )!!

        assertNull(route.photoUri)
        assertNull(route.phoneNumber)
        assertFalse(route.whatsappId != null)
    }

    @Test
    fun `an empty key is nobody`() {
        assertNull(
            GroupMemberRoute.of(
                face = face("   "),
                transport = BridgeTransport.THREEMA,
                contacts = contacts
            )
        )
    }
}
