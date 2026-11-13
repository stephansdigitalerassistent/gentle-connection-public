package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.reply.SmsSender
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The formatting in these tests is how the numbers are actually written in her address book.
 */
class SmsAddressTest {

    @Test
    fun `spaces are removed`() {
        // Read straight off the phone. Sent as-is, the stack took the destination as far as
        // the first space and tried to deliver to "+41".
        assertEquals("+41795550114", SmsSender.normalizeAddress("+41 79 555 01 14"))
    }

    @Test
    fun `the leading plus survives`() {
        assertEquals("+41795550114", SmsSender.normalizeAddress("+41795550114"))
        assertEquals("+41315550115", SmsSender.normalizeAddress("+41 31 555 01 15"))
    }

    @Test
    fun `a national number keeps its leading zero and gains no plus`() {
        assertEquals("0795550114", SmsSender.normalizeAddress("079 555 01 14"))
        assertEquals("0315550110", SmsSender.normalizeAddress("031 555 01 10"))
    }

    @Test
    fun `the other ways a number gets written down`() {
        assertEquals("+41795550114", SmsSender.normalizeAddress("+41-79-555-01-14"))
        assertEquals("+41795550114", SmsSender.normalizeAddress("+41 (79) 555 01 14"))
        assertEquals("+41795550114", SmsSender.normalizeAddress("  +41 79 555 01 14  "))
    }

    @Test
    fun `nothing to send to comes back blank`() {
        assertEquals("", SmsSender.normalizeAddress(""))
        assertEquals("", SmsSender.normalizeAddress("   "))
        // A service name, which has no subscriber behind it.
        assertEquals("", SmsSender.normalizeAddress("RoamingInfo"))
    }
}
