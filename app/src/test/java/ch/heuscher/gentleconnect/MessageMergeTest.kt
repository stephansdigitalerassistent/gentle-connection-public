package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.data.MessageMerger
import ch.heuscher.gentleconnect.model.Message
import ch.heuscher.gentleconnect.model.MessageChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageMergeTest {

    @Test
    fun `mergeMessages prefers bridge copy over notification copy within 30 seconds`() {
        val now = 100_000L

        val notifMsg = Message(
            id = "notif_1",
            senderName = "Lisa",
            text = "Hallo Mama",
            timestamp = now + 10_000L,
            sourcePackage = "com.whatsapp",
            channel = MessageChannel.DEVICE
        )

        val bridgeMsg = Message(
            id = "bridge_1",
            senderName = "Lisa",
            text = "Hallo Mama",
            timestamp = now,
            sourcePackage = "ch.threema.app",
            senderId = "ECHO1001",
            channel = MessageChannel.BRIDGE
        )

        val merged = MessageMerger.mergeMessages(
            notificationMsgs = listOf(notifMsg),
            bridgeMsgs = listOf(bridgeMsg)
        )

        assertEquals(1, merged.size)
        assertEquals("bridge_1", merged[0].id)
        assertEquals(MessageChannel.BRIDGE, merged[0].channel)
    }

    @Test
    fun `mergeMessages keeps both messages when time gap is 45 seconds`() {
        val now = 100_000L

        val bridgeMsg = Message(
            id = "bridge_1",
            senderName = "Lisa",
            text = "Hallo Mama",
            timestamp = now,
            sourcePackage = "ch.threema.app",
            channel = MessageChannel.BRIDGE
        )

        val notifMsg = Message(
            id = "notif_1",
            senderName = "Lisa",
            text = "Hallo Mama",
            timestamp = now + 45_000L, // 45 seconds gap
            sourcePackage = "com.whatsapp",
            channel = MessageChannel.DEVICE
        )

        val merged = MessageMerger.mergeMessages(
            notificationMsgs = listOf(notifMsg),
            bridgeMsgs = listOf(bridgeMsg)
        )

        assertEquals(2, merged.size)
        assertEquals("notif_1", merged[0].id)
        assertEquals("bridge_1", merged[1].id)
    }

    @Test
    fun `mergeMessages sorts output list by timestamp descending`() {
        val msg1 = Message(
            id = "m1",
            senderName = "Anna",
            text = "Eins",
            timestamp = 1000L,
            sourcePackage = "com.whatsapp",
            channel = MessageChannel.DEVICE
        )
        val msg2 = Message(
            id = "m2",
            senderName = "Bob",
            text = "Zwei",
            timestamp = 3000L,
            sourcePackage = "ch.threema.app",
            channel = MessageChannel.BRIDGE
        )
        val msg3 = Message(
            id = "m3",
            senderName = "Carl",
            text = "Drei",
            timestamp = 2000L,
            sourcePackage = "com.whatsapp",
            channel = MessageChannel.DEVICE
        )

        val merged = MessageMerger.mergeMessages(
            notificationMsgs = listOf(msg1, msg3),
            bridgeMsgs = listOf(msg2)
        )

        assertEquals(3, merged.size)
        assertEquals("m2", merged[0].id)
        assertEquals("m3", merged[1].id)
        assertEquals("m1", merged[2].id)
    }
}
