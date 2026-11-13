package ch.heuscher.gentleconnect.voip

import ch.heuscher.gentleconnect.data.db.ThreemaContactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoipPeerResolverTest {

    @Test
    fun `resolve prefers address-book contact over imported contact and raw identity`() {
        val addressBookMatch = Pair("Anna Meier", "content://com.android.contacts/display_photo/1")
        val importedContact = ThreemaContactEntity(
            identity = "ECHO1001",
            firstName = "Anna",
            lastName = "Threema",
            nickname = null,
            photoPath = "/path/to/imported_photo.jpg"
        )
        val identity = "ECHO1001"

        val result = VoipPeerResolver.resolve(addressBookMatch, importedContact, identity)

        assertEquals("Anna Meier", result.displayName)
        assertEquals("content://com.android.contacts/display_photo/1", result.photoUri)
        assertTrue(result.isKnownContact)
    }

    @Test
    fun `resolve strips a quoted nickname from an address-book contact name`() {
        // The nicknames in her address book are quoted — Eliane "Eli" Müller — and it is the
        // family name that the row's sixteen characters would otherwise eat. Parentheses are not
        // a nickname to PersonName and are left alone; see PersonName.withoutNickname.
        val addressBookMatch = Pair("Eliane \"Eli\" Müller", "content://photo")
        val result = VoipPeerResolver.resolve(addressBookMatch, null, "ECHO1001")

        assertEquals("Eliane Müller", result.displayName)
        assertEquals("content://photo", result.photoUri)
        assertTrue(result.isKnownContact)
    }

    @Test
    fun `resolve falls back to imported Threema contact when address-book match is null`() {
        val importedContact = ThreemaContactEntity(
            identity = "ECHO1001",
            firstName = "Beat",
            lastName = "Muster",
            nickname = null,
            photoPath = "/data/user/0/app/files/photos/beat.jpg"
        )
        val identity = "ECHO1001"

        val result = VoipPeerResolver.resolve(null, importedContact, identity)

        assertEquals("Beat Muster", result.displayName)
        assertEquals("file:///data/user/0/app/files/photos/beat.jpg", result.photoUri)
        assertTrue(result.isKnownContact)
    }

    @Test
    fun `resolve keeps content URI in imported contact photoPath`() {
        val importedContact = ThreemaContactEntity(
            identity = "ECHO1001",
            firstName = "Beat",
            lastName = "Muster",
            nickname = null,
            photoPath = "content://media/external/images/media/42"
        )
        val identity = "ECHO1001"

        val result = VoipPeerResolver.resolve(null, importedContact, identity)

        assertEquals("Beat Muster", result.displayName)
        assertEquals("content://media/external/images/media/42", result.photoUri)
        assertTrue(result.isKnownContact)
    }

    @Test
    fun `resolve falls back to raw identity when neither contact source matches`() {
        val identity = "ECHO1001"

        val result = VoipPeerResolver.resolve(null, null, identity)

        assertEquals("ECHO1001", result.displayName)
        assertNull(result.photoUri)
        assertFalse(result.isKnownContact)
    }

    @Test
    fun `resolve treats blank address book and imported contact names as unknown fallback`() {
        val addressBookMatch = Pair("   ", null)
        val importedContact = ThreemaContactEntity(
            identity = "ECHO1001",
            firstName = "   ",
            lastName = null,
            nickname = null,
            photoPath = null
        )
        val identity = "ECHO1001"

        val result = VoipPeerResolver.resolve(addressBookMatch, importedContact, identity)

        assertEquals("ECHO1001", result.displayName)
        assertNull(result.photoUri)
        assertFalse(result.isKnownContact)
    }

    @Test
    fun `resolve treats an imported contact with no name at all as unknown`() {
        // ThreemaContactEntity.displayName falls back to the identity when there is no name on
        // it, so "has a display name" is not the same question as "has a name". Taking the getter
        // at its word would put ECHO1001 on her screen as though it were somebody's name, and
        // mark it a known contact while doing it.
        val nameless = ThreemaContactEntity(
            identity = "ECHO1001",
            firstName = null,
            lastName = null,
            nickname = null,
            photoPath = null
        )

        val result = VoipPeerResolver.resolve(null, nameless, "ECHO1001")

        assertEquals("ECHO1001", result.displayName)
        assertFalse(result.isKnownContact)
    }
}
