package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.LocalMessageStore
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "delete on this phone" actually removes.
 *
 * The store files a message under a conversation by more than the `conversationId` it happens to
 * carry — a message that arrived before the conversation existed has none at all, and is reached
 * by its telephone number instead. So the interesting question is never "does deleting work" but
 * "does deleting a conversation take with it the messages that were only implicitly part of it",
 * and, just as importantly, does it leave the neighbouring conversation alone.
 */
class LocalMessageDeletionTest {

    private fun message(
        id: String,
        conversationId: String? = null,
        senderAddress: String? = null,
        senderId: String? = null,
        groupIdentity: GroupIdentity? = null
    ) = Message(
        id = id,
        senderName = "Julia",
        senderAddress = senderAddress,
        senderId = senderId,
        // Distinct per message on purpose. The store's duplicate rule is [Message.isSameMessageAs]
        // — same sender, same words, within thirty seconds — so messages built from one template
        // would be folded into a single row before any of these tests got to delete anything.
        text = "Bis später ($id)",
        timestamp = 1_000L,
        sourcePackage = "ch.threema.app",
        conversationId = conversationId,
        groupIdentity = groupIdentity
    )

    private fun storeOf(vararg messages: Message) = LocalMessageStore().apply {
        addOrUpdateMessages(messages.toList())
    }

    @Test
    fun `deleting one message leaves the rest of the conversation standing`() {
        val store = storeOf(
            message("m1", conversationId = "phone:41791234567"),
            message("m2", conversationId = "phone:41791234567")
        )

        store.deleteMessage("m1")

        assertEquals(setOf("m2"), store.messages.value.map { it.id }.toSet())
    }

    @Test
    fun `deleting a conversation also takes the messages only its number identifies`() {
        // m2 carries no conversationId — the state every message is in before it has been filed.
        // Deleting the conversation and leaving it behind is what "the message came back" looks
        // like from her side of the screen.
        val store = storeOf(
            message("m1", conversationId = "phone:41791234567"),
            message("m2", senderAddress = "+41 79 123 45 67"),
            message("m3", conversationId = "phone:41797654321")
        )

        store.deleteConversation("phone:41791234567")

        assertEquals(setOf("m3"), store.messages.value.map { it.id }.toSet())
    }

    @Test
    fun `the ids handed to the database are the ones the screen just lost`() {
        // These two must not disagree: the screen deletes by the whole matching rule, the
        // `messages` table only knows the column it was written with.
        val store = storeOf(
            message("m1", conversationId = "phone:41791234567"),
            message("m2", senderAddress = "+41 79 123 45 67"),
            message("m3", conversationId = "phone:41797654321")
        )

        val ids = store.conversationMessageIds("phone:41791234567")
        store.deleteConversation("phone:41791234567")

        assertEquals(setOf("m1", "m2"), ids.toSet())
        assertTrue(store.messages.value.none { it.id in ids })
    }

    @Test
    fun `a group conversation is deleted by its identity, not by whoever wrote in it`() {
        val family = GroupIdentity("TSTMBR01", "520c414019fad31b")
        val store = storeOf(
            message("m1", groupIdentity = family),
            message("m2", senderId = "TSTMBR01", conversationId = "threema:TSTMBR01")
        )

        store.deleteConversation("group:TSTMBR01:520c414019fad31b")

        // The same person writing to her privately is a different conversation and stays.
        assertEquals(setOf("m2"), store.messages.value.map { it.id }.toSet())
    }

    @Test
    fun `an empty key deletes nothing`() {
        // Guarded because a blank key matches nothing deliberately rather than everything by
        // accident, and the difference between those two is her whole message history.
        val store = storeOf(
            message("m1", conversationId = "phone:41791234567"),
            message("m2", conversationId = "phone:41797654321")
        )

        store.deleteConversation("")

        assertEquals(2, store.messages.value.size)
    }
}
