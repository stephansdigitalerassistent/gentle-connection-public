package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.db.CaregiverEventEntity
import ch.heuscher.gentleconnect.data.db.MailCountRow
import ch.heuscher.gentleconnect.data.db.MessageCountRow
import ch.heuscher.gentleconnect.model.GroupIdentity
import ch.heuscher.gentleconnect.util.CaregiverStatsExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaregiverStatsExportTest {

    private fun event(kind: String, subject: String?) =
        CaregiverEventEntity(kind = kind, at = 5L, subject = subject, count = 1, total = 1, occurrences = 2)

    @Test
    fun `messages carry group and status flags but no names`() {
        val json = CaregiverStatsExport.toJson(
            now = 100L,
            since = 0L,
            messages = listOf(
                MessageCountRow(1L, "INCOMING", "THREEMA", null),
                MessageCountRow(2L, "INCOMING", "THREEMA", "GROUPID1"),
                MessageCountRow(3L, "INCOMING", "WHATSAPP", "status@broadcast")
            ),
            events = emptyList()
        )
        val messages = json.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertFalse(messages.getJSONObject(0).getBoolean("group"))
        assertTrue(messages.getJSONObject(1).getBoolean("group"))
        assertFalse(messages.getJSONObject(2).getBoolean("group"))
        assertTrue(messages.getJSONObject(2).getBoolean("status"))
        assertFalse(json.toString().contains("GROUPID1"))
    }

    @Test
    fun `group messages are tagged per group and the tag carries the name`() {
        val family = GroupIdentity("AAAAAAAA", "0102030405060708")
        val json = CaregiverStatsExport.toJson(
            now = 100L,
            since = 0L,
            messages = listOf(
                MessageCountRow(1L, "INCOMING", "THREEMA", "0102030405060708", "AAAAAAAA"),
                MessageCountRow(2L, "OUTGOING", "THREEMA", "ffffffffffffffff", "BBBBBBBB"),
                MessageCountRow(3L, "INCOMING", "THREEMA", "0102030405060708", "aaaaaaaa"),
                MessageCountRow(4L, "INCOMING", "THREEMA", null),
                MessageCountRow(5L, "INCOMING", "WHATSAPP", "status@broadcast")
            ),
            events = emptyList(),
            groupNames = mapOf(family to "Family")
        )
        val messages = json.getJSONArray("messages")
        assertEquals("g1", messages.getJSONObject(0).getString("g"))
        assertEquals("g2", messages.getJSONObject(1).getString("g"))
        assertEquals("g1", messages.getJSONObject(2).getString("g"))
        assertFalse(messages.getJSONObject(3).has("g"))
        assertFalse(messages.getJSONObject(4).has("g"))

        val groups = json.getJSONObject("groups")
        assertEquals(2, groups.length())
        assertEquals("Family", groups.getJSONObject("g1").getString("name"))
        assertEquals("THREEMA", groups.getJSONObject("g1").getString("transport"))
        // A group nobody has named or described still counts apart; it just has no name.
        assertFalse(groups.getJSONObject("g2").has("name"))
        // Neither the group's id nor its creator leaves the phone.
        assertFalse(json.toString().contains("0102030405060708"))
        assertFalse(json.toString().contains("AAAAAAAA"))
    }

    @Test
    fun `event subject kept only for kinds whose subject is not a person`() {
        val json = CaregiverStatsExport.toJson(
            now = 100L,
            since = 0L,
            messages = emptyList(),
            events = listOf(
                event("RESTLESSNESS_DETECTED", "schnelles Tippen"),
                event("SEND_FAILED", "Anna Muster")
            )
        )
        val events = json.getJSONArray("caregiverEvents")
        assertEquals("schnelles Tippen", events.getJSONObject(0).getString("subject"))
        assertFalse(events.getJSONObject(1).has("subject"))
        assertEquals(2, events.getJSONObject(1).getInt("occurrences"))
        assertFalse(json.toString().contains("Anna"))
    }

    @Test
    fun `mails carry only time and whether the sender is known`() {
        val json = CaregiverStatsExport.toJson(
            now = 100L,
            since = 0L,
            messages = emptyList(),
            events = emptyList(),
            mails = listOf(MailCountRow(7L, true), MailCountRow(8L, false))
        )
        val mails = json.getJSONArray("mails")
        assertEquals(2, mails.length())
        assertEquals(7L, mails.getJSONObject(0).getLong("t"))
        assertTrue(mails.getJSONObject(0).getBoolean("known"))
        assertFalse(mails.getJSONObject(1).getBoolean("known"))
        assertEquals(setOf("t", "known"), mails.getJSONObject(1).keys().asSequence().toSet())
    }
}
