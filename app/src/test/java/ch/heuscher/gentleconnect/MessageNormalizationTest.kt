package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.LocalMessageStore
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.Reactions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageNormalizationTest {

    private lateinit var store: LocalMessageStore

    @Before
    fun setUp() {
        store = LocalMessageStore()
        store.clear()
    }

    @Test
    fun `addOrUpdateMessage adds new message when store is empty`() {
        val msg = Message(
            id = "m1",
            senderName = "Lisa",
            text = "Hallo",
            timestamp = 1000L,
            sourcePackage = "com.whatsapp"
        )
        store.addOrUpdateMessage(msg)

        val list = store.messages.value
        assertEquals(1, list.size)
        assertEquals("m1", list[0].id)
    }

    @Test
    fun `addOrUpdateMessage deduplicates duplicate message within 30 seconds`() {
        val now = 100_000L
        val msg1 = Message(
            id = "m1",
            senderName = "Lisa",
            text = "Hallo Mama",
            timestamp = now,
            sourcePackage = "com.whatsapp"
        )
        store.addOrUpdateMessage(msg1)

        // Duplicate incoming notification 5s later
        val msg2 = Message(
            id = "m2",
            senderName = "Lisa",
            text = "Hallo Mama",
            timestamp = now + 5000L,
            sourcePackage = "com.whatsapp"
        )
        store.addOrUpdateMessage(msg2)

        val list = store.messages.value
        assertEquals(1, list.size)
        assertEquals("m1", list[0].id)
    }

    @Test
    fun `her reply is not swallowed as a duplicate of what it answers`() {
        val now = 100_000L
        val question = Message(
            id = "m1",
            senderName = "Lisa",
            text = "Ja gerne",
            timestamp = now,
            sourcePackage = "com.google.android.apps.messaging"
        )
        // The same words back, seconds later, to the same person. Everything the duplicate rule
        // looks at matches — and it is the one message in the conversation that proves she
        // answered. Before her sent messages were imported at all this could not happen; now that
        // they are, the direction is what has to keep them apart.
        val answer = Message(
            id = "m2",
            senderName = "Lisa",
            text = "Ja gerne",
            timestamp = now + 8_000L,
            sourcePackage = "com.google.android.apps.messaging",
            direction = MessageDirection.OUTGOING,
            isRead = true
        )

        store.addOrUpdateMessages(listOf(question, answer))

        val list = store.messages.value
        assertEquals(2, list.size)
        assertTrue(list.any { it.direction == MessageDirection.OUTGOING })
        assertTrue(list.any { it.direction == MessageDirection.INCOMING })
    }

    @Test
    fun `a batch folds duplicates inside itself as well`() {
        val now = 100_000L
        val once = Message(
            id = "m1",
            senderName = "Die Apotheke",
            text = "Ihre Bestellung ist bereit",
            timestamp = now,
            sourcePackage = "com.google.android.apps.messaging"
        )
        val again = once.copy(id = "m2", timestamp = now + 4_000L)

        store.addOrUpdateMessages(listOf(once, again))

        assertEquals(1, store.messages.value.size)
    }

    @Test
    fun `addOrUpdateMessage adds separate message if content differs`() {
        val now = 100_000L
        val msg1 = Message(
            id = "m1",
            senderName = "Lisa",
            text = "Erste Nachricht",
            timestamp = now,
            sourcePackage = "com.whatsapp"
        )
        val msg2 = Message(
            id = "m2",
            senderName = "Lisa",
            text = "Zweite Nachricht",
            timestamp = now + 2000L,
            sourcePackage = "com.whatsapp"
        )

        store.addOrUpdateMessage(msg1)
        store.addOrUpdateMessage(msg2)

        val list = store.messages.value
        assertEquals(2, list.size)
    }

    @Test
    fun `a message she has read does not come back unread`() {
        // The SMS import runs on every launch and on every text that arrives, and it carries the
        // telephone's own read flag — which this app cannot always write to. So the copy that
        // arrives last used to win, a message she had opened came back unread minutes later, and
        // the mark on the Message button returned by itself with nothing she could do about it.
        val unread = Message(
            id = "sms_88",
            senderName = "Claire-Lise",
            text = "Bis morgen",
            timestamp = 100_000L,
            sourcePackage = "com.google.android.apps.messaging",
            isRead = false
        )
        store.addOrUpdateMessage(unread)
        store.markAsRead("sms_88")

        // The same message, read again from the phone, still flagged unread there.
        store.addOrUpdateMessage(unread)

        assertEquals(1, store.messages.value.size)
        assertTrue(store.messages.value.single().isRead)
    }

    @Test
    fun `addReaction marks message read and stores reaction emoji`() {
        val msg = Message(
            id = "m1",
            senderName = "Markus",
            text = "Schönen Tag!",
            timestamp = 1000L,
            sourcePackage = "org.threema.app",
            isRead = false
        )
        store.addOrUpdateMessage(msg)

        store.addReaction("m1", "❤️")

        val updated = store.messages.value.find { it.id == "m1" }
        assertNotNull(updated)
        assertTrue(updated!!.isRead)
        // Read back through [Reactions] rather than off the column: a mark now carries whose it
        // is, and on this path it is always hers.
        assertEquals("❤️", Reactions.ownOf(updated.reaction))
        assertEquals(listOf("❤️"), Reactions.of(updated.reaction))
    }

    /**
     * She reacts twice to the same message — a heart when she reads it, a thumb when she has done
     * the thing it asked. The second used to erase the first, and with it the only evidence on
     * her screen that she had answered at all.
     */
    @Test
    fun `a second reaction joins the first rather than replacing it`() {
        val msg = Message(
            id = "m1",
            senderName = "Markus",
            text = "Schönen Tag!",
            timestamp = 1000L,
            sourcePackage = "org.threema.app",
            isRead = false
        )
        store.addOrUpdateMessage(msg)

        store.addReaction("m1", "❤️")
        store.addReaction("m1", "👍")

        val updated = store.messages.value.single { it.id == "m1" }
        assertEquals(listOf("❤️", "👍"), Reactions.of(updated.reaction))
    }
}
