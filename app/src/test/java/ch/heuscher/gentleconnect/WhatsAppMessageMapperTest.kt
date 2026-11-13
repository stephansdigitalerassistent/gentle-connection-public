package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.whatsapp.CallState
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppEvent
import ch.heuscher.gentleconnect.data.whatsapp.WhatsAppMessageMapper
import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.Contact
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.MessageDirection
import ch.heuscher.gentleconnect.model.SendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppMessageMapperTest {

    private val anna = Contact(id = "17", name = "Anna", phoneNumber = "079 123 45 67")
    private val contacts = listOf(anna)

    private fun incoming(
        messageId: String = "3EB0",
        sender: String = "41791234567@s.whatsapp.net",
        chat: String = "41791234567@s.whatsapp.net",
        isGroup: Boolean = false,
        pushName: String = "Mama",
        text: String = "Bis morgen",
        timestamp: Long = 1_786_200_000_000L,
        outgoing: Boolean = false,
        quotedId: String? = null,
        quotedSender: String? = null,
        quotedText: String? = null
    ) = WhatsAppEvent.Incoming(
        messageId = messageId,
        senderJid = sender,
        chatJid = chat,
        isGroup = isGroup,
        pushName = pushName,
        text = text,
        timestamp = timestamp,
        outgoing = outgoing,
        quotedId = quotedId,
        quotedSender = quotedSender,
        quotedText = quotedText
    )

    @Test
    fun `a message from somebody in her address book arrives under the name she knows`() {
        val message = WhatsAppMessageMapper.fromBridge(incoming(), contacts)

        assertNotNull(message)
        requireNotNull(message)
        // "Mama" is what her daughter calls herself on WhatsApp. It is not what her daughter is
        // called on her mother's phone, and her mother's phone wins.
        assertEquals("Anna", message.senderName)
        assertEquals("17", message.contactId)
        assertEquals("whatsapp_3EB0", message.id)
        assertEquals(BridgeTransport.WHATSAPP, message.transport)
        assertEquals(MessageChannel.ON_DEVICE, message.channel)
        assertEquals("Bis morgen", message.text)
    }

    @Test
    fun `the address is the number in the form everything else files by`() {
        val message = WhatsAppMessageMapper.fromBridge(incoming(), contacts)!!

        // Conversation ids, contact links and the merge with SMS all key on a normalised
        // telephone number. Without the +, a WhatsApp message from her daughter and an SMS from
        // her daughter become two threads.
        assertEquals("+41791234567", message.senderAddress)
        assertEquals("41791234567", message.senderId)
    }

    @Test
    fun `a stranger keeps the name they chose, and is not pretended to be a contact`() {
        val message = WhatsAppMessageMapper.fromBridge(
            incoming(sender = "493012345678@s.whatsapp.net", pushName = "Praxis Dr. Meier"),
            contacts
        )!!

        assertEquals("Praxis Dr. Meier", message.senderName)
        assertNull(message.contactId)
    }

    @Test
    fun `somebody with no name at all is shown as their number rather than as nobody`() {
        val message = WhatsAppMessageMapper.fromBridge(
            incoming(sender = "493012345678@s.whatsapp.net", pushName = "   "),
            contacts
        )!!

        assertEquals("493012345678", message.senderName)
    }

    @Test
    fun `what she writes in the native WhatsApp app is on her screen too`() {
        // It used to be dropped, along with the echo of what this app sent — and a message
        // written in the native app is echoed and nothing else, so her half of those
        // conversations simply did not exist. The duplicate this guarded against is caught by
        // its id instead, in ArrivalPipeline.isOwnEcho, which is the only layer that can know.
        val message = WhatsAppMessageMapper.fromBridge(incoming(outgoing = true), contacts)!!

        assertEquals(MessageDirection.OUTGOING, message.direction)
        // Filed under the person she wrote to, which is who the conversation is with.
        assertEquals("Anna", message.senderName)
        assertEquals("17", message.contactId)
        // Nothing to announce: she wrote it, so it is neither unread nor still on its way.
        assertTrue(message.isRead)
        assertEquals(SendState.SENT, message.sendState)
        // The name WhatsApp knows it by, which is what tells this apart from the app's own echo.
        assertEquals("3EB0", message.wireMessageId)
    }

    @Test
    fun `a message of hers is not filed under her own name`() {
        // The push name on a message of hers is hers. Taking it would put "Mama" on a message she
        // sent to a number her address book has never heard of — a conversation with herself.
        val message = WhatsAppMessageMapper.fromBridge(
            incoming(
                sender = "493012345678@s.whatsapp.net",
                chat = "493012345678@s.whatsapp.net",
                pushName = "Mama",
                outgoing = true
            ),
            contacts
        )!!

        assertEquals("493012345678", message.senderName)
    }

    @Test
    fun `a message with nothing to show is dropped`() {
        assertNull(WhatsAppMessageMapper.fromBridge(incoming(text = "   "), contacts))
        assertNull(WhatsAppMessageMapper.fromBridge(incoming(messageId = ""), contacts))
        assertNull(WhatsAppMessageMapper.fromBridge(incoming(sender = "status@broadcast"), contacts))
    }

    @Test
    fun `answering from a laptop is still her daughter`() {
        val message = WhatsAppMessageMapper.fromBridge(
            incoming(sender = "41791234567:12@s.whatsapp.net"),
            contacts
        )!!

        assertEquals("Anna", message.senderName)
        assertEquals("17", message.contactId)
    }

    @Test
    fun `a group message is shown, and carries the group it came from`() {
        val message = WhatsAppMessageMapper.fromBridge(
            incoming(chat = "1234567890-1234567890@g.us", isGroup = true),
            contacts
        )!!

        // Shown — hiding it would make the sender vanish. But it carries a group identity, and
        // that is what stops a reply to eight people going privately to whoever wrote.
        assertNotNull(message.groupIdentity)
        assertEquals(WhatsAppMessageMapper.GROUP_CREATOR.uppercase(), message.groupIdentity!!.creatorIdentity)
        assertEquals("1234567890-1234567890@g.us", message.groupIdentity!!.groupId)
    }

    @Test
    fun `a group is recognised from the chat address even when the flag is missing`() {
        val message = WhatsAppMessageMapper.fromBridge(
            incoming(chat = "1234567890-1234567890@g.us", isGroup = false),
            contacts
        )!!

        assertNotNull(message.groupIdentity)
    }

    @Test
    fun `a private message carries no group`() {
        assertNull(WhatsAppMessageMapper.fromBridge(incoming(), contacts)!!.groupIdentity)
    }

    @Test
    fun `a reply to one person goes to that person`() {
        val message = WhatsAppMessageMapper.fromBridge(incoming(), contacts)!!

        assertEquals("41791234567@s.whatsapp.net", WhatsAppMessageMapper.replyTarget(message))
    }

    @Test
    fun `a reply to a group goes to the group and never to the one who wrote`() {
        val message = WhatsAppMessageMapper.fromBridge(
            incoming(chat = "1234567890-1234567890@g.us", isGroup = true),
            contacts
        )!!

        assertEquals("1234567890-1234567890@g.us", WhatsAppMessageMapper.replyTarget(message))
    }

    @Test
    fun `contactFor finds nobody rather than the wrong person`() {
        assertEquals(anna, WhatsAppMessageMapper.contactFor("41791234567@s.whatsapp.net", contacts))
        assertNull(WhatsAppMessageMapper.contactFor("493012345678@s.whatsapp.net", contacts))
        assertNull(WhatsAppMessageMapper.contactFor(null, contacts))
        assertNull(WhatsAppMessageMapper.contactFor("41791234567@s.whatsapp.net", emptyList()))
    }

    @Test
    fun `the group creator slot cannot be mistaken for a threema identity`() {
        // GroupIdentity was built for Threema and upper-cases its creator. A Threema ID is
        // exactly eight characters of A-Z and 0-9 — and the first version of this constant was
        // the word "whatsapp", which is exactly that shape. This test is why it is not any more.
        assertTrue(WhatsAppMessageMapper.GROUP_CREATOR.length != 8)
        assertTrue(WhatsAppMessageMapper.GROUP_CREATOR.any { !it.isLetterOrDigit() })
    }

    @Test
    fun `a backfilled message is shown but is not news`() {
        // The newest thing each person said, imported when the phone is linked. Shown, so the
        // Messages list is not empty on day one — and already read, because a fortnight of
        // unread badges arriving at once teaches her in a day that the badge means nothing.
        val backfilled = WhatsAppMessageMapper.fromBridge(
            incoming().copy(historical = true),
            contacts
        )!!

        assertTrue(backfilled.isRead)
        assertEquals("Anna", backfilled.senderName)
    }

    @Test
    fun `a message arriving now is unread`() {
        assertFalse(WhatsAppMessageMapper.fromBridge(incoming(), contacts)!!.isRead)
    }

    @Test
    fun `a message answering an earlier message maps quotedId with whatsapp prefix and resolves sender`() {
        val message = WhatsAppMessageMapper.fromBridge(
            incoming(
                quotedId = "STANZA_99",
                quotedSender = "41791234567@s.whatsapp.net",
                quotedText = "Kommst du am Sonntag zum Essen?"
            ),
            contacts
        )!!

        assertEquals("whatsapp_STANZA_99", message.quotedId)
        // Quoted sender resolved against her contacts list ("Anna").
        assertEquals("Anna", message.quotedSenderName)
        assertEquals("Kommst du am Sonntag zum Essen?", message.quotedText)
        // quotedIsHers is left false here; ConversationBuilder resolves it against conversation history.
        assertFalse(message.quotedIsHers)
    }

    @Test
    fun `a quote from an unknown number shows the number as quotedSenderName`() {
        val message = WhatsAppMessageMapper.fromBridge(
            incoming(
                quotedId = "STANZA_100",
                quotedSender = "493012345678@s.whatsapp.net",
                quotedText = "Guten Tag"
            ),
            contacts
        )!!

        assertEquals("whatsapp_STANZA_100", message.quotedId)
        assertEquals("493012345678", message.quotedSenderName)
        assertEquals("Guten Tag", message.quotedText)
    }

    @Test
    fun `a message without quote metadata leaves quote fields null`() {
        val message = WhatsAppMessageMapper.fromBridge(
            incoming(quotedId = null, quotedSender = null, quotedText = null),
            contacts
        )!!

        assertNull(message.quotedId)
        assertNull(message.quotedSenderName)
        assertNull(message.quotedText)
        assertFalse(message.quotedIsHers)
    }

    @Test
    fun `reactionParticipant is null for a 1-to-1 message`() {
        val message = WhatsAppMessageMapper.fromBridge(incoming(), contacts)!!

        assertNull(message.groupIdentity)
        assertNull(WhatsAppMessageMapper.reactionParticipant(message))
    }

    @Test
    fun `reactionParticipant is the sender personal JID for a group message`() {
        val message = WhatsAppMessageMapper.fromBridge(
            incoming(chat = "1234567890-1234567890@g.us", sender = "41791234567@s.whatsapp.net", isGroup = true),
            contacts
        )!!

        assertNotNull(message.groupIdentity)
        assertEquals("41791234567@s.whatsapp.net", WhatsAppMessageMapper.reactionParticipant(message))
    }

    @Test
    fun `reactionParticipant is null for a group message whose sender is the group JID`() {
        val groupMessage = WhatsAppMessageMapper.fromBridge(
            incoming(chat = "1234567890-1234567890@g.us", sender = "41791234567@s.whatsapp.net", isGroup = true),
            contacts
        )!!
        val unresolvedSenderMessage = groupMessage.copy(
            senderId = "1234567890-1234567890@g.us",
            senderAddress = null
        )

        assertNotNull(unresolvedSenderMessage.groupIdentity)
        assertNull(WhatsAppMessageMapper.reactionParticipant(unresolvedSenderMessage))
    }

    @Test
    fun `reactionParticipant is null for a group message with blank sender`() {
        val groupMessage = WhatsAppMessageMapper.fromBridge(
            incoming(chat = "1234567890-1234567890@g.us", isGroup = true),
            contacts
        )!!
        val blankSenderMessage = groupMessage.copy(
            senderId = "   ",
            senderAddress = null
        )

        assertNull(WhatsAppMessageMapper.reactionParticipant(blankSenderMessage))
    }

    @Test
    fun `a live message takes receivedAt while backfilled history leaves it zero`() {
        val live = WhatsAppMessageMapper.fromBridge(
            incoming = incoming(),
            contacts = contacts,
            receivedAt = 1_786_200_010_000L
        )
        assertNotNull(live)
        assertEquals(1_786_200_010_000L, live!!.receivedAt)

        val backfilled = WhatsAppMessageMapper.fromBridge(
            incoming = incoming().copy(historical = true),
            contacts = contacts,
            receivedAt = 1_786_200_010_000L
        )
        assertNotNull(backfilled)
        assertEquals(0L, backfilled!!.receivedAt)
    }

    private fun call(
        callId: String = "A1B2",
        caller: String = "41791234567@s.whatsapp.net",
        timestamp: Long = 1_786_200_000_000L,
        group: Boolean = false
    ) = WhatsAppEvent.Call(
        callId = callId,
        callerJid = caller,
        state = CallState.ENDED,
        reason = "timeout",
        timestamp = timestamp,
        fromMe = false,
        group = group
    )

    @Test
    fun `a missed WhatsApp call is filed under the person who rang`() {
        val message = WhatsAppMessageMapper.missedCall(call(), contacts, "Verpasster Anruf")

        assertNotNull(message)
        message!!
        assertEquals("Anna", message.senderName)
        assertEquals("17", message.contactId)
        assertEquals("+41791234567", message.senderAddress)
        assertEquals("Verpasster Anruf", message.text)
        assertTrue(message.isMissedCall)
        assertFalse(message.isRead)
        assertEquals(MessageDirection.INCOMING, message.direction)
        assertEquals(BridgeTransport.WHATSAPP, message.transport)
        assertEquals(MessageChannel.ON_DEVICE, message.channel)
    }

    @Test
    fun `the ringing and the ending of one call cannot become two missed calls`() {
        val first = WhatsAppMessageMapper.missedCall(call(), contacts, "Verpasster Anruf")
        val again = WhatsAppMessageMapper.missedCall(call(), contacts, "Verpasster Anruf")

        assertEquals(first!!.id, again!!.id)
    }

    @Test
    fun `somebody her address book has never heard of still gets a card, named by their number`() {
        val message = WhatsAppMessageMapper.missedCall(
            call(caller = "41999999999@s.whatsapp.net"),
            contacts,
            "Verpasster Anruf"
        )

        assertNotNull(message)
        assertEquals("41999999999", message!!.senderName)
        assertNull(message.contactId)
    }

    @Test
    fun `a call from a hidden id is not shown at all`() {
        // A card naming nobody, whose green button would ring nobody.
        assertNull(WhatsAppMessageMapper.missedCall(call(caller = "2345678901@lid"), contacts, "x"))
    }
}
