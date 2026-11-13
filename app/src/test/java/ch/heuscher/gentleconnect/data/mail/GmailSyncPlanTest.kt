package ch.heuscher.gentleconnect.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GmailSyncPlanTest {

    private fun uid(id: String) = GmailIds.toUid(id)!!

    @Test
    fun `gmail ids round trip through the uid column, top bit included`() {
        for (id in listOf("18c2f0a1b2c3d4e5", "ffffffffffffffff", "1")) {
            assertEquals(id, GmailIds.fromUid(uid(id)))
        }
        assertNull(GmailIds.toUid("not-hex"))
    }

    @Test
    fun `first sync fetches the newest letters up to the cap`() {
        val plan = GmailSyncPlanner.plan(
            inboxIds = listOf("5", "4", "3", "2", "1"),
            unreadIds = setOf("5"),
            storedUids = emptySet(),
            fetchCap = 3
        )
        assertEquals(listOf("5", "4", "3"), plan.toFetch)
        assertEquals(emptySet<Long>(), plan.seenUids)
    }

    @Test
    fun `later syncs fetch only what is not stored yet`() {
        val plan = GmailSyncPlanner.plan(
            inboxIds = listOf("7", "6", "5", "4"),
            unreadIds = setOf("7", "6", "5"),
            storedUids = setOf(uid("5"), uid("4")),
            fetchCap = 50
        )
        assertEquals(listOf("7", "6"), plan.toFetch)
    }

    @Test
    fun `stored letters no longer unread are reported seen, and only those`() {
        val plan = GmailSyncPlanner.plan(
            inboxIds = listOf("9", "8", "7"),
            unreadIds = setOf("9", "7"),
            // "3" has left the inbox window: Gmail said nothing about it, so it stays as it is.
            storedUids = setOf(uid("8"), uid("7"), uid("3")),
            fetchCap = 50
        )
        assertEquals(listOf("9"), plan.toFetch)
        assertEquals(setOf(uid("8")), plan.seenUids)
    }

    @Test
    fun `sender name prefers the display name and falls back to the address`() {
        assertEquals("Anna Muster", MimeMailMapper.extractSenderName("\"Anna Muster\" <anna@example.com>", "anna@example.com"))
        assertEquals("anna@example.com", MimeMailMapper.extractSenderName("<anna@example.com>", "anna@example.com"))
        assertEquals("", MimeMailMapper.extractSenderName("", ""))
    }
}
