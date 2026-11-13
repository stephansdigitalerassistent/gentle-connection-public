package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppGroups
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppMessageMapper
import ch.heuscher.gentleconnect.model.GroupIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answering several people at once, on the messenger where that is easy.
 *
 * The thing being pinned down is the difference from Threema, because the difference is what she
 * experiences: a Threema group cannot be answered until this app learns who is in it, and a
 * WhatsApp group can be answered immediately.
 */
class WhatsAppGroupsTest {

    private val familyChat = "1234567890-1234567890@g.us"
    private val family = GroupIdentity.of(WhatsAppMessageMapper.GROUP_CREATOR, familyChat)!!

    @Test
    fun `a whatsapp group can be answered the moment it arrives`() {
        // No control message, no member list, no waiting. WhatsApp delivers one message to the
        // group address and everybody gets it, including whoever joined this morning.
        assertTrue(WhatsAppGroups.canBeAnswered(family))
        assertEquals(familyChat, WhatsAppGroups.jidOf(family))
    }

    @Test
    fun `a threema group is not answered through here`() {
        // Threema groups go through GroupReplyPolicy, which refuses until membership is known.
        // Answering one through this path would send to an address Threema does not have.
        val threema = GroupIdentity.of("ECHO1001", "0011223344556677")!!

        assertFalse(WhatsAppGroups.canBeAnswered(threema))
        assertNull(WhatsAppGroups.jidOf(threema))
    }

    @Test
    fun `nothing is not a group`() {
        assertFalse(WhatsAppGroups.canBeAnswered(null))
        assertNull(WhatsAppGroups.jidOf(null))
    }

    @Test
    fun `a person's address is never treated as a group`() {
        // The failure this stops: a reply addressed to a group that is really one person, or
        // worse, a group reply sent to whichever member happened to write.
        val notAGroup = GroupIdentity.of(
            WhatsAppMessageMapper.GROUP_CREATOR,
            "41791234567@s.whatsapp.net"
        )!!

        assertFalse(WhatsAppGroups.canBeAnswered(notAGroup))
        assertNull(WhatsAppGroups.jidOf(notAGroup))
    }

    @Test
    fun `a group keeps the name the family gave it`() {
        assertEquals(
            "Familie Hartmann",
            WhatsAppGroups.displayName("Familie Hartmann", 6) { "never used" }
        )
    }

    @Test
    fun `an unnamed group is described rather than shown as an address`() {
        // Plenty of families never name their group. Showing the JID would be showing her an
        // address, which is the one thing her screen never does — and the words that go here
        // never say "group", which is the rule above group_and in strings.xml.
        assertEquals(
            "6 people",
            WhatsAppGroups.displayName(null, 6) { count -> "$count people" }
        )
        assertEquals(
            "6 people",
            WhatsAppGroups.displayName("   ", 6) { count -> "$count people" }
        )
    }

    @Test
    fun `a group with no name and nobody in it is not described at all`() {
        // Better to leave the existing name alone than to overwrite it with "0 people".
        assertNull(WhatsAppGroups.displayName(null, 0) { "never used" })
        assertNull(WhatsAppGroups.displayName("", -1) { "never used" })
    }

    @Test
    fun `a member is filed under the same key as the messages they wrote`() {
        // The whole point of the key: WhatsAppMessageMapper stores a WhatsApp message under the
        // bare digits of the sender's number, and GroupFaces uppercases whatever it is given. If
        // these two disagree, every member is drawn as silent and every speaker as a stranger who
        // has left the group.
        assertEquals("41791234567", WhatsAppGroups.faceKey("41791234567@s.whatsapp.net"))
    }

    @Test
    fun `answering from a laptop does not make somebody a second person`() {
        assertEquals("41791234567", WhatsAppGroups.faceKey("41791234567:12@s.whatsapp.net"))
    }

    @Test
    fun `a member with no number behind them is still filed under something`() {
        // A hidden-number address the bridge could not resolve. Nothing they write will find its
        // way to this face, but they are in the group and the group says how many people are in
        // it — so they are kept, and PersonName draws them as Someone rather than as an address.
        assertEquals("2345678901@LID", WhatsAppGroups.faceKey("2345678901@lid"))
    }

    @Test
    fun `nothing is not a member`() {
        assertNull(WhatsAppGroups.faceKey(null))
        assertNull(WhatsAppGroups.faceKey("  "))
        assertNull(WhatsAppGroups.faceKey(familyChat))
    }

    @Test
    fun `the group itself is not one of the people in it`() {
        val members = WhatsAppGroups.memberAddresses(
            listOf("41791234567@s.whatsapp.net", familyChat, " ", "41791234567@s.whatsapp.net")
        )

        assertEquals(listOf("41791234567@s.whatsapp.net"), members)
    }

    @Test
    fun `a group nobody has described yet has no members rather than none`() {
        // Empty means "not told yet", and GroupFaces reads it that way: it falls back to whoever
        // has written. Not knowing must never be drawn as an empty group.
        assertEquals(emptyList<String>(), WhatsAppGroups.memberAddresses(null))
    }
}
