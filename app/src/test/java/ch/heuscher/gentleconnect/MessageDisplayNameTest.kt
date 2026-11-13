package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.Nudge
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageDisplayNameTest {

    private fun message(senderName: String, groupName: String? = null) = Message(
        id = "m1",
        senderName = senderName,
        senderId = "TSTMBR01",
        text = "Guten Abend zusammen",
        timestamp = 0L,
        sourcePackage = "ch.threema.app",
        groupIdentity = groupName?.let { GroupIdentity("TSTMBR01", "520c414019fad31b") },
        groupName = groupName
    )

    @Test
    fun `a message from one person is that person`() {
        assertEquals("Anna", message("Anna").displayName)
    }

    @Test
    fun `a message to several people is the group`() {
        assertEquals("Familie Hartmann", message("TSTMBR01", "Familie Hartmann").displayName)
    }

    @Test
    fun `the nudge names the person who wrote, not the group they wrote to`() {
        // The card answers one question — who is asking for her — and in a group the answer is her
        // daughter, not the group's name. The list behind it goes on naming the group, because a
        // list is about conversations and the card is about a person.
        val group = message("Anna", "Familie Hartmann")
        assertEquals("Anna", Nudge.MessageNudge(group).senderName)
        assertEquals("Familie Hartmann", group.displayName)
    }

    @Test
    fun `a sender nobody could name falls back to the group and never to a code`() {
        // This is what the fallback is for. It went wrong once in the other direction: the nudge —
        // the first thing she reads — said "TSTMBR01 wrote a message", naming a code at her
        // instead of the people who wrote.
        val group = message("TSTMBR01", "Familie Hartmann")
        assertEquals("Familie Hartmann", Nudge.MessageNudge(group).senderName)
    }

    @Test
    fun `a message from one person names that person on the card too`() {
        assertEquals("Anna", Nudge.MessageNudge(message("Anna")).senderName)
    }
}
