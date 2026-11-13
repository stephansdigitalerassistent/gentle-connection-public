package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.ForwardableMedia
import ch.heuscher.gentleconnect.data.Forwarding
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.Recipient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sending a photograph on to somebody: who is offered, and what goes.
 *
 * Both halves are worth pinning. Offering the wrong person is how a photograph reaches somebody
 * she never meant to send it to, and offering somebody who cannot be reached is a question she
 * answers and nothing happens.
 */
class ForwardingTest {

    private fun message(
        id: String,
        name: String,
        address: String?,
        at: Long,
        direction: MessageDirection = MessageDirection.OUTGOING,
        transport: BridgeTransport = BridgeTransport.THREEMA,
        group: GroupIdentity? = null,
        photo: String? = null,
        video: String? = null,
        audio: String? = null
    ) = Message(
        id = id,
        senderName = name,
        senderId = address,
        senderAddress = address,
        text = "",
        timestamp = at,
        sourcePackage = "",
        transport = transport,
        direction = direction,
        groupIdentity = group,
        photoPath = photo,
        videoPath = video,
        audioPath = audio
    )

    private fun person(name: String, threemaId: String? = "ABCD1234", phone: String? = null) =
        Recipient(name = name, threemaId = threemaId, phoneNumber = phone)

    @Test
    fun `a message of words has nothing to send on`() {
        assertNull(Forwarding.mediaOf(message("m", "Anna", "AAAA1111", 1L)))
    }

    /** The still exists because the film takes time. Sending it instead would be a quiet lie. */
    @Test
    fun `a film is sent as the film and not as its still`() {
        val film = message("m", "Anna", "AAAA1111", 1L, photo = "/still.jpg", video = "/film.mp4")
        val media = Forwarding.mediaOf(film)
        assertEquals(ForwardableMedia.Kind.VIDEO, media?.kind)
        assertEquals("/film.mp4", media?.path)
    }

    @Test
    fun `a voice message is somebody talking and a photograph is a photograph`() {
        assertEquals(
            ForwardableMedia.Kind.VOICE,
            Forwarding.mediaOf(message("m", "A", "A", 1L, audio = "/voice.m4a"))?.kind
        )
        assertEquals(
            ForwardableMedia.Kind.PHOTO,
            Forwarding.mediaOf(message("m", "A", "A", 1L, photo = "/photo.jpg"))?.kind
        )
    }

    @Test
    fun `somebody with no messenger that can carry a photograph is not offered`() {
        assertFalse(Forwarding.canCarryMedia(person("Nur SMS", threemaId = null, phone = "+41790000000")))
        assertTrue(Forwarding.canCarryMedia(person("Anna")))
    }

    /** A fan-out to a membership nobody vouched for is the reason groups are left out. */
    @Test
    fun `a group is not offered`() {
        val group = Recipient(name = "Hartmanns", groupIdentity = GroupIdentity("CREATOR1", "abcd"))
        assertFalse(Forwarding.canCarryMedia(group))
    }

    @Test
    fun `the people she wrote to last come first`() {
        val anna = person("Anna", "AAAA1111")
        val beat = person("Beat", "BBBB2222")
        val carla = person("Carla", "CCCC3333")

        val offered = Forwarding.recentRecipients(
            recipients = listOf(anna, beat, carla),
            messages = listOf(
                message("1", "Anna", "AAAA1111", at = 100L),
                message("2", "Carla", "CCCC3333", at = 300L),
                message("3", "Beat", "BBBB2222", at = 200L)
            )
        )

        assertEquals(listOf("Carla", "Beat", "Anna"), offered.map { it.name })
    }

    /** Writing to the same person twice does not put her on the list twice. */
    @Test
    fun `each person is offered once, at the last thing she sent them`() {
        val anna = person("Anna", "AAAA1111")
        val beat = person("Beat", "BBBB2222")

        val offered = Forwarding.recentRecipients(
            recipients = listOf(anna, beat),
            messages = listOf(
                message("1", "Anna", "AAAA1111", at = 100L),
                message("2", "Beat", "BBBB2222", at = 200L),
                message("3", "Anna", "AAAA1111", at = 300L)
            )
        )

        assertEquals(listOf("Anna", "Beat"), offered.map { it.name })
    }

    @Test
    fun `somebody who only ever wrote to her is not somebody she wrote to`() {
        val anna = person("Anna", "AAAA1111")
        val offered = Forwarding.recentRecipients(
            recipients = listOf(anna),
            messages = listOf(
                message("1", "Anna", "AAAA1111", at = 100L, direction = MessageDirection.INCOMING)
            )
        )
        assertTrue(offered.isEmpty())
    }

    @Test
    fun `a message to several people says nothing about who to forward to`() {
        val anna = person("Anna", "AAAA1111")
        val offered = Forwarding.recentRecipients(
            recipients = listOf(anna),
            messages = listOf(
                message(
                    "1", "Anna", "AAAA1111", at = 100L,
                    group = GroupIdentity("CREATOR1", "abcd")
                )
            )
        )
        assertTrue(offered.isEmpty())
    }

    /**
     * Everybody she has written to, not the handful that happened to fit.
     *
     * The list scrolls; cutting it left somebody she writes to every fortnight absent with no way
     * to reach her at all. The order is what makes it usable, and the order is what she did.
     */
    @Test
    fun `everybody she has written to is offered, newest first`() {
        val people = (1..12).map { person("Person $it", "ID%07d".format(it)) }
        val messages = people.mapIndexed { index, recipient ->
            message("m$index", recipient.name, recipient.threemaId, at = index.toLong())
        }

        val offered = Forwarding.recentRecipients(people, messages)

        assertEquals(12, offered.size)
        assertEquals("Person 12", offered.first().name)
        assertEquals("Person 1", offered.last().name)
    }

    /** WhatsApp can carry an attachment now; a telephone number on its own still cannot. */
    @Test
    fun `somebody on whatsapp is offered too`() {
        val onWhatsApp = Recipient(name = "Beat", whatsappId = "41791234567")
        assertTrue(Forwarding.canCarryMedia(onWhatsApp))

        val offered = Forwarding.recentRecipients(
            recipients = listOf(onWhatsApp),
            messages = listOf(
                message(
                    "1", "Beat", "41791234567@s.whatsapp.net", at = 100L,
                    transport = BridgeTransport.WHATSAPP
                )
            )
        )
        assertEquals(listOf("Beat"), offered.map { it.name })
    }

    /**
     * The bug this whole list was rewritten for.
     *
     * Whom she wrote to is a fact her messages know and her address book does not. While the list
     * was her address book filtered by her messages, a person had to survive two conditions that
     * do not hold on a real phone — a favourite carries a telephone number and no WhatsApp id,
     * and the name WhatsApp gives somebody is not the name she filed them under — and the list
     * came out empty on a phone with a hundred and fifty sent messages on it.
     */
    @Test
    fun `somebody she wrote to who is not in her address book is still offered`() {
        val offered = Forwarding.recentRecipients(
            recipients = emptyList(),
            messages = listOf(
                message(
                    "1", "Laura \"Lala Lulu\" Hartmann", "41785550101", at = 100L,
                    transport = BridgeTransport.WHATSAPP
                )
            )
        )

        assertEquals(listOf("Laura \"Lala Lulu\" Hartmann"), offered.map { it.name })
        assertEquals("41785550101", offered.single().whatsappId)
    }

    /** And where the address book does know her, she is offered under her own name and face. */
    @Test
    fun `the address book lends her face and the name she was filed under`() {
        val laura = Recipient(
            name = "Laura Hartmann",
            photoUri = "content://faces/laura",
            phoneNumber = "+41 78 555 01 01"
        )

        val offered = Forwarding.recentRecipients(
            recipients = listOf(laura),
            messages = listOf(
                message(
                    "1", "Laura \"Lala Lulu\" Hartmann", "41785550101", at = 100L,
                    transport = BridgeTransport.WHATSAPP
                )
            )
        )

        val her = offered.single()
        assertEquals("Laura Hartmann", her.name)
        assertEquals("content://faces/laura", her.photoUri)
        // Reached down the road the message went down, whatever her address book calls her.
        assertEquals("41785550101", her.whatsappId)
    }

    /** A number and a JID are the same woman, and she appears once. */
    @Test
    fun `the same person under both her addresses is one entry`() {
        val offered = Forwarding.recentRecipients(
            recipients = emptyList(),
            messages = listOf(
                message("1", "Beat", "41791234567", at = 100L, transport = BridgeTransport.WHATSAPP),
                message(
                    "2", "Beat", "41791234567@s.whatsapp.net", at = 200L,
                    transport = BridgeTransport.WHATSAPP
                )
            )
        )

        assertEquals(1, offered.size)
        assertEquals("41791234567", offered.single().whatsappId)
    }

    /** An SMS cannot carry a photograph — this app does not do MMS — so it offers nobody. */
    @Test
    fun `somebody she only ever texted is not offered`() {
        val offered = Forwarding.recentRecipients(
            recipients = emptyList(),
            messages = listOf(
                message(
                    "1", "David Thaler", "+41795550116", at = 100L,
                    transport = BridgeTransport.UNKNOWN
                )
            )
        )
        assertTrue(offered.isEmpty())
    }

    /** A status is posted at everybody and is not somewhere anything can be sent. */
    @Test
    fun `a status is not a person`() {
        val offered = Forwarding.recentRecipients(
            recipients = emptyList(),
            messages = listOf(
                message(
                    "1", "Annette", "status@broadcast", at = 100L,
                    transport = BridgeTransport.WHATSAPP
                )
            )
        )
        assertTrue(offered.isEmpty())
    }
}
