package ch.heuscher.gentleconnect

import ch.heuscher.gentleconnect.util.LogRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRecorderTest {

    @Test
    fun `a first start takes the whole ring buffer`() {
        val command = LogRecorder.command("/f/app.log", sinceMillis = null)

        assertFalse(command.contains("-T"))
        assertEquals("/f/app.log", command[command.indexOf("-f") + 1])
    }

    @Test
    fun `a restart carries on from where the previous process stopped writing`() {
        val command = LogRecorder.command("/f/app.log", sinceMillis = 1_789_000_123_045L)

        // logcat's epoch form, seconds then milliseconds padded to three digits.
        assertEquals("1789000123.045", command[command.indexOf("-T") + 1])
    }

    @Test
    fun `logcat rotates the file itself`() {
        val command = LogRecorder.command("/f/app.log", sinceMillis = null)

        assertEquals("5120", command[command.indexOf("-r") + 1])
        assertEquals("9", command[command.indexOf("-n") + 1])
    }
}
