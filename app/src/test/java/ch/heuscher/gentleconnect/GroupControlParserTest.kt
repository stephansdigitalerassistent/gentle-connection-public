package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.GroupControlMessage
import ch.heuscher.gentleconnect.data.GroupControlParser
import ch.heuscher.gentleconnect.model.GroupIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupControlParserTest {

    private val creator = "TSTMBR06"
    private val groupIdHex = "0a1b2c3d4e5f6071"

    private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `a group setup names the group and everybody in it`() {
        val control = GroupControlParser.parse(
            sender = creator,
            messageType = GroupControlParser.TYPE_SETUP,
            dataHex = groupIdHex + ascii("ECHO1001") + ascii("TSTMBR06") + ascii("PEER0001")
        )

        assertEquals(
            GroupControlMessage.Setup(
                GroupIdentity(creator, groupIdHex),
                listOf("ECHO1001", "TSTMBR06", "PEER0001")
            ),
            control
        )
    }

    @Test
    fun `a group setup takes its creator from the sender, not from the body`() {
        // The trap this parser exists for. libthreema decodes everything at or above 0x40 as if
        // the body began with an 8-byte creator identity, which is wrong for setup and rename —
        // their bodies start with the group id, because the creator is the sender and is not
        // repeated. Reading the first eight bytes as a creator here would produce a group nobody
        // is in, under an identity nobody has.
        val control = GroupControlParser.parse(
            sender = "peer0001",
            messageType = GroupControlParser.TYPE_SETUP,
            dataHex = groupIdHex + ascii("ECHO1001")
        ) as GroupControlMessage.Setup

        assertEquals("PEER0001", control.group.creatorIdentity)
        assertEquals(groupIdHex, control.group.groupId)
    }

    @Test
    fun `an empty member list is a group she is no longer in, not a parse failure`() {
        val control = GroupControlParser.parse(
            sender = creator,
            messageType = GroupControlParser.TYPE_SETUP,
            dataHex = groupIdHex
        )

        assertEquals(
            GroupControlMessage.Setup(GroupIdentity(creator, groupIdHex), emptyList()),
            control
        )
    }

    @Test
    fun `half a member list is no member list at all`() {
        // A member list is what a reply will one day be fanned out to. Keeping the readable part
        // of a damaged list is how a message reaches three of five people while looking exactly
        // like it reached everybody, which is the failure Stage 3 is designed against.
        assertNull(
            GroupControlParser.parse(
                sender = creator,
                messageType = GroupControlParser.TYPE_SETUP,
                // Second identity contains a space, which no Threema ID does.
                dataHex = groupIdHex + ascii("ECHO1001") + ascii("PEER 001")
            )
        )
    }

    @Test
    fun `a truncated member list is refused rather than rounded down`() {
        assertNull(
            GroupControlParser.parse(
                sender = creator,
                messageType = GroupControlParser.TYPE_SETUP,
                dataHex = groupIdHex + ascii("ECHO24")
            )
        )
    }

    @Test
    fun `a rename carries the name`() {
        val control = GroupControlParser.parse(
            sender = creator,
            messageType = GroupControlParser.TYPE_RENAME,
            dataHex = groupIdHex + "Familie".toByteArray(Charsets.UTF_8).toHex()
        )

        assertEquals(
            GroupControlMessage.Renamed(GroupIdentity(creator, groupIdHex), "Familie"),
            control
        )
    }

    @Test
    fun `a rename to nothing takes the name away`() {
        val control = GroupControlParser.parse(
            sender = creator,
            messageType = GroupControlParser.TYPE_RENAME,
            dataHex = groupIdHex
        )

        assertEquals(
            GroupControlMessage.Renamed(GroupIdentity(creator, groupIdHex), null),
            control
        )
    }

    @Test
    fun `a rename survives an umlaut`() {
        val control = GroupControlParser.parse(
            sender = creator,
            messageType = GroupControlParser.TYPE_RENAME,
            dataHex = groupIdHex + "Grosseltern & Enkel ☕".toByteArray(Charsets.UTF_8).toHex()
        ) as GroupControlMessage.Renamed

        assertEquals("Grosseltern & Enkel ☕", control.name)
    }

    @Test
    fun `a leave says who left, and that is the sender`() {
        // Leave is the one control message whose body really does begin with the creator, so the
        // person leaving is only knowable from the envelope.
        val control = GroupControlParser.parse(
            sender = "ECHO1001",
            messageType = GroupControlParser.TYPE_LEAVE,
            dataHex = ascii(creator) + groupIdHex
        )

        assertEquals(
            GroupControlMessage.Left(GroupIdentity(creator, groupIdHex), "ECHO1001"),
            control
        )
    }

    @Test
    fun `a leave of the wrong length is refused`() {
        assertNull(
            GroupControlParser.parse(
                sender = "ECHO1001",
                messageType = GroupControlParser.TYPE_LEAVE,
                dataHex = ascii(creator)
            )
        )
    }

    @Test
    fun `everything else is somebody sending a photograph`() {
        // Most "unknown" messages are files and images. Returning null for them is the ordinary
        // case, not an error worth reporting.
        assertNull(GroupControlParser.parse(creator, 0x17, groupIdHex + ascii("ECHO1001")))
        assertNull(GroupControlParser.parse(creator, 0x01, ""))
    }

    @Test
    fun `nonsense on the wire never becomes a person`() {
        assertNull(GroupControlParser.parse("", GroupControlParser.TYPE_SETUP, groupIdHex))
        assertNull(GroupControlParser.parse("TOO_LONG_ID", GroupControlParser.TYPE_SETUP, groupIdHex))
        assertNull(GroupControlParser.parse(creator, GroupControlParser.TYPE_SETUP, "zzzz"))
        assertNull(GroupControlParser.parse(creator, GroupControlParser.TYPE_SETUP, "0a1b2"))
    }
}
