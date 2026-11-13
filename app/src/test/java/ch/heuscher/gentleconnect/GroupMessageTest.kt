package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.OnDeviceMessageMapper
import ch.heuscher.gentleconnect.data.threema.ThreemaIncomingMessage
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.canBeAnswered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMessageTest {

    private fun incoming(creator: String? = null, groupId: String? = null) =
        ThreemaIncomingMessage(
            sender = "ECHO1001",
            text = "Kommt ihr am Sonntag?",
            timestamp = 1_700_000_000_000L,
            messageId = "abc123",
            groupCreator = creator,
            groupId = groupId
        )

    @Test
    fun `a group message is carried through as one`() {
        val message = OnDeviceMessageMapper.fromThreema(incoming("TSTMBR06", "0a1b2c3d4e5f6071"))
        assertNotNull(message)
        requireNotNull(message)
        assertEquals(GroupIdentity("TSTMBR06", "0a1b2c3d4e5f6071"), message.groupIdentity)
        assertTrue(message.isGroupMessage)
    }

    @Test
    fun `a private message stays private`() {
        val message = OnDeviceMessageMapper.fromThreema(incoming())
        assertNotNull(message)
        requireNotNull(message)
        assertNull(message.groupIdentity)
        assertFalse(message.isGroupMessage)
    }

    @Test
    fun `a group message cannot be answered until we know who is in it`() {
        // The bug this exists for: before the group identity was carried through, this message
        // was indistinguishable from a private one, and answering it sent a private reply to
        // whoever happened to write while the rest of the group saw nothing. She had no way to
        // notice — she answered what looked like a private message and got a private reply.
        //
        // A message straight off the wire has not been through GroupReplyPolicy yet, so it is
        // unanswerable — which is the right way round for a default: the membership we have not
        // checked is exactly the membership we might be short a person on.
        val group = OnDeviceMessageMapper.fromThreema(incoming("TSTMBR06", "0a1b2c3d4e5f6071"))!!
        val private = OnDeviceMessageMapper.fromThreema(incoming())!!

        assertFalse(group.canBeAnswered)
        assertTrue(private.canBeAnswered)
    }

    @Test
    fun `a group whose members we were told can be answered`() {
        // The other half of the same rule, once the reader has been through GroupReplyPolicy and
        // set the flag. Without this the reply field would never appear for anybody.
        val group = OnDeviceMessageMapper.fromThreema(incoming("TSTMBR06", "0a1b2c3d4e5f6071"))!!
        assertTrue(group.copy(groupCanBeAnswered = true).canBeAnswered)
    }

    @Test
    fun `a half-identified group is not treated as private`() {
        // A creator with no group id names no group. Answering it would reach one person, which
        // is the failure this whole change exists to prevent, so it must not fall through to the
        // private path.
        assertNull(GroupIdentity.of("TSTMBR06", null))
        assertNull(GroupIdentity.of(null, "0a1b"))
        assertNull(GroupIdentity.of("  ", "0a1b"))
        assertNull(GroupIdentity.of("TSTMBR06", "   "))
    }

    @Test
    fun `the same group written differently is the same group`() {
        assertEquals(
            GroupIdentity.of("tstmbr06", "0A1B2C3D4E5F6071"),
            GroupIdentity.of(" TSTMBR06 ", " 0a1b2c3d4e5f6071 ")
        )
    }

    @Test
    fun `a group message from the bridge is refused too`() {
        // Nothing produces these yet, but canBeAnswered is what the reply UI asks, and it must
        // not depend on which route carried the message.
        val bridged = Message(
            id = "bridge_1",
            senderName = "Anna",
            senderId = "ECHO1001",
            text = "Kommt ihr am Sonntag?",
            timestamp = 1L,
            sourcePackage = "",
            channel = MessageChannel.BRIDGE,
            groupIdentity = GroupIdentity("TSTMBR06", "0a1b")
        )
        assertFalse(bridged.canBeAnswered)
    }
}
