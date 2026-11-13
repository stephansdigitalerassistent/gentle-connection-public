package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.telecom.UnknownCallers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who gets hung up on.
 *
 * The most consequential four lines in the telephone: switched on, this decides in a few
 * milliseconds whether her daughter's midwife, her surgery or a man asking for her bank details
 * gets through, and there is nobody watching when it runs.
 */
class UnknownCallersTest {

    @Test
    fun `switched off, nobody is ever cut off`() {
        // The default, and the state most phones should stay in.
        assertFalse(UnknownCallers.shouldEnd(blocking = false, number = "", inAddressBook = false))
        assertFalse(
            UnknownCallers.shouldEnd(blocking = false, number = null, inAddressBook = false)
        )
        assertFalse(
            UnknownCallers.shouldEnd(blocking = false, number = "+41791234567", inAddressBook = false)
        )
    }

    @Test
    fun `somebody in her address book always gets through`() {
        assertFalse(
            UnknownCallers.shouldEnd(blocking = true, number = "+41791234567", inAddressBook = true)
        )
    }

    @Test
    fun `a number she has not saved is ended`() {
        assertTrue(
            UnknownCallers.shouldEnd(blocking = true, number = "+41791234567", inAddressBook = false)
        )
    }

    @Test
    fun `a withheld number is ended`() {
        // Nobody to recognise, and hiding the number is exactly the behaviour the switch is for.
        assertTrue(UnknownCallers.shouldEnd(blocking = true, number = null, inAddressBook = false))
        assertTrue(UnknownCallers.shouldEnd(blocking = true, number = "", inAddressBook = false))
        assertTrue(UnknownCallers.shouldEnd(blocking = true, number = "   ", inAddressBook = false))
    }

    @Test
    fun `a withheld number is ended even if something claims to know it`() {
        // Guards against a lookup that returns a match for an empty string — some contact
        // providers do — quietly turning the switch off for exactly the callers it exists for.
        assertTrue(UnknownCallers.shouldEnd(blocking = true, number = "", inAddressBook = true))
    }

    @Test
    fun `call log inclusion depends on the caregiver setting for rejected and blocked calls`() {
        // Regular incoming, outgoing, missed calls are always included
        assertTrue(UnknownCallers.shouldIncludeInCallLog(callType = 1, showBlockedInCallLog = false))
        assertTrue(UnknownCallers.shouldIncludeInCallLog(callType = 2, showBlockedInCallLog = false))
        assertTrue(UnknownCallers.shouldIncludeInCallLog(callType = 3, showBlockedInCallLog = false))

        // Rejected calls are always included regardless of setting
        assertTrue(UnknownCallers.shouldIncludeInCallLog(callType = UnknownCallers.REJECTED_TYPE, showBlockedInCallLog = false))
        assertTrue(UnknownCallers.shouldIncludeInCallLog(callType = UnknownCallers.REJECTED_TYPE, showBlockedInCallLog = true))

        // Blocked calls depend on the caregiver setting
        assertFalse(UnknownCallers.shouldIncludeInCallLog(callType = UnknownCallers.BLOCKED_TYPE, showBlockedInCallLog = false))
        assertTrue(UnknownCallers.shouldIncludeInCallLog(callType = UnknownCallers.BLOCKED_TYPE, showBlockedInCallLog = true))
    }
}
