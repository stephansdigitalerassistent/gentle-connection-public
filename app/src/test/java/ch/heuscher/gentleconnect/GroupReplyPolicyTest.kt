package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.GroupReplyPolicy
import ch.heuscher.gentleconnect.data.GroupSendOutcome
import ch.heuscher.gentleconnect.model.GroupIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether several people may be answered.
 *
 * Every one of these is a mistake she could not detect: a reply that reached four of the five
 * people it named looks, on her screen and on theirs, exactly like one that reached everybody.
 */
class GroupReplyPolicyTest {

    private val group = GroupIdentity("TSTMBR01", "520c414019fad31b")
    private val her = "TSTMBR02"

    private fun decide(
        members: List<String>,
        lastSetupAt: Long = 1_700_000_000_000L,
        own: String? = her,
        group: GroupIdentity = this.group
    ) = GroupReplyPolicy.decide(group, own, members, lastSetupAt)

    @Test
    fun `a group we were sent a member list for can be answered`() {
        val decision = decide(listOf(her, "TSTMBR03"))
        assertEquals(
            GroupReplyPolicy.Decision.SendTo(listOf("TSTMBR01", "TSTMBR03")),
            decision
        )
    }

    @Test
    fun `the creator is written to even though the member list leaves them out`() {
        // Threema's setup message does not repeat the creator — they are the one sending it. Left
        // out here too, the one person certain to be in the group would be the one person her
        // reply never reached.
        val decision = decide(listOf(her)) as GroupReplyPolicy.Decision.SendTo
        assertEquals(listOf("TSTMBR01"), decision.recipients)
    }

    @Test
    fun `she is never one of the recipients of her own message`() {
        val decision = decide(listOf(her, "TSTMBR03", "ECHO1001")) as GroupReplyPolicy.Decision.SendTo
        assertFalse(her in decision.recipients)
    }

    @Test
    fun `a member list restored from a backup is a member list`() {
        // Threema's own backup writes the roster with the creator in it and with her own identity
        // in it exactly when she is a member — unlike a setup message, which leaves the creator
        // out. Both spellings have to come out as the same three people, or a restored group is
        // answerable in theory and refused in practice.
        val decision = decide(listOf("TSTMBR07", "TSTMBR01", her)) as GroupReplyPolicy.Decision.SendTo

        assertEquals(listOf("TSTMBR01", "TSTMBR07"), decision.recipients)
    }

    @Test
    fun `a group nobody has sent a member list for cannot be answered`() {
        // The whole point of the stage. A group that existed before this app was installed and has
        // been quiet since is a group whose membership we are guessing at, and a reply fanned out
        // to a guess reaches some of the family and looks exactly like reaching all of them.
        assertEquals(
            GroupReplyPolicy.Decision.Refused(GroupReplyPolicy.Reason.MEMBERSHIP_UNKNOWN),
            decide(members = listOf(her, "TSTMBR03"), lastSetupAt = 0L)
        )
    }

    @Test
    fun `a group she was removed from cannot be answered`() {
        // An empty member list is exactly how being removed arrives, and it does arrive: three of
        // the fourteen setup messages captured off the wire had no members at all.
        assertEquals(
            GroupReplyPolicy.Decision.Refused(GroupReplyPolicy.Reason.NO_LONGER_A_MEMBER),
            decide(members = emptyList())
        )
        assertEquals(
            GroupReplyPolicy.Decision.Refused(GroupReplyPolicy.Reason.NO_LONGER_A_MEMBER),
            decide(members = listOf("TSTMBR03", "ECHO1001"))
        )
    }

    @Test
    fun `a group of only herself has nobody to write to`() {
        assertEquals(
            GroupReplyPolicy.Decision.Refused(GroupReplyPolicy.Reason.NOBODY_LEFT_TO_REACH),
            decide(members = listOf(her), group = GroupIdentity(her, "520c414019fad31b"))
        )
    }

    @Test
    fun `without her own identity nothing is answered`() {
        // She would otherwise be sent her own message, which arrives looking like somebody having
        // written to her — to the person here least able to work out that she wrote it herself.
        assertEquals(
            GroupReplyPolicy.Decision.Refused(GroupReplyPolicy.Reason.MEMBERSHIP_UNKNOWN),
            decide(members = listOf(her, "TSTMBR03"), own = null)
        )
    }

    @Test
    fun `identities are read the way they are written down`() {
        val decision = decide(listOf(" tstmbr02 ", "tstmbr03")) as GroupReplyPolicy.Decision.SendTo
        assertEquals(listOf("TSTMBR01", "TSTMBR03"), decision.recipients)
    }

    @Test
    fun `something that is not an identity is not written to`() {
        // A truncated or corrupted entry would otherwise be sent to the directory server as though
        // it were a person, and a member we cannot name is a member the fan-out silently drops.
        val decision = decide(listOf(her, "TSTMBR03", "not-an-id", "")) as GroupReplyPolicy.Decision.SendTo
        assertEquals(listOf("TSTMBR01", "TSTMBR03"), decision.recipients)
    }

    @Test
    fun `the same group always produces the same list`() {
        val first = decide(listOf(her, "TSTMBR03", "ECHO1001")) as GroupReplyPolicy.Decision.SendTo
        val second = decide(listOf("ECHO1001", "TSTMBR03", her)) as GroupReplyPolicy.Decision.SendTo
        assertEquals(first.recipients, second.recipients)
    }

    @Test
    fun `reaching everybody is the only success`() {
        val intended = listOf("TSTMBR03", "TSTMBR01", "ECHO1001")
        assertTrue(GroupSendOutcome.succeeded(intended, intended))
        // "Sent to 2 of 3" is a fact she can do nothing with: she cannot tell which two, cannot
        // resend to the third, and cannot ask. So it is a failure, and her message stays visibly
        // unfinished rather than sitting there looking like one that arrived.
        assertFalse(GroupSendOutcome.succeeded(intended, listOf("TSTMBR03", "TSTMBR01")))
        assertFalse(GroupSendOutcome.succeeded(intended, emptyList()))
    }

    @Test
    fun `a fan-out to nobody is not a success`() {
        assertFalse(GroupSendOutcome.succeeded(emptyList(), emptyList()))
    }
}
