package ch.heuscher.gentleconnect.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailSenderFilterTest {

    private val filter = MailSenderFilter(
        knownAddresses = setOf("anna@example.com", "Dr.Weber@Praxis.CH"),
        allowlist = setOf("gemeinde@example.org")
    )

    @Test
    fun `a contact of hers gets through`() {
        assertTrue(filter.isAllowed("Anna Müller <anna@example.com>"))
    }

    @Test
    fun `an allowlisted sender gets through`() {
        assertTrue(filter.isAllowed("Gemeinde <gemeinde@example.org>"))
    }

    @Test
    fun `an unknown sender is hidden`() {
        assertFalse(filter.isAllowed("Newsletter <news@shop.example>"))
    }

    @Test
    fun `case and surrounding whitespace do not decide whether she sees a letter`() {
        assertTrue(filter.isAllowed("  Dr. Weber <dr.weber@praxis.ch>  "))
        assertTrue(filter.isAllowed("<ANNA@EXAMPLE.COM>"))
    }

    @Test
    fun `a display name matching a contact does not get an unknown address through`() {
        // The From display name is written by whoever sent the mail. If this passed, anyone
        // could reach her by putting a familiar first name in front of their own address.
        assertFalse(filter.isAllowed("Anna <not-anna@spam.example>"))
        assertFalse(filter.isAllowed("\"anna@example.com\" <spammer@spam.example>"))
    }

    @Test
    fun `a header with no address at all is hidden`() {
        assertFalse(filter.isAllowed("Anna"))
        assertFalse(filter.isAllowed(""))
        assertFalse(filter.isAllowed(null))
    }

    @Test
    fun `a bare address with no display name is handled`() {
        assertTrue(filter.isAllowed("anna@example.com"))
    }

    @Test
    fun `a malformed header does not throw and is hidden`() {
        assertFalse(filter.isAllowed("<<<>>> not a header"))
    }

    @Test
    fun `the address is extracted from the header, not the display name`() {
        assertEquals("anna@example.com", MailSenderFilter.parseFromHeader("Anna <anna@example.com>"))
        assertEquals("anna@example.com", MailSenderFilter.parseFromHeader("anna@example.com"))
        assertEquals("", MailSenderFilter.parseFromHeader("Anna"))
    }

    @Test
    fun `an empty filter hides everything rather than showing everything`() {
        val empty = MailSenderFilter()
        assertFalse(empty.isAllowed("Anna <anna@example.com>"))
    }
}
