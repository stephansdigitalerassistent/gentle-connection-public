package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.threema.ThreemaBackupGroup
import ch.heuscher.gentleconnect.data.threema.ThreemaBackupParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape a real Threema data backup actually has, taken from one (version 27) rather than from
 * what the columns were assumed to be called.
 *
 * Every case here is a mistake that was found in a live import of 7,301 rows: the conversation
 * filed under an internal row number instead of a Threema ID, group chats flattened into one
 * private conversation per member, and "X was added to the group" arriving as a line of JSON.
 * The column names and the ordering are copied from the file; the identities and the words are
 * invented.
 */
class ThreemaBackupFormatTest {

    private val contactsCsv = """
        "identity","publickey","verification","acid","firstname","lastname","nick_name","last_update","hidden","archived","identity_id"
        "TSTMBR09","c7ee1dac","SERVER_VERIFIED","","Klara","Vogel","Klara V.","1689101606786","0","0","0008473196"
        "TSTMBR05","9c6fb0d7","UNVERIFIED","","","","Nono","1763143241278","0","0","1675925400"
    """.trimIndent()

    private val groupsCsv = """
        "id","creator","groupname","created_at","last_update","members","archived","groupDesc","groupDescTimestamp","group_uid","user_state"
        "3a2f369fea391750","TSTMBR03","Die Familie","0","1788540344232","TSTMBR07;TSTMBR03;TSTMBR04","0","","","1919635298","0"
    """.trimIndent()

    @Test
    fun `a contact carries the row number its message file is named after`() {
        val contacts = ThreemaBackupParser.parseContactsCsv(contactsCsv)

        assertEquals(2, contacts.size)
        assertEquals("0008473196", contacts[0].identityId)
        assertEquals("TSTMBR09", contacts[0].identity)
        // Threema spells it nick_name; the header cleaner folds the underscore away.
        assertEquals("Klara V.", contacts[0].nickname)
        assertEquals("Klara Vogel", contacts[0].displayName)
        assertEquals("Nono", contacts[1].displayName)
    }

    @Test
    fun `a group carries both its own identity and the row number of its message file`() {
        val groups = ThreemaBackupParser.parseGroupsCsv(groupsCsv)

        assertEquals(1, groups.size)
        val group = groups.single()
        // What the app identifies a group by...
        assertEquals("3a2f369fea391750", group.id)
        assertEquals("TSTMBR03", group.creator)
        // ...and what its file is called, which is a different number entirely.
        assertEquals("1919635298", group.groupUid)
        assertEquals("Die Familie", group.name)
        assertEquals(listOf("TSTMBR07", "TSTMBR03", "TSTMBR04"), group.memberIdentities)
    }

    @Test
    fun `a group says how old its member list is and whether she is still in it`() {
        // The two columns that decide whether a restored group can be answered at all: the member
        // list is a snapshot, and without a date on it there is no telling it apart from one taken
        // years before the family last changed who is in the group.
        val group = ThreemaBackupParser.parseGroupsCsv(groupsCsv).single()

        assertEquals(1788540344232L, group.lastUpdate)
        assertEquals(1788540344232L, group.membershipKnownAt(importedAt = 1_800_000_000_000L))
        assertTrue(group.isStillAMember)
    }

    @Test
    fun `a group with no date of its own is dated by the import that read it`() {
        // created_at is 0 here as it is in her own archive. A zero would read downstream as never
        // having been told the membership, which would leave the group unanswerable for the sake
        // of a column Threema declined to fill in.
        val undated = groupsCsv.replace(""","TSTMBR03","Die Familie","0","1788540344232",""", ""","TSTMBR03","Die Familie","0","0",""")

        val group = ThreemaBackupParser.parseGroupsCsv(undated).single()

        assertEquals(0L, group.lastUpdate)
        assertEquals(1_800_000_000_000L, group.membershipKnownAt(importedAt = 1_800_000_000_000L))
    }

    @Test
    fun `a group she was removed from says so`() {
        // user_state: 0 a member, 1 removed, 2 left — Threema's GroupModel.UserState. Her own
        // identity is absent from the member list in both of the latter cases, and this is the
        // second lock on the same door.
        val kicked = groupsCsv.replace(""","1919635298","0"""", ""","1919635298","1"""")
        val left = groupsCsv.replace(""","1919635298","0"""", ""","1919635298","2"""")

        assertEquals(
            ThreemaBackupGroup.USER_STATE_KICKED,
            ThreemaBackupParser.parseGroupsCsv(kicked).single().userState
        )
        assertEquals(
            ThreemaBackupGroup.USER_STATE_LEFT,
            ThreemaBackupParser.parseGroupsCsv(left).single().userState
        )
        assertFalse(ThreemaBackupParser.parseGroupsCsv(kicked).single().isStillAMember)
        assertFalse(ThreemaBackupParser.parseGroupsCsv(left).single().isStillAMember)
    }

    @Test
    fun `an archive written before there was a user_state column counts as a member`() {
        // Threema writes 0 when it has no state to write, and an older archive without the column
        // must keep behaving the way it did before this was read at all.
        val older = groupsCsv
            .replace(""","group_uid","user_state"""", ""","group_uid"""")
            .replace(""","1919635298","0"""", ""","1919635298"""")

        assertTrue(ThreemaBackupParser.parseGroupsCsv(older).single().isStillAMember)
    }

    @Test
    fun `a private conversation belongs to the file it is in, not to a column`() {
        // message_0008473196.csv, with the columns in the order the export writes them. No
        // identity column at all in a private chat: who wrote it is the isoutbox flag.
        val csv = """
            "apiid","uid","isoutbox","isread","issaved","messagestae","posted_at","created_at","modified_at","type","body","isstatusmessage","caption","quoted_message_apiid","delivered_at","read_at","g_msg_states","display_tags","edited_at","deleted_at"
            "aa11","uid-1","0","1","1","","1764781134044","1764781134044","","TEXT","Kommst du am Sonntag?","0","","","","","","0","",""
            "bb22","uid-2","1","1","1","","1764781140000","1764781140000","","TEXT","Ja, gern.","0","","","","","","0","",""
        """.trimIndent()

        val messages = ThreemaBackupParser.parseMessagesCsv(csv, "TSTMBR09")

        assertEquals(2, messages.size)
        assertTrue(messages.all { it.partnerIdentity == "TSTMBR09" })
        assertTrue(messages.all { it.senderIdentity == null })
        assertEquals("Kommst du am Sonntag?", messages[0].text)
        assertEquals(false, messages[0].isOutgoing)
        assertEquals(true, messages[1].isOutgoing)
    }

    @Test
    fun `a group message keeps the group as the conversation and the member as the sender`() {
        // group_message_1919635298.csv. The identity column here is who wrote, and reading it as
        // the conversation is what turned one group of nine into nine private chats.
        val csv = """
            "apiid","uid","identity","isoutbox","isread","issaved","messagestae","posted_at","created_at","modified_at","type","body","isstatusmessage","caption","quoted_message_apiid","delivered_at","read_at","g_msg_states","display_tags","edited_at","deleted_at"
            "","st-1","","0","1","1","","1764781134044","1764781134044","","GROUP_STATUS","[4,{""status"":5,""identity"":""TSTMBR10""}]","1","","","","","","0","",""
            "cc33","uid-3","TSTMBR04","0","1","1","","1764781134165","1764781134165","","TEXT","Bis Sonntag!","0","","","","","","0","",""
        """.trimIndent()

        val messages = ThreemaBackupParser.parseMessagesCsv(csv, "3a2f369fea391750")

        // The status row is gone, not imported as a message whose text is JSON.
        assertEquals(1, messages.size)
        val message = messages.single()
        assertEquals("3a2f369fea391750", message.partnerIdentity)
        assertEquals("TSTMBR04", message.senderIdentity)
        assertEquals("Bis Sonntag!", message.text)
    }

    @Test
    fun `status messages are dropped wherever they appear`() {
        val csv = """
            "uid","isoutbox","posted_at","type","body","isstatusmessage"
            "s1","0","1764781134044","VOIP_STATUS","[1,{}]","1"
            "s2","0","1764781134045","GROUP_CALL_STATUS","[2,{}]","1"
            "m1","0","1764781134046","TEXT","Hallo","0"
        """.trimIndent()

        val messages = ThreemaBackupParser.parseMessagesCsv(csv, "TSTMBR09")

        assertEquals(1, messages.size)
        assertEquals("Hallo", messages.single().text)
    }

    @Test
    fun `a telephone call is not a message, whatever the status column says`() {
        // Copied from a real export: VOIP_STATUS rows carry isstatusmessage = 0, so the column
        // alone let every call through, and her father's conversation was half machinery.
        val csv = """
            "apiid","uid","isoutbox","isread","issaved","messagestae","posted_at","created_at","modified_at","type","body","isstatusmessage","caption"
            "","e8bf688e","0","1","1","","1769714410309","1769714410309","","VOIP_STATUS","[1,{""status"":1,""callId"":2346486070}]","0",""
            "","2b5c5b52","1","1","1","","1788526647952","1788526647952","","VOIP_STATUS","[1,{""status"":4,""callId"":582711611}]","0",""
            "aa11","uid-1","0","1","1","READ","1788526923163","1788526923163","","TEXT","Rufst du mich an?","0",""
        """.trimIndent()

        val messages = ThreemaBackupParser.parseMessagesCsv(csv, "TSTMBR04")

        assertEquals(1, messages.size)
        assertEquals("Rufst du mich an?", messages.single().text)
    }

    @Test
    fun `a file's blob ids never reach her screen`() {
        // A FILE row's body is ["<blob id>","<key>",…]. Shown raw it is forty characters of hex
        // where a sentence should be; the attachment itself is what carries the meaning.
        val csv = """
            "uid","isoutbox","posted_at","type","body","isstatusmessage","caption"
            "f1","1","1769762664463","FILE","[""352914899def0c7f"",""0c2bb771ac322edb""]","0",""
            "f2","1","1769762664999","SOMETHINGNEW","[""1"",{""a"":2}]","0",""
        """.trimIndent()

        val messages = ThreemaBackupParser.parseMessagesCsv(csv, "TSTMBR04")

        assertEquals(2, messages.size)
        assertEquals("[Datei]", messages[0].text)
        assertEquals("[Anhang]", messages[1].text)
    }

    @Test
    fun `a caption survives a type nobody here has seen before`() {
        val csv = """
            "uid","isoutbox","posted_at","type","body","isstatusmessage","caption"
            "c1","0","1769762664463","SOMETHINGNEW","[""machinery""]","0","Schau mal, der Garten"
        """.trimIndent()

        assertEquals("Schau mal, der Garten", ThreemaBackupParser.parseMessagesCsv(csv, "X").single().text)
    }

    @Test
    fun `a voice message reads as one rather than as an unnamed attachment`() {
        val csv = """
            "uid","isoutbox","posted_at","type","body","isstatusmessage"
            "v1","0","1764781134046","VOICEMESSAGE","","0"
        """.trimIndent()

        val message = ThreemaBackupParser.parseMessagesCsv(csv, "TSTMBR09").single()

        assertEquals("[Sprachnachricht]", message.text)
    }

    @Test
    fun `the ids an import may overwrite include the rows it refuses to import`() {
        // The rule that lets a fixed parser reach a conversation it has already imported. The
        // telephone call is not restored as a message — and it is named here, so the import
        // deletes the row an older parser left behind instead of leaving it on her screen.
        val csv = """
            "apiid","uid","isoutbox","posted_at","type","body","isstatusmessage"
            "","e8bf688e","0","1769714410309","VOIP_STATUS","[1,{""status"":1}]","0"
            "aa11","uid-1","0","1788526923163","TEXT","Rufst du mich an?","0"
        """.trimIndent()

        assertEquals(
            listOf("e8bf688e", "uid-1"),
            ThreemaBackupParser.parseMessageIds(csv, "TSTMBR04")
        )
        // And they are the same ids the import goes on to write, or it would delete a message and
        // put a second copy of it back.
        assertEquals(
            listOf("uid-1"),
            ThreemaBackupParser.parseMessagesCsv(csv, "TSTMBR04").map { it.id }
        )
    }

    @Test
    fun `a row with no id of its own is still named the same way twice`() {
        val csv = """
            "isoutbox","posted_at","type","body","isstatusmessage"
            "0","1769714410309","TEXT","Hallo","0"
        """.trimIndent()

        assertEquals(ThreemaBackupParser.parseMessageIds(csv, "TSTMBR09"), listOf("TSTMBR09_1"))
        assertEquals(
            ThreemaBackupParser.parseMessagesCsv(csv, "TSTMBR09").map { it.id },
            ThreemaBackupParser.parseMessageIds(csv, "TSTMBR09")
        )
    }

    @Test
    fun `a file with no groups and no contacts parses to nothing rather than throwing`() {
        assertEquals(emptyList<Any>(), ThreemaBackupParser.parseGroupsCsv(""))
        assertEquals(emptyList<Any>(), ThreemaBackupParser.parseGroupsCsv("\"id\",\"creator\"\n"))
        assertNull(ThreemaBackupParser.parseContactsCsv(contactsCsv).firstOrNull { it.identity == "NOBODY" })
    }
}
