package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.model.WriteRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteRequestTest {

    @Test
    fun `a plain number is the person`() {
        assertEquals("0791234567", WriteRequest.firstAddressIn("0791234567"))
    }

    @Test
    fun `a number written the way a person writes it survives`() {
        // Kept exactly as it came. Normalising is the view model's job and it knows her country;
        // this must not quietly turn a foreign number into a Swiss one.
        assertEquals("+41 79 123 45 67", WriteRequest.firstAddressIn("+41 79 123 45 67"))
    }

    @Test
    fun `text somebody else wrote is not carried into her message`() {
        assertEquals("0791234567", WriteRequest.firstAddressIn("0791234567?body=Hallo%20Mama"))
    }

    @Test
    fun `several recipients become the first`() {
        assertEquals("0791234567", WriteRequest.firstAddressIn("0791234567,0797654321"))
        assertEquals("0791234567", WriteRequest.firstAddressIn("0791234567;0797654321"))
    }

    @Test
    fun `naming nobody is not an address`() {
        // A bare smsto: means "open the messages", and the caller has to be able to tell that from
        // a request that names somebody.
        assertNull(WriteRequest.firstAddressIn(""))
        assertNull(WriteRequest.firstAddressIn("   "))
        assertNull(WriteRequest.firstAddressIn(null))
        assertNull(WriteRequest.firstAddressIn("?body=Hallo"))
    }

    @Test
    fun `every message scheme is a request to write, in any case`() {
        // Chrome hands over `sms:` as a VIEW; apps hand over `smsto:` as a SENDTO. Both are ours.
        listOf("sms", "smsto", "mms", "mmsto", "SMS", "SmsTo").forEach {
            assertTrue(it, WriteRequest.isMessageScheme(it))
        }
    }

    @Test
    fun `other schemes are not`() {
        // A VIEW of a telephone number or a web page must go to the keys or nowhere, not here.
        listOf("tel", "http", "https", "mailto", "", null).forEach {
            assertFalse("$it", WriteRequest.isMessageScheme(it))
        }
    }
}
