package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.GroupVisibility
import ch.heuscher.gentleconnect.model.KnownGroup
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.StatusBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the several-people conversations she is shown, as decided remotely by a caregiver.
 *
 * Everything here is invisible when it goes wrong: a group hidden by a key that did not match is
 * a family that has simply stopped writing, as far as she can tell, and a group shown that should
 * not be is thirty messages a day on a home screen with room for three.
 */
class GroupVisibilityTest {

    private val family = GroupIdentity("TSTMBR06", "0a1b2c3d4e5f6071")
    private val choir = GroupIdentity("ECHO1001", "99887766554433221")

    private fun message(
        id: String,
        group: GroupIdentity? = null,
        direction: MessageDirection = MessageDirection.INCOMING
    ) = Message(
        id = id,
        senderName = "Anna",
        text = "Kommt ihr am Sonntag?",
        timestamp = 1_700_000_000_000L,
        sourcePackage = "ch.threema.app",
        groupIdentity = group,
        direction = direction
    )

    @Test
    fun `nothing is hidden until somebody hides it`() {
        val messages = listOf(message("1", family), message("2"))
        assertEquals(messages, GroupVisibility.visible(messages, emptySet()))
    }

    @Test
    fun `a switched-off group goes, and only that group`() {
        val hidden = GroupVisibility.hiddenKeys(listOf(GroupVisibility.keyOf(choir)))
        val messages = listOf(message("1", family), message("2", choir), message("3"))

        val shown = GroupVisibility.visible(messages, hidden)

        assertEquals(listOf("1", "3"), shown.map { it.id })
    }

    @Test
    fun `her own messages to a hidden group go with it`() {
        // Half a conversation is stranger than none: her reply, on the list, under a group name,
        // with nothing it was a reply to.
        val hidden = GroupVisibility.hiddenKeys(listOf(GroupVisibility.keyOf(family)))
        val messages = listOf(message("1", family, MessageDirection.OUTGOING))

        assertTrue(GroupVisibility.visible(messages, hidden).isEmpty())
    }

    @Test
    fun `the key is the creator and the id, whatever case they arrive in`() {
        // The website stores this string and hands it back. A group renamed in the messenger must
        // stay switched off, and two groups sharing a name must not switch each other off.
        assertEquals("tstmbr06:0a1b2c3d4e5f6071", GroupVisibility.keyOf(family))
        assertEquals(
            GroupVisibility.keyOf(family),
            GroupVisibility.keyOf(GroupIdentity("tstmbr06", "0A1B2C3D4E5F6071"))
        )
    }

    @Test
    fun `a stray capital in the stored list still matches`() {
        val hidden = GroupVisibility.hiddenKeys(listOf(" TSTMBR06:0A1B2C3D4E5F6071 ", "", null))
        assertTrue(GroupVisibility.isHidden(message("1", family), hidden))
        assertEquals(1, hidden.size)
    }

    @Test
    fun `a status is not a group and cannot be switched off as one`() {
        // A status wears a group's clothes — see StatusBroadcast — and belongs to the Look screen,
        // which this list has nothing to do with.
        val posted = GroupIdentity("ECHO1001", StatusBroadcast.JID)
        val status = message("1", posted)
        val hidden = GroupVisibility.hiddenKeys(listOf(GroupVisibility.keyOf(posted)))

        assertFalse(GroupVisibility.isHidden(status, hidden))
    }

    @Test
    fun `the caregiver is offered named groups, in a stable order`() {
        val known = GroupVisibility.known(mapOf(family to "Turnverein", choir to "  Familie  "))

        assertEquals(
            listOf(
                KnownGroup(GroupVisibility.keyOf(choir), "Familie"),
                KnownGroup(GroupVisibility.keyOf(family), "Turnverein")
            ),
            known
        )
    }

    @Test
    fun `a group with no name yet is not a decision anybody can take`() {
        assertTrue(GroupVisibility.known(mapOf(family to "   ")).isEmpty())
    }
}
