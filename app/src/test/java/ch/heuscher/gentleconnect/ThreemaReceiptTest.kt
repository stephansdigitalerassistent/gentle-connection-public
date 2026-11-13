package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.ThreemaReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The receipts that tell whoever wrote to her that the message arrived, and that she read it.
 *
 * Tested closely because both halves of the failure are silent. A malformed receipt is discarded
 * by the other end with no error reported anywhere, and the symptom is the thing that was already
 * wrong before any of this existed: the sender sees one grey tick for ever and concludes the
 * telephone is not working.
 */
class ThreemaReceiptTest {

    /** A real message id, exactly as the bridge printed it in the device log. */
    private val realId = "b7a2e10ab3b9b953"

    @Test
    fun `the message id goes on the wire byte-reversed`() {
        // The one thing here that can be wrong and still look right. The bridge prints the id
        // with `{:016x}`, which is the *number*, most significant byte first; Threema puts it on
        // the wire little-endian. Sending the printed order makes a perfectly well-formed receipt
        // for a message that does not exist, which the other end discards without a word.
        val body = ThreemaReceipt.body(ThreemaReceipt.RECEIVED, realId)

        // b7 a2 e1 0a b3 b9 b9 53  ->  53 b9 b9 b3 0a e1 a2 b7
        assertEquals("01" + "53b9b9b30ae1a2b7", body)
    }

    @Test
    fun `a receipt is the kind byte followed by the eight bytes of the id`() {
        val body = ThreemaReceipt.body(ThreemaReceipt.READ, "0102030405060708")

        // Kind, then the id reversed pair by pair.
        assertEquals("020807060504030201", body)
        assertEquals(18, body?.length)
    }

    @Test
    fun `read and received are different bodies for the same message`() {
        val received = ThreemaReceipt.body(ThreemaReceipt.RECEIVED, realId)
        val read = ThreemaReceipt.body(ThreemaReceipt.READ, realId)

        assertEquals("01", received?.take(2))
        assertEquals("02", read?.take(2))
        assertEquals(received?.drop(2), read?.drop(2))
    }

    @Test
    fun `an id that is not a Threema message id is refused`() {
        // Better no receipt than a receipt naming something else: acknowledging a message
        // somebody did not send is worse than acknowledging nothing.
        assertNull(ThreemaReceipt.body(ThreemaReceipt.READ, ""))
        assertNull(ThreemaReceipt.body(ThreemaReceipt.READ, "b7a2e10a"))
        assertNull(ThreemaReceipt.body(ThreemaReceipt.READ, "b7a2e10ab3b9b953ff"))
        assertNull(ThreemaReceipt.body(ThreemaReceipt.READ, "zzzzzzzzzzzzzzzz"))
    }

    @Test
    fun `a receipt kind this app does not send is refused`() {
        // 0x03 and 0x04 are the legacy thumbs-up and thumbs-down reactions, not delivery state.
        // Sending one of those as though it were a receipt would put a reaction on her
        // daughter's message that she never made.
        assertNull(ThreemaReceipt.body(0x03, "0102030405060708"))
        assertNull(ThreemaReceipt.body(0x00, "0102030405060708"))
    }

    @Test
    fun `capitals and spacing in an id do not change the bytes`() {
        assertEquals(
            ThreemaReceipt.body(ThreemaReceipt.RECEIVED, "0102030405060708"),
            ThreemaReceipt.body(ThreemaReceipt.RECEIVED, " 0102030405060708 ")
        )
        assertEquals(
            ThreemaReceipt.body(ThreemaReceipt.RECEIVED, "0102030405060708"),
            ThreemaReceipt.body(ThreemaReceipt.RECEIVED, "0102030405060708".uppercase())
        )
    }

    @Test
    fun `only a Threema message is acknowledged`() {
        assertEquals(
            "020807060504030201",
            ThreemaReceipt.bodyForStoredId(ThreemaReceipt.READ, "threema_0102030405060708", "threema_")
        )

        // A WhatsApp id put through here would name an arbitrary Threema message.
        assertNull(
            ThreemaReceipt.bodyForStoredId(ThreemaReceipt.READ, "whatsapp_3EB0", "threema_")
        )
        assertNull(
            ThreemaReceipt.bodyForStoredId(ThreemaReceipt.READ, "0102030405060708", "threema_")
        )
    }
}
