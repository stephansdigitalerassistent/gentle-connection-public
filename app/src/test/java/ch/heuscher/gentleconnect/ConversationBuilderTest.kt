package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.ConversationBuilder
import ch.heuscher.gentleconnect.data.ConversationItem
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.MessageDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBuilderTest {

    private val day = 24 * 60 * 60 * 1000L

    /** Whole days since the epoch — a stand-in for the phone's own midnight. */
    private val dayOf: (Long) -> Long = { it / day }

    private fun message(
        id: String,
        text: String,
        at: Long,
        from: String = "Sabine",
        direction: MessageDirection = MessageDirection.INCOMING,
        quotedId: String? = null,
        quotedSenderName: String? = null,
        quotedText: String? = null,
        quotedIsHers: Boolean = false,
        wireMessageId: String? = null
    ) = Message(
        id = id,
        senderName = from,
        senderId = "ECHO1001",
        text = text,
        timestamp = at,
        sourcePackage = "",
        channel = MessageChannel.ON_DEVICE,
        direction = direction,
        quotedId = quotedId,
        quotedSenderName = quotedSenderName,
        quotedText = quotedText,
        quotedIsHers = quotedIsHers,
        wireMessageId = wireMessageId
    )

    private fun texts(items: List<ConversationItem>) =
        items.filterIsInstance<ConversationItem.Entry>().map { it.message.text }

    @Test
    fun `a conversation reads downwards, oldest first`() {
        // The opposite of the flat message list, and the right way round for a back-and-forth.
        val items = ConversationBuilder.build(
            listOf(
                message("c", "and bring the photos", at = 3 * day + 3),
                message("a", "Are you coming on Sunday?", at = 3 * day + 1),
                message("b", "Yes, gladly", at = 3 * day + 2, direction = MessageDirection.OUTGOING)
            ),
            dayOf
        )

        assertEquals(
            listOf("Are you coming on Sunday?", "Yes, gladly", "and bring the photos"),
            texts(items)
        )
    }

    @Test
    fun `each day is announced once, above the first message of that day`() {
        val items = ConversationBuilder.build(
            listOf(
                message("a", "Monday morning", at = 1 * day + 1),
                message("b", "Monday evening", at = 1 * day + 2),
                message("c", "Tuesday", at = 2 * day + 1)
            ),
            dayOf
        )

        assertEquals(5, items.size)
        assertTrue(items[0] is ConversationItem.Day)
        assertTrue(items[1] is ConversationItem.Entry)
        assertTrue(items[2] is ConversationItem.Entry)
        assertTrue(items[3] is ConversationItem.Day)
        assertTrue(items[4] is ConversationItem.Entry)
    }

    @Test
    fun `the same message arriving twice is one message`() {
        // The server-side bridge and her SMS inbox both carry it, under ids numbered
        // independently. Two identical bubbles a second apart read as the sender having written
        // twice, and she has no way to tell that they did not.
        val items = ConversationBuilder.build(
            listOf(
                message("bridge_1", "Are you coming on Sunday?", at = 3 * day + 1000),
                message("sms_9", "Are you coming on Sunday?", at = 3 * day + 2000)
            ),
            dayOf
        )

        assertEquals(listOf("Are you coming on Sunday?"), texts(items))
    }

    @Test
    fun `a burst of captionless photos from one sender keeps every photo`() {
        // Nine photos in three seconds, all `[Foto]`: one route, nine ids, nine messages. Text
        // matching folded them into the first and she saw one photo of nine.
        val burst = (1..9).map { n ->
            message("threema_$n", "[Foto]", at = 3 * day + n * 300L, from = "Julia")
        }

        val items = ConversationBuilder.build(burst, dayOf)

        assertEquals(List(9) { "[Foto]" }, texts(items))
    }

    @Test
    fun `the same message id twice is one message`() {
        val items = ConversationBuilder.build(
            listOf(
                message("threema_7", "Hello", at = 3 * day + 1),
                message("threema_7", "Hello", at = 3 * day + 1)
            ),
            dayOf
        )

        assertEquals(listOf("Hello"), texts(items))
    }

    @Test
    fun `somebody deliberately writing the same thing twice keeps both`() {
        // The far side of the dedup rule, and the reason it has a window at all. Her sister
        // writing "hello?" again twenty minutes later is not a repeat to be folded away — it is
        // somebody wondering why she has not answered.
        val items = ConversationBuilder.build(
            listOf(
                message("a", "hello?", at = 3 * day),
                message("b", "hello?", at = 3 * day + 20 * 60 * 1000)
            ),
            dayOf
        )

        assertEquals(listOf("hello?", "hello?"), texts(items))
    }

    @Test
    fun `her own message is never folded into theirs`() {
        // She answered a 👍 with a 👍. Two directions is two messages, however alike they look.
        val items = ConversationBuilder.build(
            listOf(
                message("a", "👍", at = 3 * day),
                message("b", "👍", at = 3 * day + 1000, direction = MessageDirection.OUTGOING)
            ),
            dayOf
        )

        assertEquals(2, texts(items).size)
    }

    @Test
    fun `an empty conversation has no day separator hanging over it`() {
        assertEquals(emptyList<ConversationItem>(), ConversationBuilder.build(emptyList(), dayOf))
    }

    @Test
    fun `a quote referencing another message in the conversation resolves sender and text`() {
        val q = message("msg_1", "Are you coming on Sunday?", at = 1 * day, from = "Anna")
        val a = message("msg_2", "Yes, gladly!", at = 1 * day + 1000, from = "Sabine", quotedId = "msg_1")

        val items = ConversationBuilder.build(listOf(q, a), dayOf)
        val entries = items.filterIsInstance<ConversationItem.Entry>()

        assertEquals(2, entries.size)
        val resolved = entries[1].message
        assertEquals("Anna", resolved.quotedSenderName)
        assertEquals("Are you coming on Sunday?", resolved.quotedText)
        assertFalse(resolved.quotedIsHers)
    }

    @Test
    fun `a quote of her own message resolves with null quotedSenderName and quotedIsHers true`() {
        val q = message("out_1", "Shall we eat?", at = 1 * day, from = "Anna", direction = MessageDirection.OUTGOING)
        val a = message("in_2", "Yes!", at = 1 * day + 1000, from = "Anna", quotedId = "out_1")

        val items = ConversationBuilder.build(listOf(q, a), dayOf)
        val entries = items.filterIsInstance<ConversationItem.Entry>()

        assertEquals(2, entries.size)
        val resolved = entries[1].message
        assertNull(resolved.quotedSenderName)
        assertEquals("Shall we eat?", resolved.quotedText)
        assertTrue(resolved.quotedIsHers)
    }

    @Test
    fun `a quote of a message not in the conversation leaves quoted fields null`() {
        val a = message("in_2", "Yes!", at = 1 * day, from = "Anna", quotedId = "threema_old_missing_id")

        val items = ConversationBuilder.build(listOf(a), dayOf)
        val entries = items.filterIsInstance<ConversationItem.Entry>()

        assertEquals(1, entries.size)
        val resolved = entries[0].message
        assertNull(resolved.quotedSenderName)
        assertNull(resolved.quotedText)
        assertFalse(resolved.quotedIsHers)
    }

    @Test
    fun `carried quote fields are kept when the message is not in the store`() {
        val a = message(
            "in_2",
            "Yes!",
            at = 1 * day,
            from = "Anna",
            quotedId = "whatsapp_old_msg",
            quotedSenderName = "Anna",
            quotedText = "Carried quote text"
        )

        val items = ConversationBuilder.build(listOf(a), dayOf)
        val entries = items.filterIsInstance<ConversationItem.Entry>()

        assertEquals(1, entries.size)
        val resolved = entries[0].message
        assertEquals("Anna", resolved.quotedSenderName)
        assertEquals("Carried quote text", resolved.quotedText)
        assertFalse(resolved.quotedIsHers)
    }

    @Test
    fun `carried quote with missing sender name keeps null sender and false quotedIsHers`() {
        val a = message(
            "in_2",
            "Yes!",
            at = 1 * day,
            from = "Anna",
            quotedId = "whatsapp_missing",
            quotedSenderName = null,
            quotedText = "Carried quote text",
            quotedIsHers = false
        )

        val items = ConversationBuilder.build(listOf(a), dayOf)
        val entries = items.filterIsInstance<ConversationItem.Entry>()

        assertEquals(1, entries.size)
        val resolved = entries[0].message
        assertNull(resolved.quotedSenderName)
        assertEquals("Carried quote text", resolved.quotedText)
        assertFalse(resolved.quotedIsHers)
    }

    @Test
    fun `the message in the store wins over carried quote fields`() {
        val q = message("whatsapp_123", "Stored text", at = 1 * day, from = "Anna (renamed)")
        val a = message(
            "in_2",
            "Yes!",
            at = 1 * day + 1000,
            from = "Anna",
            quotedId = "whatsapp_123",
            quotedSenderName = "41791234567",
            quotedText = "Old carried text"
        )

        val items = ConversationBuilder.build(listOf(q, a), dayOf)
        val entries = items.filterIsInstance<ConversationItem.Entry>()

        assertEquals(2, entries.size)
        val resolved = entries[1].message
        assertEquals("Anna (renamed)", resolved.quotedSenderName)
        assertEquals("Stored text", resolved.quotedText)
        assertFalse(resolved.quotedIsHers)
    }

    @Test
    fun `a quote matches by wireMessageId on her own sent message`() {
        val q = message(
            "out_local_id",
            "Are you there?",
            at = 1 * day,
            from = "Anna",
            direction = MessageDirection.OUTGOING,
            wireMessageId = "a3f19c04b7e25d81"
        )
        val a = message(
            "in_2",
            "Yes!",
            at = 1 * day + 1000,
            from = "Anna",
            quotedId = "threema_a3f19c04b7e25d81"
        )

        val items = ConversationBuilder.build(listOf(q, a), dayOf)
        val entries = items.filterIsInstance<ConversationItem.Entry>()

        assertEquals(2, entries.size)
        val resolved = entries[1].message
        assertNull(resolved.quotedSenderName)
        assertEquals("Are you there?", resolved.quotedText)
        assertTrue(resolved.quotedIsHers)
    }

    @Test
    fun `a message quoting itself does not resolve against itself`() {
        val selfQuote = message(
            "msg_1",
            "I quote myself",
            at = 1 * day,
            from = "Sabine",
            quotedId = "msg_1"
        )

        val items = ConversationBuilder.build(listOf(selfQuote), dayOf)
        val entries = items.filterIsInstance<ConversationItem.Entry>()

        assertEquals(1, entries.size)
        val resolved = entries[0].message
        assertNull(resolved.quotedSenderName)
        assertNull(resolved.quotedText)
        assertFalse(resolved.quotedIsHers)
    }

    @Test
    fun `a message quoting its own wireMessageId does not resolve against itself`() {
        val selfQuote = message(
            "threema_a3f19c04b7e25d81",
            "I quote my wire id",
            at = 1 * day,
            from = "Sabine",
            wireMessageId = "a3f19c04b7e25d81",
            quotedId = "threema_a3f19c04b7e25d81"
        )

        val items = ConversationBuilder.build(listOf(selfQuote), dayOf)
        val entries = items.filterIsInstance<ConversationItem.Entry>()

        assertEquals(1, entries.size)
        val resolved = entries[0].message
        assertNull(resolved.quotedSenderName)
        assertNull(resolved.quotedText)
        assertFalse(resolved.quotedIsHers)
    }
}
