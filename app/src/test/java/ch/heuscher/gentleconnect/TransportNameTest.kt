package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.BridgeTransport
import ch.heuscher.gentleconnect.model.MessageChannel
import ch.heuscher.gentleconnect.model.transportName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the Advanced Mode facts panel calls the way a message travelled.
 */
class TransportNameTest {

    @Test
    fun `a text message is called SMS, not unknown`() {
        // Found on the phone. BridgeTransport says which messenger a reply must go back out
        // over, and for an SMS there is none — so every text message in the panel was filed as
        // UNKNOWN, which is exactly the wrong word to show somebody working out where a message
        // went.
        assertEquals("SMS", transportName(MessageChannel.DEVICE, BridgeTransport.UNKNOWN))
    }

    @Test
    fun `the messengers keep their own names`() {
        assertEquals("THREEMA", transportName(MessageChannel.ON_DEVICE, BridgeTransport.THREEMA))
        assertEquals("WHATSAPP", transportName(MessageChannel.BRIDGE, BridgeTransport.WHATSAPP))
    }

    @Test
    fun `a bridged message with no transport is still unknown`() {
        // Here the word is true, and replacing it would hide the one case worth looking into.
        assertEquals("UNKNOWN", transportName(MessageChannel.BRIDGE, BridgeTransport.UNKNOWN))
    }
}
