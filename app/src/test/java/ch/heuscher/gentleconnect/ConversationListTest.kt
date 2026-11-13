package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.ConversationList
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a row in the list of messages says.
 *
 * Found on the phone: she answered the family group, went back to the list, and the row still
 * read "Yesterday · Guten Abend zusammen". Her reply had gone — it reached both members — and the
 * one screen she would check to see whether it had said nothing about it.
 */
class ConversationListTest {

    private fun message(
        id: String,
        conversation: String?,
        text: String,
        at: Long,
        sender: String = "Familie Hartmann",
        outgoing: Boolean = false
    ) = Message(
        id = id,
        senderName = sender,
        text = text,
        timestamp = at,
        sourcePackage = "",
        conversationId = conversation,
        direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING
    )

    private fun collapse(messages: List<Message>) =
        ConversationList.newestPerConversation(messages) { it.conversationId }

    @Test
    fun `the row shows what she said, when what she said is the newest thing`() {
        val theirs = message("1", "group:familie", "Guten Abend zusammen", at = 100)
        val hers = message("2", "group:familie", "Test 19h41", at = 200, outgoing = true)

        val rows = collapse(listOf(theirs, hers))

        assertEquals(1, rows.size)
        assertEquals("Test 19h41", rows.single().text)
    }

    @Test
    fun `one row per conversation, newest first`() {
        val rows = collapse(
            listOf(
                message("1", "person:simon", "older", at = 100, sender = "Simon"),
                message("2", "group:familie", "newest", at = 300),
                message("3", "person:simon", "middle", at = 200, sender = "Simon")
            )
        )

        assertEquals(listOf("newest", "middle"), rows.map { it.text })
    }

    @Test
    fun `four identical roaming texts still occupy one row`() {
        // The rule this replaced. Her carrier sent the same tariff four times; the list opened on
        // four identical cards while the home screen showed one, and the same message must not
        // look like one thing or four depending on how far she has scrolled.
        val roaming = (1..4).map {
            message("r$it", conversation = null, text = "Surfen im Ausland", at = 100L + it,
                sender = "RoamingInfo")
        }

        assertEquals(1, collapse(roaming).size)
    }

    @Test
    fun `two different messages from the same service keep a row each`() {
        // There is no conversation to open for a service, so folding them together would hide
        // one of them behind the other with no way to reach it.
        val rows = collapse(
            listOf(
                message("a", conversation = null, text = "Auftrag abholbereit", at = 200,
                    sender = "Reinigung!"),
                message("b", conversation = null, text = "Liefertag wählen", at = 100,
                    sender = "Zur Rose")
            )
        )

        assertEquals(2, rows.size)
    }

    @Test
    fun `nothing in means nothing out`() {
        assertEquals(emptyList<Message>(), collapse(emptyList()))
    }

    @Test
    fun `a message she sent is not shown twice because the phone knows it twice`() {
        // What the app recorded when she pressed send, and the same message read back out of the
        // telephone's sent box by the SMS import on the next launch. Different ids, different
        // timestamps by a few seconds — one message.
        val recorded = message("out_1", "phone:41791234567", "Ja gerne", at = 100_000, outgoing = true)
            .copy(senderName = "Claire-Lise")
        val fromThePhone = message("sms_88", "phone:41791234567", "Ja gerne", at = 104_000, outgoing = true)
            .copy(senderName = "Claire-Lise")

        val joined = ConversationList.joined(listOf(recorded), listOf(fromThePhone))

        assertEquals(1, joined.size)
        // The app's own copy survives: it is the one carrying whether the message actually went.
        assertEquals("out_1", joined.single().id)
    }

    @Test
    fun `what they wrote is never folded into what she wrote`() {
        val question = message("1", "phone:41791234567", "Ja gerne", at = 100_000, sender = "Claire-Lise")
        val answer = message("out_1", "phone:41791234567", "Ja gerne", at = 108_000, sender = "Claire-Lise", outgoing = true)

        assertEquals(2, ConversationList.joined(listOf(answer), listOf(question)).size)
    }

    @Test
    fun `two things somebody really said both stay`() {
        val first = message("1", "group:familie", "Guten Abend", at = 100)
        val later = message("2", "group:familie", "Guten Abend", at = 100_000)

        assertEquals(2, ConversationList.joined(listOf(first), listOf(later)).size)
    }
}
