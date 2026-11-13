package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.GroupVisibility
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.MessageVisibility
import ch.heuscher.gentleconnect.model.RemoteSettings
import ch.heuscher.gentleconnect.model.StatusBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules for what messages count as visible to her.
 */
class MessageVisibilityTest {

    private fun message(
        id: String,
        conversationId: String? = null,
        text: String = "Hallo",
        at: Long = 1_700_000_000_000L,
        sender: String = "Son",
        senderAddress: String? = "+41791234567",
        direction: MessageDirection = MessageDirection.INCOMING,
        groupIdentity: GroupIdentity? = null,
        contactId: String? = null
    ) = Message(
        id = id,
        senderName = sender,
        senderAddress = senderAddress,
        text = text,
        timestamp = at,
        sourcePackage = "",
        conversationId = conversationId,
        direction = direction,
        groupIdentity = groupIdentity,
        contactId = contactId
    )

    @Test
    fun `a status is not a conversation`() {
        val posted = GroupIdentity.of("whatsapp-group", StatusBroadcast.JID)
        val status = message("status_1", groupIdentity = posted)
        assertTrue(status.isStatus)
        val ordinary = message("msg_1", conversationId = "conv_1")

        val result = MessageVisibility.visible(
            arrived = listOf(status, ordinary),
            stored = listOf(ordinary),
            settings = RemoteSettings()
        ) { it.conversationId }

        assertEquals(listOf("msg_1"), result.map { it.id })
    }

    @Test
    fun `a hidden group disappears entirely including her own replies to it`() {
        val family = GroupIdentity("TSTMBR06", "0a1b2c3d4e5f6071")
        val hiddenKey = GroupVisibility.keyOf(family)
        val incoming = message("in_1", conversationId = "group_fam", groupIdentity = family)
        val reply = message("out_1", conversationId = "group_fam", groupIdentity = family, direction = MessageDirection.OUTGOING)
        val other = message("in_2", conversationId = "conv_other")

        val settings = RemoteSettings(hiddenGroups = listOf(hiddenKey))

        val result = MessageVisibility.visible(
            arrived = listOf(incoming, other),
            stored = listOf(reply, incoming, other),
            settings = settings
        ) { it.conversationId }

        assertEquals(listOf("in_2"), result.map { it.id })
    }

    @Test
    fun `a sender judged a person survives the company filter`() {
        val surgery = message("msg_1", conversationId = "surgery", sender = "Praxis Dr. Meier", senderAddress = "4144")
        val laundry = message("msg_2", conversationId = "laundry", sender = "Reinigung!", senderAddress = "Reinigung!")
        val friend = message("msg_3", conversationId = "friend", sender = "Anna", senderAddress = "+41791234567")

        val settings = RemoteSettings(
            showCompanyMessages = false,
            sendersAsPeople = listOf("4144")
        )

        val result = MessageVisibility.visible(
            arrived = listOf(surgery, laundry, friend),
            stored = emptyList(),
            settings = settings
        ) { it.conversationId }

        assertEquals(listOf("msg_1", "msg_3"), result.map { it.id })
        assertTrue(result.first { it.id == "msg_1" }.treatedAsPerson)
    }

    @Test
    fun `only the newest per conversation comes back`() {
        val older = message("m_1", conversationId = "conv_1", text = "older", at = 100)
        val newer = message("m_2", conversationId = "conv_1", text = "newer", at = 200)
        val other = message("m_3", conversationId = "conv_2", text = "other", at = 150)

        val result = MessageVisibility.visible(
            arrived = listOf(newer, older, other),
            stored = emptyList(),
            settings = RemoteSettings()
        ) { it.conversationId }

        assertEquals(listOf("m_2", "m_3"), result.map { it.id })
    }
}
