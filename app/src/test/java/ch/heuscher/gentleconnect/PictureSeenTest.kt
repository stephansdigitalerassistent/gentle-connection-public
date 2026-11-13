package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.PictureSeen
import ch.heuscher.gentleconnect.model.StatusBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureSeenTest {

    private fun message(id: String) = Message(
        id = id,
        senderName = "Test",
        senderAddress = "+41791234567",
        contactId = "c",
        text = "",
        timestamp = 0L,
        sourcePackage = "",
        groupIdentity = GroupIdentity.of("group", StatusBroadcast.JID)
    )

    @Test
    fun `unseenAny returns true if any message is not in seen set`() {
        val messages = listOf(message("m1"), message("m2"), message("m3"))
        
        assertTrue(PictureSeen.unseenAny(messages, setOf("m1", "m2")))
        assertFalse(PictureSeen.unseenAny(messages, setOf("m1", "m2", "m3")))
    }

    @Test
    fun `pruned returns seen plus added when present is empty`() {
        val seen = setOf("m1", "m2")
        val added = "m3"
        val present = emptySet<String>()

        val result = PictureSeen.pruned(seen, added, present)
        assertEquals(setOf("m1", "m2", "m3"), result)
    }

    @Test
    fun `pruned returns intersection of seen and present plus added when present is not empty`() {
        val seen = setOf("m1", "m2", "m3")
        val added = "m4"
        val present = setOf("m2", "m3", "m5")

        val result = PictureSeen.pruned(seen, added, present)
        // Should keep m2, m3 from seen, and add m4. Drops m1 because it's not in present.
        assertEquals(setOf("m2", "m3", "m4"), result)
    }

    @Test
    fun `pruned with all present marks all present seen`() {
        val seen = setOf("m1")
        val present = setOf("m1", "m2", "m3")
        val added = present

        val result = PictureSeen.pruned(seen, added, present)
        assertEquals(setOf("m1", "m2", "m3"), result)
    }
}
