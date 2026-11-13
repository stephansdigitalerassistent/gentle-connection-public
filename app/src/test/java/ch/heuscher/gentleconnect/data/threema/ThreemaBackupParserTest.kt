package ch.heuscher.gentleconnect.data.threema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreemaBackupParserTest {

    @Test
    fun parseContactsCsv_wellFormed_parsesCorrectly() {
        val csv = """
            identity,publickey,firstname,lastname,nickname,verification
            ECHO1001,0102030405060708,Anna,Müller,Anna M.,verified
            TEST1234,090a0b0c0d0e0f00,Bob,Smith,Bobby,unverified
        """.trimIndent()

        val contacts = ThreemaBackupParser.parseContactsCsv(csv)

        assertEquals(2, contacts.size)
        assertEquals("ECHO1001", contacts[0].identity)
        assertEquals("Anna", contacts[0].firstName)
        assertEquals("Müller", contacts[0].lastName)
        assertEquals("Anna Müller", contacts[0].displayName)

        assertEquals("TEST1234", contacts[1].identity)
        assertEquals("Bob Smith", contacts[1].displayName)
    }

    @Test
    fun parseMessagesCsv_wellFormed_parsesCorrectly() {
        val csv = """
            uid,apiid,isoutbox,identity,body,type,posted_at,isread
            msg101,api101,0,ECHO1001,"Hallo Oma!",1,1689000000000,1
            msg102,api102,1,ECHO1001,"Hallo Anna!",1,1689000060000,1
        """.trimIndent()

        val messages = ThreemaBackupParser.parseMessagesCsv(csv, "ECHO1001")

        assertEquals(2, messages.size)
        assertEquals("msg101", messages[0].id)
        assertEquals("ECHO1001", messages[0].partnerIdentity)
        assertEquals("Hallo Oma!", messages[0].text)
        assertFalse(messages[0].isOutgoing)
        assertTrue(messages[0].isRead)

        assertEquals("msg102", messages[1].id)
        assertTrue(messages[1].isOutgoing)
        assertTrue(messages[1].isRead)
    }

    @Test
    fun parseCsv_quotedFieldsWithCommasAndNewlines_survive() {
        val csv = """
            identity,publickey,firstname,lastname,nickname,verification
            ECHO1001,key123,"Anna, Maria","Müller
            Meier",AnnaM,verified
        """.trimIndent()

        val contacts = ThreemaBackupParser.parseContactsCsv(csv)

        assertEquals(1, contacts.size)
        assertEquals("Anna, Maria", contacts[0].firstName)
        assertEquals("Müller\nMeier", contacts[0].lastName)
        assertEquals("Anna, Maria Müller\nMeier", contacts[0].displayName)
    }

    @Test
    fun parseCsv_malformedRowInMiddle_surroundingRowsStillParse() {
        val csv = """
            identity,publickey,firstname,lastname,nickname,verification
            ECHO1001,key1,,Müller,AnnaM,verified
            ,,,,,
            TEST1234,key2,Bob,Smith,Bobby,unverified
        """.trimIndent()

        val contacts = ThreemaBackupParser.parseContactsCsv(csv)

        assertEquals(2, contacts.size)
        assertEquals("ECHO1001", contacts[0].identity)
        assertEquals("TEST1234", contacts[1].identity)
    }

    @Test
    fun parseMessagesCsv_unknownMessageType_becomesPlaceholder() {
        val csv = """
            uid,isoutbox,body,type,posted_at
            m1,0,"",image,1689000000000
            m2,0,"",voice,1689000000000
            m3,0,"",video,1689000000000
            m4,0,"",location,1689000000000
            m5,0,"",custom_unknown_type,1689000000000
        """.trimIndent()

        val messages = ThreemaBackupParser.parseMessagesCsv(csv, "ECHO1001")

        assertEquals(5, messages.size)
        assertEquals("[Foto]", messages[0].text)
        assertEquals("[Sprachnachricht]", messages[1].text)
        assertEquals("[Video]", messages[2].text)
        assertEquals("[Standort]", messages[3].text)
        assertEquals("[Anhang]", messages[4].text)
    }

    @Test
    fun parseCsv_emptyFile_yieldsEmptyList() {
        val emptyContacts = ThreemaBackupParser.parseContactsCsv("")
        val emptyMessages = ThreemaBackupParser.parseMessagesCsv("", "ECHO1001")

        assertTrue(emptyContacts.isEmpty())
        assertTrue(emptyMessages.isEmpty())
    }
}
