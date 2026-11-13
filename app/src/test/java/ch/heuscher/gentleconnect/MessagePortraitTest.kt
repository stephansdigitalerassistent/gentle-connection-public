package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.MessagePortrait
import ch.heuscher.gentleconnect.model.StatusBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whose face goes on a message row.
 *
 * Found on the phone: Clara Meier wrote, her photograph was sitting in the address book under
 * that number, and the row drew "CM" in a circle. Every row did — the list was the one screen in
 * the app that never asked for a photograph at all.
 */
class MessagePortraitTest {

    private fun message(
        id: String = "m1",
        from: String = "Clara Meier",
        address: String? = "+41795550125",
        senderId: String? = null,
        direction: MessageDirection = MessageDirection.INCOMING,
        group: GroupIdentity? = null,
        channel: MessageChannel = MessageChannel.DEVICE,
        contactId: String? = "c-1"
    ) = Message(
        id = id,
        senderName = from,
        senderAddress = address,
        senderId = senderId,
        contactId = contactId,
        text = "Bin um vier da",
        timestamp = 1_000L,
        sourcePackage = "",
        direction = direction,
        groupIdentity = group,
        channel = channel
    )

    @Test
    fun `the address book is asked first`() {
        val photo = MessagePortrait.photoFor(
            message(),
            byPhone = { if (it == "+41795550125") "content://contacts/400" else null }
        )
        assertEquals("content://contacts/400", photo)
    }

    @Test
    fun `an email sender is looked up by email and never by telephone`() {
        var phoneAsked = false
        val photo = MessagePortrait.photoFor(
            message(address = "clara.meier@example.ch"),
            byPhone = { phoneAsked = true; null },
            byEmail = { if (it == "clara.meier@example.ch") "content://contacts/7" else null }
        )
        assertEquals("content://contacts/7", photo)
        assertTrue("an address with an @ in it is not a number to dial", !phoneAsked)
    }

    @Test
    fun `a whatsapp address is a number, not an email`() {
        val photo = MessagePortrait.photoFor(
            message(address = "41795550126@s.whatsapp.net", channel = MessageChannel.BRIDGE),
            byPhone = { "file:///avatars/41795550126.jpg" },
            byEmail = { throw AssertionError("asked the address book for an email") }
        )
        assertEquals("file:///avatars/41795550126.jpg", photo)
    }

    @Test
    fun `the messenger answers when the address book does not`() {
        val photo = MessagePortrait.photoFor(
            message(address = null, senderId = "abcd1234", channel = MessageChannel.ON_DEVICE),
            photoByPartnerId = mapOf("ABCD1234" to "https://example/laura.jpg")
        )
        assertEquals("https://example/laura.jpg", photo)
    }

    @Test
    fun `the address book wins over the messenger for the same person`() {
        val photo = MessagePortrait.photoFor(
            message(senderId = "abcd1234", channel = MessageChannel.ON_DEVICE),
            photoByPartnerId = mapOf("ABCD1234" to "https://example/stale.jpg"),
            byPhone = { "content://contacts/400" }
        )
        assertEquals("content://contacts/400", photo)
    }

    @Test
    fun `the name is the last resort`() {
        val photo = MessagePortrait.photoFor(
            message(address = null),
            photoByName = mapOf("clara meier" to "content://contacts/400")
        )
        assertEquals("content://contacts/400", photo)
    }

    @Test
    fun `a group row keeps the group's initials`() {
        val photo = MessagePortrait.photoFor(
            message(group = GroupIdentity.of("creator", "familie")),
            byPhone = { "content://contacts/400" }
        )
        assertNull("her nephew's face must not become the family group", photo)
    }

    @Test
    fun `a group row draws the group's own picture when there is one`() {
        val family = GroupIdentity("CREATOR1", "familie")

        val photo = MessagePortrait.photoFor(
            message(group = family),
            photoByGroup = mapOf(family to "/data/threema_group_photos/CREATOR1_familie.jpg"),
            // Everything a person could be resolved by, all of it wrong for a group row.
            photoByPartnerId = mapOf("ECHOECHO" to "content://contacts/1"),
            byPhone = { "content://contacts/400" }
        )

        assertEquals("/data/threema_group_photos/CREATOR1_familie.jpg", photo)
    }

    @Test
    fun `a group nobody has a picture of still draws no face at all`() {
        val photo = MessagePortrait.photoFor(
            message(group = GroupIdentity("CREATOR1", "turnverein")),
            photoByGroup = mapOf(GroupIdentity("CREATOR1", "familie") to "/data/familie.jpg"),
            byPhone = { "content://contacts/400" }
        )

        assertNull("a different group's picture is not this group's", photo)
    }

    @Test
    fun `a message of hers wears the face of whoever she wrote to`() {
        // The row for a conversation she has just replied to reads "Clara Meier / Your message:
        // 👍", and its address is Clara's — see LocalMessageStore, which files what she sent
        // under the counterpart. Left out as "hers", these rows would be the faceless ones.
        val photo = MessagePortrait.photoFor(
            message(direction = MessageDirection.OUTGOING),
            byPhone = { "content://contacts/400" }
        )
        assertEquals("content://contacts/400", photo)
    }

    @Test
    fun `a company has no face`() {
        val photo = MessagePortrait.photoFor(
            message(from = "Reinigung!", address = "Reinigung", contactId = null),
            byPhone = { "content://contacts/400" }
        )
        assertNull(photo)
    }

    @Test
    fun `a status is not a group, so its poster keeps their face`() {
        val photo = MessagePortrait.photoFor(
            message(group = GroupIdentity.of("whatsapp-group", StatusBroadcast.JID)),
            byPhone = { "content://contacts/400" }
        )
        assertEquals("content://contacts/400", photo)
    }

    @Test
    fun `messages with no face resolved are absent from the map, not mapped to nothing`() {
        val found = MessagePortrait.photosFor(
            listOf(message(id = "has"), message(id = "hasnt", address = "+41000000000")),
            byPhone = { if (it == "+41795550125") "content://contacts/400" else null }
        )
        assertEquals(mapOf("has" to "content://contacts/400"), found)
    }

    @Test
    fun `the address book is asked once per person, however many rows they fill`() {
        var asks = 0
        val answers = mutableMapOf<String, String?>()
        val cached: (String) -> String? = { number ->
            if (answers.containsKey(number)) answers[number]
            else {
                asks++
                "content://contacts/400".also { answers[number] = it }
            }
        }
        MessagePortrait.photosFor(
            (1..5).map { message(id = "m$it") },
            byPhone = cached
        )
        assertEquals(1, asks)
    }
}
