package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.ConversationReply
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.MessageDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Who a reply goes to, in the conversations that actually caused trouble on the phone.
 */
class ConversationReplyTest {

    private fun arrived(
        id: String,
        from: String,
        address: String? = "+41791234567",
        channel: MessageChannel = MessageChannel.DEVICE,
        at: Long = 1_000L
    ) = Message(
        id = id,
        senderName = from,
        senderAddress = address,
        text = "hello",
        timestamp = at,
        sourcePackage = "com.google.android.apps.messaging",
        channel = channel
    )

    /** One of hers, stored the way `recordOutgoing` stores it: the *other* person in the name. */
    private fun hers(
        id: String,
        to: String,
        address: String? = "+41791234567",
        channel: MessageChannel = MessageChannel.DEVICE,
        at: Long = 1_000L
    ) = Message(
        id = id,
        senderName = to,
        senderAddress = address,
        senderId = address,
        text = "hello",
        timestamp = at,
        sourcePackage = "",
        isRead = true,
        channel = channel,
        direction = MessageDirection.OUTGOING
    )

    @Test
    fun `a conversation holding only her own message can still be written to`() {
        // The one this exists for: she wrote to somebody who has not written back, and the reply
        // field used to be missing, so the conversation she started was one she could not continue.
        val conversation = listOf(hers("out_1", to = "David"))

        assertEquals("out_1", ConversationReply.targetIn(conversation)?.id)
    }

    @Test
    fun `an arrived message is preferred to one of her own`() {
        val conversation = listOf(
            hers("out_1", to = "David", at = 1_000L),
            arrived("in_1", from = "David", at = 2_000L)
        )

        assertEquals("in_1", ConversationReply.targetIn(conversation)?.id)
    }

    @Test
    fun `the newest arrived message wins, not the newest message`() {
        // Unchanged by the fallback: her own newer message must not displace the person who wrote.
        val conversation = listOf(
            arrived("in_1", from = "David", at = 1_000L),
            hers("out_1", to = "David", at = 2_000L)
        )

        assertEquals("in_1", ConversationReply.targetIn(conversation)?.id)
    }

    @Test
    fun `a service she wrote to is still not somebody she can write to`() {
        // Her carrier's short code. There is no subscriber behind it in either direction.
        val conversation = listOf(hers("out_1", to = "RoamingInfo", address = "444"))

        assertNull(ConversationReply.targetIn(conversation))
    }

    @Test
    fun `one of her own with no address behind it names nobody`() {
        val conversation = listOf(hers("out_1", to = "David", address = null))

        assertNull(ConversationReply.targetIn(conversation))
    }

    @Test
    fun `her own message to several people is not a way back into the group`() {
        // groupCanBeAnswered is worked out when a message arrives, so her own row never carries
        // it. Answering anyway would fan out to a membership nobody confirmed, which reaches some
        // of the family and looks exactly like reaching all of them.
        val conversation = listOf(
            hers("out_1", to = "Familie", channel = MessageChannel.ON_DEVICE).copy(
                groupIdentity = GroupIdentity.of("ABCD1234", "TSTMBR01")
            )
        )

        assertNull(ConversationReply.targetIn(conversation))
    }

    @Test
    fun `an empty conversation has nobody to write to`() {
        assertNull(ConversationReply.targetIn(emptyList()))
    }
}
